"""The domain: what a party is, who is in it, and what it is playing."""

from __future__ import annotations

import secrets
import time
from dataclasses import dataclass, field
from typing import Any

from . import codes, config
from .clock import now_ms


class PartyError(Exception):
    """A request that is refused for a reason the caller should be told."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


@dataclass(slots=True)
class Track:
    """A song, in the only terms every device needs to agree on."""

    video_id: str
    title: str = ""
    artist: str = ""
    thumbnail_url: str | None = None
    duration_ms: int | None = None
    from_autoplay: bool = False
    added_by: str | None = None
    added_by_name: str | None = None
    queue_id: str | None = None

    @staticmethod
    def from_wire(raw: Any) -> "Track | None":
        if not isinstance(raw, dict):
            return None
        video_id = str(raw.get("videoId") or "").strip()
        if not video_id:
            return None
        duration = raw.get("durationMs")
        return Track(
            video_id=video_id[:128],
            title=str(raw.get("title") or "")[:300],
            artist=str(raw.get("artist") or "")[:300],
            thumbnail_url=(str(raw["thumbnailUrl"])[:1000] if raw.get("thumbnailUrl") else None),
            duration_ms=int(duration) if isinstance(duration, (int, float)) and duration > 0 else None,
            from_autoplay=bool(raw.get("fromAutoplay", False)),
            added_by=str(raw.get("addedBy"))[:64] if raw.get("addedBy") else None,
            added_by_name=str(raw.get("addedByName"))[:80] if raw.get("addedByName") else None,
            queue_id=str(raw.get("queueId"))[:64] if raw.get("queueId") else None,
        )

    def to_wire(self) -> dict[str, Any]:
        return {
            "videoId": self.video_id,
            "title": self.title,
            "artist": self.artist,
            "thumbnailUrl": self.thumbnail_url,
            "durationMs": self.duration_ms,
            "fromAutoplay": self.from_autoplay,
            "addedBy": self.added_by,
            "addedByName": self.added_by_name,
            "queueId": self.queue_id,
        }


@dataclass(slots=True)
class PlaybackState:
    """Where the party is in what it is playing, as of a server timestamp."""

    track: Track | None = None
    queue: list[Track] = field(default_factory=list)
    queue_index: int = -1
    is_playing: bool = False
    position_ms: int = 0
    anchor_ms: int = field(default_factory=now_ms)
    seq: int = 0
    queue_seq: int = 0
    updated_by: str | None = None
    updated_at_ms: int = field(default_factory=now_ms)
    started_by: str | None = None
    started_by_name: str | None = None
    autoplay_enabled: bool = False

    def position_at(self, server_ms: int) -> int:
        if not self.is_playing:
            return self.position_ms
        elapsed = max(0, server_ms - self.anchor_ms)
        position = self.position_ms + elapsed
        duration = self.track.duration_ms if self.track else None
        if duration is not None:
            return min(position, duration)
        return position

    def _touch(self, member_id: str | None) -> None:
        self.seq += 1
        self.updated_by = member_id
        self.updated_at_ms = now_ms()

    def _touch_queue(self, member_id: str | None) -> None:
        self.queue_seq += 1
        self.updated_by = member_id
        self.updated_at_ms = now_ms()

    def play(self, member_id: str | None, position_ms: int | None = None) -> None:
        now = now_ms()
        start_at = position_ms if position_ms is not None else self.position_at(now)
        self.position_ms = max(0, start_at)
        self.is_playing = True
        self.anchor_ms = now + config.PLAY_LEAD_MS
        self._touch(member_id)

    def pause(self, member_id: str | None, position_ms: int | None = None) -> None:
        now = now_ms()
        self.position_ms = max(0, position_ms if position_ms is not None else self.position_at(now))
        self.is_playing = False
        self.anchor_ms = now
        self._touch(member_id)

    def seek(self, member_id: str | None, position_ms: int) -> None:
        self.position_ms = max(0, position_ms)
        self.anchor_ms = now_ms() + (config.PLAY_LEAD_MS if self.is_playing else 0)
        self._touch(member_id)

    def set_track(
        self,
        member_id: str | None,
        track: Track | None,
        position_ms: int = 0,
        is_playing: bool = True,
        queue_index: int | None = None,
        member_name: str | None = None,
    ) -> None:
        self.track = track
        self.position_ms = max(0, position_ms)
        self.is_playing = is_playing and track is not None
        self.anchor_ms = now_ms() + (config.PLAY_LEAD_MS if self.is_playing else 0)
        if queue_index is not None:
            self.queue_index = queue_index
        elif track is not None:
            match = next(
                (i for i, item in enumerate(self.queue) if item.video_id == track.video_id),
                -1,
            )
            self.queue_index = match
        self.started_by = member_id
        self.started_by_name = member_name
        self._touch(member_id)

    def set_queue(
        self,
        member_id: str | None,
        queue: list[Track],
        queue_index: int,
    ) -> None:
        self.queue = queue[: config.MAX_QUEUE_LENGTH]
        self.queue_index = queue_index if 0 <= queue_index < len(self.queue) else -1
        self.queue_seq += 1
        self._touch(member_id)

    def add_upcoming(
        self,
        member_id: str | None,
        tracks: list[Track],
        play_next: bool = False,
        member_name: str | None = None,
    ) -> tuple[bool, str, str]:
        """Adds tracks to the upcoming section, respecting the 25-upcoming cap."""
        if not tracks:
            return True, "", ""

        current_upcoming_count = len(self.queue) - max(0, self.queue_index + 1)
        if current_upcoming_count + len(tracks) > config.MAX_UPCOMING_QUEUE:
            return (
                False,
                "queue_full",
                f"Queue is full (maximum {config.MAX_UPCOMING_QUEUE} upcoming songs).",
            )

        stamped_tracks: list[Track] = []
        for t in tracks:
            qid = t.queue_id or secrets.token_hex(6)
            stamped = Track(
                video_id=t.video_id,
                title=t.title,
                artist=t.artist,
                thumbnail_url=t.thumbnail_url,
                duration_ms=t.duration_ms,
                from_autoplay=t.from_autoplay,
                added_by=member_id,
                added_by_name=member_name,
                queue_id=qid,
            )
            stamped_tracks.append(stamped)

        if play_next and self.queue_index >= 0:
            insert_idx = self.queue_index + 1
            self.queue[insert_idx:insert_idx] = stamped_tracks
        else:
            self.queue.extend(stamped_tracks)

        self._touch_queue(member_id)
        return True, "", ""

    def remove_upcoming(self, member_id: str | None, video_id: str | None = None, index: int | None = None) -> bool:
        """Removes a track from the upcoming queue."""
        base = max(0, self.queue_index + 1)
        target_idx = -1

        if index is not None and base <= index < len(self.queue):
            target_idx = index
        elif video_id:
            for idx in range(base, len(self.queue)):
                if self.queue[idx].video_id == video_id:
                    target_idx = idx
                    break

        if target_idx < 0:
            return False

        self.queue.pop(target_idx)
        self._touch_queue(member_id)
        return True

    def clear_upcoming(self, member_id: str | None) -> bool:
        """Clears all upcoming tracks while keeping current song untouched."""
        base = max(0, self.queue_index + 1)
        if base >= len(self.queue):
            return False
        self.queue = self.queue[:base]
        self._touch_queue(member_id)
        return True

    def move_upcoming(
        self,
        member_id: str | None,
        from_index: int,
        to_index: int,
        video_id: str | None = None,
    ) -> bool:
        """Reorders tracks within upcoming without disturbing the playing song."""
        base = max(0, self.queue_index + 1)
        actual_from = base + from_index
        actual_to = base + to_index

        if not (base <= actual_from < len(self.queue)) or not (base <= actual_to < len(self.queue)):
            return False

        if video_id and self.queue[actual_from].video_id != video_id:
            return False

        item = self.queue.pop(actual_from)
        self.queue.insert(actual_to, item)
        self._touch_queue(member_id)
        return True

    def set_autoplay(self, member_id: str | None, enabled: bool) -> None:
        self.autoplay_enabled = enabled
        self._touch(member_id)

    def step(self, member_id: str | None, delta: int, member_name: str | None = None) -> bool:
        target = self.queue_index + delta
        if not (0 <= target < len(self.queue)):
            return False
        self.set_track(
            member_id,
            self.queue[target],
            position_ms=0,
            queue_index=target,
            member_name=member_name,
        )
        return True

    def to_wire(self, server_ms: int | None = None) -> dict[str, Any]:
        now = server_ms if server_ms is not None else now_ms()
        return {
            "seq": self.seq,
            "track": self.track.to_wire() if self.track else None,
            "queueSeq": self.queue_seq,
            "queueIndex": self.queue_index,
            "queueLength": len(self.queue),
            "isPlaying": self.is_playing,
            "positionMs": self.position_ms,
            "anchorMs": self.anchor_ms,
            "effectivePositionMs": self.position_at(now),
            "updatedBy": self.updated_by,
            "startedBy": self.started_by,
            "startedByName": self.started_by_name,
            "autoplayEnabled": self.autoplay_enabled,
            "updatedAtMs": self.updated_at_ms,
        }

    def queue_to_wire(self) -> dict[str, Any]:
        return {
            "seq": self.queue_seq,
            "index": self.queue_index,
            "items": [item.to_wire() for item in self.queue],
        }


@dataclass(slots=True)
class Member:
    """One signed-in device in a party."""

    member_id: str
    user_id: str
    device_id: str
    display_name: str
    avatar_url: str | None
    token: str
    is_host: bool = False
    joined_at_ms: int = field(default_factory=now_ms)
    last_seen_ms: int = field(default_factory=now_ms)
    connected: bool = False
    wants_activity: bool = False
    control_budget: float = float(config.CONTROL_RATE_PER_SECOND)
    control_budget_at_ms: int = field(default_factory=now_ms)
    frame_budget: float = float(config.FRAME_RATE_PER_SECOND)
    frame_budget_at_ms: int = field(default_factory=now_ms)

    def to_wire(self) -> dict[str, Any]:
        return {
            "memberId": self.member_id,
            "userId": self.user_id,
            "displayName": self.display_name,
            "avatarUrl": self.avatar_url,
            "isHost": self.is_host,
            "connected": self.connected,
            "joinedAtMs": self.joined_at_ms,
            "lastSeenMs": self.last_seen_ms,
        }


@dataclass(slots=True)
class Party:
    code: str
    max_members: int = config.MAX_MEMBERS
    host_only_control: bool = config.HOST_ONLY_CONTROL_DEFAULT
    members: dict[str, Member] = field(default_factory=dict)
    playback: PlaybackState = field(default_factory=PlaybackState)
    created_at_ms: int = field(default_factory=now_ms)
    touched_at_ms: int = field(default_factory=now_ms)
    empty_since_ms: int | None = field(default_factory=now_ms)
    activities: list[dict[str, Any]] = field(default_factory=list)

    @property
    def host(self) -> Member | None:
        return next((m for m in self.members.values() if m.is_host), None)

    def may_control(self, member: Member) -> bool:
        """Checks if the member is permitted to control playback and queue."""
        return not self.host_only_control or member.is_host

    def set_host_only_control(self, member: Member, enabled: bool) -> None:
        if not member.is_host:
            raise PartyError(403, "host_only", "Only the host can change who controls the music.")
        if self.host_only_control == enabled:
            return
        self.host_only_control = enabled
        self.touch()

    def set_max_members(self, member: Member, max_members: int) -> None:
        if not member.is_host:
            raise PartyError(403, "host_only", "Only the host can change the party size.")
        if max_members < 2 or max_members > 10:
            raise PartyError(422, "invalid_capacity", "Party size must be between 2 and 10.")
        if max_members < len(self.members):
            raise PartyError(409, "party_too_small", "Party size cannot be smaller than current member count.")
        self.max_members = max_members
        self.touch()

    def set_autoplay(self, member: Member, enabled: bool) -> None:
        if not self.may_control(member):
            raise PartyError(403, "host_only", "Only the host can change AutoPlay in this party.")
        self.playback.set_autoplay(member.member_id, enabled)
        self.touch()

    def record_activity(self, action: str, by_name: str, detail: str) -> dict[str, Any]:
        activity = {
            "type": "activity",
            "action": action,
            "by": by_name,
            "detail": detail,
            "atMs": now_ms(),
        }
        self.activities.append(activity)
        if len(self.activities) > 50:
            self.activities.pop(0)
        return activity

    def to_preview(self) -> dict[str, Any]:
        """Safe representation for guests before joining."""
        host_name = self.host.display_name if self.host else ""
        return {
            "code": self.code,
            "hostName": host_name,
            "memberCount": len(self.members),
            "maxMembers": self.max_members,
            "isFull": len(self.members) >= self.max_members,
            "members": [
                {
                    "displayName": m.display_name,
                    "avatarUrl": m.avatar_url,
                    "isHost": m.is_host,
                }
                for m in sorted(self.members.values(), key=lambda m: m.joined_at_ms)
            ],
        }

    def occupied_slots(self) -> int:
        return len(self.members)

    def join(
        self,
        user_id: str,
        device_id: str,
        display_name: str,
        avatar_url: str | None,
        max_members: int | None = None,
        host_only_control: bool | None = None,
        autoplay_enabled: bool | None = None,
    ) -> Member:
        existing = next(
            (m for m in self.members.values() if m.device_id == device_id),
            None,
        )
        if existing is not None:
            existing.display_name = display_name
            existing.avatar_url = avatar_url
            existing.user_id = user_id
            existing.last_seen_ms = now_ms()
            existing.token = secrets.token_urlsafe(24)
            self.touch()
            return existing

        if len(self.members) >= self.max_members:
            raise PartyError(
                409,
                "party_full",
                f"This party is full ({self.max_members} devices).",
            )

        is_first = len(self.members) == 0
        if is_first:
            if max_members is not None and 2 <= max_members <= 10:
                self.max_members = max_members
            if host_only_control is not None:
                self.host_only_control = host_only_control
            if autoplay_enabled is not None:
                self.playback.autoplay_enabled = autoplay_enabled

        member = Member(
            member_id=secrets.token_hex(8),
            user_id=user_id,
            device_id=device_id,
            display_name=display_name,
            avatar_url=avatar_url,
            token=secrets.token_urlsafe(24),
            is_host=is_first,
        )
        self.members[member.member_id] = member
        self.touch()
        return member

    def authenticate(self, token: str) -> Member:
        member = next((m for m in self.members.values() if secrets.compare_digest(m.token, token)), None)
        if member is None:
            raise PartyError(401, "bad_token", "This device is not a member of that party.")
        return member

    def remove(self, member_id: str) -> Member | None:
        member = self.members.pop(member_id, None)
        if member is None:
            return None
        if member.is_host and self.members:
            successor = next(iter(self.members.values()), None)
            if successor is not None:
                successor.is_host = True
        self.touch()
        self._refresh_emptiness()
        return member

    def spend_control_budget(self, member: Member) -> bool:
        now = now_ms()
        elapsed_s = max(0, now - member.control_budget_at_ms) / 1000.0
        member.control_budget = min(
            float(config.CONTROL_RATE_PER_SECOND),
            member.control_budget + elapsed_s * config.CONTROL_RATE_PER_SECOND,
        )
        member.control_budget_at_ms = now
        if member.control_budget < 1.0:
            return False
        member.control_budget -= 1.0
        return True

    def spend_frame_budget(self, member: Member) -> bool:
        now = now_ms()
        elapsed_s = max(0, now - member.frame_budget_at_ms) / 1000.0
        member.frame_budget = min(
            float(config.FRAME_RATE_PER_SECOND),
            member.frame_budget + elapsed_s * config.FRAME_RATE_PER_SECOND,
        )
        member.frame_budget_at_ms = now
        if member.frame_budget < 1.0:
            return False
        member.frame_budget -= 1.0
        return True

    def touch(self) -> None:
        self.touched_at_ms = now_ms()

    def mark_connected(self, member: Member, connected: bool) -> None:
        member.connected = connected
        member.last_seen_ms = now_ms()
        self.touch()
        self._refresh_emptiness()

    def _refresh_emptiness(self) -> None:
        if any(m.connected for m in self.members.values()):
            self.empty_since_ms = None
        elif self.empty_since_ms is None:
            self.empty_since_ms = now_ms()

    def expired_members(self, now: int) -> list[Member]:
        return [
            m
            for m in self.members.values()
            if not m.connected and now - m.last_seen_ms > config.DISCONNECT_GRACE_MS
        ]

    def is_expired(self, now: int) -> bool:
        if now - self.created_at_ms > config.PARTY_MAX_AGE_MS:
            return True
        if self.empty_since_ms is not None:
            return now - self.empty_since_ms > config.EMPTY_PARTY_TTL_MS
        return False

    def to_wire(self) -> dict[str, Any]:
        now = now_ms()
        return {
            "code": self.code,
            "createdAtMs": self.created_at_ms,
            "maxMembers": self.max_members,
            "hostOnlyControl": self.host_only_control,
            "members": [m.to_wire() for m in sorted(self.members.values(), key=lambda m: m.joined_at_ms)],
            "playback": self.playback.to_wire(now),
            "queue": self.playback.queue_to_wire(),
            "serverMs": now,
        }


class PartyStore:
    """Manages active parties with optional database persistence for recovery."""

    def __init__(self) -> None:
        self._parties: dict[str, Party] = {}
        self._creation_log: list[float] = []

    def __len__(self) -> int:
        return len(self._parties)

    def clear(self) -> None:
        self._parties.clear()
        self._creation_log.clear()

    def _check_creation_rate_limit(self) -> None:
        if config.CREATE_RATE_PER_MINUTE <= 0:
            return
        now = time.time()
        minute_ago = now - 60.0
        self._creation_log = [t for t in self._creation_log if t > minute_ago]
        if len(self._creation_log) >= config.CREATE_RATE_PER_MINUTE:
            raise PartyError(429, "rate_limited", "Creating parties too fast. Please wait a minute.")
        self._creation_log.append(now)

    def create(
        self,
        max_members: int = config.MAX_MEMBERS,
        host_only_control: bool = config.HOST_ONLY_CONTROL_DEFAULT,
    ) -> Party:
        self._check_creation_rate_limit()

        if len(self._parties) >= config.MAX_PARTIES:
            raise PartyError(503, "server_full", "The party server is busy. Please try again in a few minutes.")

        for _ in range(12):
            code = codes.new_code()
            if code not in self._parties:
                party = Party(
                    code=code,
                    max_members=max_members,
                    host_only_control=host_only_control,
                )
                self._parties[code] = party
                return party
        raise PartyError(503, "code_exhausted", "Could not allocate a party code.")

    def get(self, code: str) -> Party:
        party = self._parties.get(codes.normalise(code))
        if party is None:
            raise PartyError(404, "no_such_party", "No party with that code.")
        return party

    def find(self, code: str) -> Party | None:
        return self._parties.get(codes.normalise(code))

    def drop(self, code: str) -> None:
        self._parties.pop(code, None)

    def all(self) -> list[Party]:
        return list(self._parties.values())

    def sweep(self, now: int) -> list[Party]:
        changed: list[Party] = []
        for party in list(self._parties.values()):
            gone = party.expired_members(now)
            for member in gone:
                party.remove(member.member_id)
            if party.is_expired(now):
                self._parties.pop(party.code, None)
                continue
            if gone:
                changed.append(party)
        return changed
