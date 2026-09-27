"""YZ Music Listen Together — realtime party synchronization server."""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse

from . import codes, config, protocol
from .clock import now_ms
from .db import db
from .hub import Hub
from .party import Member, Party, PartyError, PartyStore, Track

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("jam")

store = PartyStore()
hub = Hub()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Initialize DB and recover active parties
    await db.init()
    recovered_data = await db.load_all(config.PARTY_MAX_AGE_MS, now_ms())
    for p_data in recovered_data:
        code = p_data.get("code")
        if code and code not in store._parties:
            p = Party(
                code=code,
                max_members=p_data.get("maxMembers", config.MAX_MEMBERS),
                host_only_control=p_data.get("hostOnlyControl", config.HOST_ONLY_CONTROL_DEFAULT),
                created_at_ms=p_data.get("createdAtMs", now_ms()),
            )
            store._parties[code] = p

    ticker = asyncio.create_task(_heartbeat())
    try:
        yield
    finally:
        ticker.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await ticker


app = FastAPI(
    title="YZ Music Listen Together",
    version="1.7.0",
    lifespan=lifespan,
)

if config.ALLOWED_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=config.ALLOWED_ORIGINS,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )


@app.exception_handler(PartyError)
async def _party_error(_: Request, exc: PartyError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"error": exc.code, "message": exc.message})


# ---------------------------------------------------------------- REST ----


@app.get("/")
async def root() -> dict[str, Any]:
    return {
        "service": "yz-music-listen-together",
        "version": "1.7.0",
        "maxMembers": config.MAX_MEMBERS,
        "maxUpcomingQueue": config.MAX_UPCOMING_QUEUE,
        "parties": len(store),
        "serverMs": now_ms(),
    }


@app.get("/healthz")
async def healthz() -> dict[str, Any]:
    return {"ok": True, "serverMs": now_ms()}


@app.get("/invite/{code}", response_class=HTMLResponse)
async def invite_landing(code: str) -> str:
    clean_code = codes.clean(code)
    vercel_url = f"{config.PUBLIC_BASE_URL}/join/{clean_code}"
    scheme_url = f"yzmusic://party?code={clean_code}"
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Join Party {clean_code} - YZ Music</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0c0d10; color: #fff; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 20px; }}
        .card {{ background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12); border-radius: 24px; padding: 32px; max-width: 400px; width: 100%; text-align: center; box-shadow: 0 20px 40px rgba(0,0,0,0.5); backdrop-filter: blur(20px); }}
        h1 {{ font-size: 24px; margin: 0 0 8px; font-weight: 700; }}
        p {{ color: rgba(255,255,255,0.7); font-size: 14px; line-height: 1.5; margin: 0 0 24px; }}
        .code {{ font-size: 36px; font-weight: 800; letter-spacing: 6px; color: #3b82f6; margin-bottom: 24px; font-family: monospace; }}
        .btn {{ display: block; width: 100%; padding: 14px 0; background: #3b82f6; color: #fff; font-weight: 600; text-decoration: none; border-radius: 14px; font-size: 16px; margin-bottom: 12px; }}
        .btn-sub {{ background: rgba(255,255,255,0.1); color: #fff; }}
    </style>
</head>
<body>
    <div class="card">
        <h1>🎵 YZ Music Party</h1>
        <p>You've been invited to Play Together!</p>
        <div class="code">{clean_code}</div>
        <a class="btn" href="{scheme_url}">Open in YZ Music</a>
        <a class="btn btn-sub" href="{vercel_url}">Join on Web</a>
    </div>
</body>
</html>"""


@app.get("/api/time")
async def server_time() -> dict[str, Any]:
    return {"serverMs": now_ms()}


async def _authenticated(
    code: str,
    authorization: str | None = Header(default=None),
) -> tuple[Party, Member]:
    party = store.get(code)
    token = ""
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
    if not token:
        raise PartyError(401, "no_token", "Missing party token.")
    return party, party.authenticate(token)


@app.get("/api/parties/{code}/preview")
async def party_preview(code: str) -> dict[str, Any]:
    party = store.find(code)
    if party is None:
        raise PartyError(404, "no_such_party", "No party with that code.")
    return party.to_preview()


@app.post("/api/parties", status_code=201)
async def create_party(body: protocol.JoinRequest) -> dict[str, Any]:
    max_m = body.max_members or config.MAX_MEMBERS
    host_only = body.host_only_control if body.host_only_control is not None else config.HOST_ONLY_CONTROL_DEFAULT
    party = store.create(max_members=max_m, host_only_control=host_only)
    member = party.join(
        user_id=body.user_id,
        device_id=body.device_id,
        display_name=body.display_name,
        avatar_url=body.avatar_url,
        max_members=max_m,
        host_only_control=host_only,
        autoplay_enabled=body.autoplay_enabled,
    )
    asyncio.create_task(db.save_party(party.code, party.to_wire(), party.touched_at_ms))
    return _membership_payload(party, member)


@app.post("/api/parties/{code}/join", status_code=200)
async def join_party(code: str, body: protocol.JoinRequest) -> dict[str, Any]:
    party = store.get(code)
    member = party.join(
        user_id=body.user_id,
        device_id=body.device_id,
        display_name=body.display_name,
        avatar_url=body.avatar_url,
    )
    asyncio.create_task(db.save_party(party.code, party.to_wire(), party.touched_at_ms))
    await hub.broadcast(party.code, _members_frame(party), skip=member.member_id)
    return _membership_payload(party, member)


@app.get("/api/parties/{code}")
async def get_party(auth: tuple[Party, Member] = Depends(_authenticated)) -> dict[str, Any]:
    party, _ = auth
    return party.to_wire()


@app.get("/api/parties/{code}/activity")
async def get_party_activity(code: str) -> list[dict[str, Any]]:
    party = store.find(code)
    if party is None:
        raise PartyError(404, "no_such_party", "No party with that code.")
    return party.activities


@app.post("/api/parties/{code}/leave", status_code=200)
async def leave_party(auth: tuple[Party, Member] = Depends(_authenticated)) -> dict[str, Any]:
    party, member = auth
    party.remove(member.member_id)
    await hub.send(party.code, member.member_id, {"type": protocol.BYE, "reason": "left"})
    if not party.members:
        store.drop(party.code)
        await hub.drop_party(party.code)
        asyncio.create_task(db.delete_party(party.code))
    else:
        await hub.broadcast(party.code, _members_frame(party))
        asyncio.create_task(db.save_party(party.code, party.to_wire(), party.touched_at_ms))
    return {"ok": True}


# ----------------------------------------------------------- WebSocket ----


@app.websocket("/ws/parties/{code}")
async def party_socket(
    socket: WebSocket,
    code: str,
    token: str = Query(default=""),
) -> None:
    # 1. Resolve token from Authorization header or ?token= query parameter
    auth_header = socket.headers.get("authorization")
    if auth_header and auth_header.lower().startswith("bearer "):
        resolved_token = auth_header[7:].strip()
    else:
        resolved_token = token.strip()

    party = store.find(code)
    if party is None:
        await _reject(socket, 4404, "no_such_party", "No party with that code.")
        return

    try:
        member = party.authenticate(resolved_token)
    except PartyError as exc:
        await _reject(socket, 4401, exc.code, exc.message)
        return

    # 2. Handshake accepted only after authentication passes
    await socket.accept()

    wants_activity = socket.query_params.get("activity") == "true" or "activity" in socket.headers.get("x-features", "")
    member.wants_activity = wants_activity

    await hub.attach(party.code, member.member_id, socket)
    party.mark_connected(member, True)
    await socket.send_json({
        "type": protocol.WELCOME,
        "you": member.to_wire(),
        "party": party.to_wire(),
        "serverMs": now_ms(),
    })
    await hub.broadcast(party.code, _members_frame(party), skip=member.member_id)

    try:
        while True:
            raw = await socket.receive_text()
            if len(raw) > config.MAX_FRAME_BYTES:
                await _reply(party, member, {
                    "type": protocol.ERROR,
                    "error": "frame_too_large",
                    "message": "WebSocket frame exceeded size limit.",
                })
                continue

            frame = json.loads(raw)
            await _handle_frame(party, member, frame)
    except WebSocketDisconnect:
        pass
    except Exception as exc:  # noqa: BLE001
        log.info("socket error in %s: %s", party.code, exc)
    finally:
        await hub.detach(party.code, member.member_id, socket)
        party.mark_connected(member, False)
        await hub.broadcast(party.code, _members_frame(party))
        asyncio.create_task(db.save_party(party.code, party.to_wire(), party.touched_at_ms))


async def _handle_frame(party: Party, member: Member, frame: Any) -> None:
    if not isinstance(frame, dict):
        return
    kind = frame.get("type")
    member.last_seen_ms = now_ms()

    # Frame budget check for all frames
    if not party.spend_frame_budget(member):
        await _reply(party, member, {
            "type": protocol.ERROR,
            "error": "rate_limited",
            "message": "Too many frames sent. Slow down.",
        })
        return

    if kind == protocol.PING:
        await _reply(party, member, {
            "type": protocol.PONG,
            "clientMs": frame.get("clientMs"),
            "serverMs": now_ms(),
        })
        return

    if kind == protocol.SYNC:
        await _reply(party, member, _state_frame(party))
        return

    if kind == protocol.SYNC_QUEUE:
        await _reply(party, member, _queue_frame(party))
        return

    if kind == protocol.REPORT:
        reported = _as_int(frame.get("positionMs"))
        if reported is not None and party.playback.is_playing:
            drift = reported - party.playback.position_at(now_ms())
            if abs(drift) > 1500:
                log.info("party %s: %s drifted %dms", party.code, member.display_name, drift)
        return

    if kind == protocol.CONTROL:
        if not party.spend_control_budget(member):
            await _reply(party, member, {
                "type": protocol.ERROR,
                "error": "rate_limited",
                "message": "Too many controls at once.",
            })
            return

        action = frame.get("action")
        # Check permissions
        if action in protocol.CONTROL_ACTIONS and not party.may_control(member):
            await _reply(party, member, {
                "type": protocol.ERROR,
                "error": "host_only",
                "message": "Only the host can control the music in this party.",
            })
            return

        queue_before = party.playback.queue_seq
        success, err_code, err_msg, activity_info = _apply_control(party, member, frame)

        if not success:
            await _reply(party, member, {
                "type": protocol.ERROR,
                "error": err_code or "bad_control",
                "message": err_msg or f"Unsupported control: {action!r}",
            })
            return

        party.touch()

        # Broadcast queue update if queue was modified
        if party.playback.queue_seq != queue_before:
            await hub.broadcast(party.code, _queue_frame(party))

        # Broadcast state frame to everyone
        await hub.broadcast(party.code, _state_frame(party))

        # Broadcast activity if recorded
        if activity_info:
            act_frame = party.record_activity(
                action=activity_info["action"],
                by_name=member.display_name,
                detail=activity_info["detail"],
            )
            for m in party.members.values():
                if getattr(m, "wants_activity", False):
                    await hub.send(party.code, m.member_id, act_frame)

        asyncio.create_task(db.save_party(party.code, party.to_wire(), party.touched_at_ms))


def _apply_control(
    party: Party,
    member: Member,
    frame: dict[str, Any],
) -> tuple[bool, str, str, dict[str, str] | None]:
    """Applies control mutation. Returns (success, err_code, err_msg, activity_info)."""
    action = frame.get("action")
    playback = party.playback
    who = member.member_id

    if action == protocol.ACTION_PLAY:
        playback.play(who, _as_int(frame.get("positionMs")))
        return True, "", "", {"action": action, "detail": "Started playback"}

    if action == protocol.ACTION_PAUSE:
        playback.pause(who, _as_int(frame.get("positionMs")))
        return True, "", "", {"action": action, "detail": "Paused playback"}

    if action == protocol.ACTION_SEEK:
        position = _as_int(frame.get("positionMs"))
        if position is None:
            return False, "invalid_position", "Seek position required.", None
        playback.seek(who, position)
        return True, "", "", {"action": action, "detail": "Changed the playback position"}

    if action == protocol.ACTION_SET_TRACK:
        track = Track.from_wire(frame.get("track"))
        playback.set_track(
            who,
            track,
            position_ms=_as_int(frame.get("positionMs")) or 0,
            is_playing=bool(frame.get("isPlaying", True)),
            queue_index=_as_int(frame.get("queueIndex")),
            member_name=member.display_name,
        )
        title = track.title if track and track.title else "track"
        return True, "", "", {"action": action, "detail": f"Changed the song to '{title}'"}

    if action == protocol.ACTION_SET_QUEUE:
        raw = frame.get("queue")
        if not isinstance(raw, list):
            return False, "invalid_queue", "Queue must be a list.", None
        queue = [track for track in (Track.from_wire(item) for item in raw) if track is not None]
        playback.set_queue(who, queue, _as_int(frame.get("queueIndex")) if frame.get("queueIndex") is not None else -1)
        return True, "", "", {"action": action, "detail": f"Replaced the queue with {len(queue)} songs"}

    if action == protocol.ACTION_QUEUE_ADD:
        raw_tracks = frame.get("tracks")
        raw_track = frame.get("track")
        to_add: list[Track] = []
        if isinstance(raw_tracks, list):
            to_add = [t for t in (Track.from_wire(item) for item in raw_tracks) if t is not None]
        elif isinstance(raw_track, dict):
            t = Track.from_wire(raw_track)
            if t:
                to_add = [t]

        play_next = bool(frame.get("playNext", False))
        ok, code, msg = playback.add_upcoming(who, to_add, play_next=play_next, member_name=member.display_name)
        if not ok:
            return False, code, msg, None

        first_title = to_add[0].title if to_add and to_add[0].title else "song"
        detail = f"Added '{first_title}' to queue" if len(to_add) == 1 else f"Added {len(to_add)} songs to queue"
        return True, "", "", {"action": action, "detail": detail}

    if action == protocol.ACTION_QUEUE_REMOVE:
        vid = str(frame.get("videoId") or "")
        idx = _as_int(frame.get("index"))
        if not playback.remove_upcoming(who, video_id=vid if vid else None, index=idx):
            return False, "not_found", "Track is not in the upcoming queue.", None
        return True, "", "", {"action": action, "detail": "Removed a song from the queue"}

    if action == protocol.ACTION_QUEUE_CLEAR:
        if not playback.clear_upcoming(who):
            return False, "no_upcoming", "No upcoming songs to clear.", None
        return True, "", "", {"action": action, "detail": "Cleared upcoming queue"}

    if action == protocol.ACTION_QUEUE_MOVE:
        from_idx = _as_int(frame.get("fromIndex"))
        to_idx = _as_int(frame.get("toIndex"))
        vid = str(frame.get("videoId") or "")
        if from_idx is None or to_idx is None:
            return False, "missing_indices", "fromIndex and toIndex required.", None
        if not playback.move_upcoming(who, from_idx, to_idx, video_id=vid if vid else None):
            return False, "invalid_move", "Invalid queue move indices.", None
        return True, "", "", {"action": action, "detail": "Reordered the upcoming queue"}

    if action == protocol.ACTION_NEXT:
        if not playback.step(who, 1, member.display_name):
            return False, "end_of_queue", "Already at the end of the queue.", None
        return True, "", "", {"action": action, "detail": "Skipped to next song"}

    if action == protocol.ACTION_PREVIOUS:
        if not playback.step(who, -1, member.display_name):
            return False, "start_of_queue", "Already at the beginning of the queue.", None
        return True, "", "", {"action": action, "detail": "Went back to previous song"}

    if action == protocol.ACTION_SET_MAX_MEMBERS:
        max_m = _as_int(frame.get("maxMembers"))
        if max_m is None:
            return False, "invalid_capacity", "maxMembers required.", None
        try:
            party.set_max_members(member, max_m)
            asyncio.create_task(hub.broadcast(party.code, _members_frame(party)))
            return True, "", "", {"action": action, "detail": f"Changed party size to {max_m}"}
        except PartyError as exc:
            return False, exc.code, exc.message, None

    if action == protocol.ACTION_SET_AUTOPLAY:
        enabled = bool(frame.get("enabled", False))
        try:
            party.set_autoplay(member, enabled)
            return True, "", "", {"action": action, "detail": f"Set AutoPlay to {enabled}"}
        except PartyError as exc:
            return False, exc.code, exc.message, None

    if action == protocol.ACTION_SET_HOST_ONLY_CONTROL:
        enabled = bool(frame.get("enabled", False))
        try:
            party.set_host_only_control(member, enabled)
            asyncio.create_task(hub.broadcast(party.code, _members_frame(party)))
            return True, "", "", {"action": action, "detail": f"Set host-only controls to {enabled}"}
        except PartyError as exc:
            return False, exc.code, exc.message, None

    if action == protocol.ACTION_KICK:
        target_id = str(frame.get("memberId") or "")
        if not member.is_host:
            return False, "host_only", "Only the host can kick listeners.", None
        if not target_id or target_id == member.member_id:
            return False, "invalid_member", "Choose another listener to remove.", None
        removed = party.remove(target_id)
        if removed is None:
            return False, "not_found", "Listener not found in this party.", None
        asyncio.create_task(hub.send(party.code, target_id, {
            "type": protocol.BYE,
            "reason": "kicked",
            "message": "The host removed you from this party.",
        }))
        asyncio.create_task(hub.broadcast(party.code, _members_frame(party)))
        return True, "", "", {"action": action, "detail": f"Removed {removed.display_name} from the party"}

    return False, "bad_control", f"Unsupported control: {action!r}", None


# -------------------------------------------------------------- shared ----


def _membership_payload(party: Party, member: Member) -> dict[str, Any]:
    return {
        "code": party.code,
        "token": member.token,
        "you": member.to_wire(),
        "party": party.to_wire(),
        "serverMs": now_ms(),
    }


def _state_frame(party: Party) -> dict[str, Any]:
    return {
        "type": protocol.STATE,
        "playback": party.playback.to_wire(now_ms()),
        "serverMs": now_ms(),
    }


def _queue_frame(party: Party) -> dict[str, Any]:
    return {
        "type": protocol.QUEUE,
        "queue": party.playback.queue_to_wire(),
        "serverMs": now_ms(),
    }


def _members_frame(party: Party) -> dict[str, Any]:
    return {
        "type": protocol.MEMBERS,
        "members": [m.to_wire() for m in sorted(party.members.values(), key=lambda m: m.joined_at_ms)],
        "maxMembers": party.max_members,
        "hostOnlyControl": party.host_only_control,
        "serverMs": now_ms(),
    }


async def _reject(socket: WebSocket, close_code: int, error: str, message: str) -> None:
    with contextlib.suppress(Exception):
        await socket.accept()
        await socket.send_json({"type": protocol.ERROR, "error": error, "message": message})
        await socket.close(code=close_code)


async def _reply(party: Party, member: Member, frame: dict[str, Any]) -> None:
    await hub.send(party.code, member.member_id, frame)


def _as_int(value: Any) -> int | None:
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


async def _heartbeat() -> None:
    interval_s = max(1.0, config.STATE_HEARTBEAT_MS / 1000.0)
    while True:
        await asyncio.sleep(interval_s)
        now = now_ms()
        for party in store.all():
            if hub.has_connections(party.code):
                await hub.broadcast(party.code, _state_frame(party))
        changed = store.sweep(now)
        for party in changed:
            await hub.broadcast(party.code, _members_frame(party))
            asyncio.create_task(db.save_party(party.code, party.to_wire(), party.touched_at_ms))
