"""Request bodies for the REST half, and the protocol names the WebSocket half uses."""

from __future__ import annotations

from typing import Any
from pydantic import BaseModel, Field, field_validator


class JoinRequest(BaseModel):
    """The identity a device presents in order to create or join a party."""

    user_id: str = Field(alias="userId", min_length=1, max_length=128)
    device_id: str = Field(alias="deviceId", min_length=1, max_length=128)
    display_name: str = Field(alias="displayName", min_length=1, max_length=80)
    avatar_url: str | None = Field(alias="avatarUrl", default=None, max_length=1000)
    max_members: int | None = Field(alias="maxMembers", default=None, ge=2, le=10)
    autoplay_enabled: bool | None = Field(alias="autoplayEnabled", default=None)
    host_only_control: bool | None = Field(alias="hostOnlyControl", default=None)

    model_config = {"populate_by_name": True}

    @field_validator("user_id", "device_id", "display_name")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("must not be blank")
        return cleaned

    @field_validator("avatar_url")
    @classmethod
    def _http_only(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = value.strip()
        if not cleaned:
            return None
        if not cleaned.startswith(("http://", "https://")):
            raise ValueError("must be an http(s) URL")
        return cleaned


class PartyPreviewMember(BaseModel):
    display_name: str = Field(alias="displayName")
    avatar_url: str | None = Field(alias="avatarUrl", default=None)
    is_host: bool = Field(alias="isHost", default=False)

    model_config = {"populate_by_name": True}


class PartyPreviewResponse(BaseModel):
    code: str
    host_name: str = Field(alias="hostName")
    member_count: int = Field(alias="memberCount")
    max_members: int = Field(alias="maxMembers")
    is_full: bool = Field(alias="isFull")
    members: list[PartyPreviewMember] = Field(default_factory=list)

    model_config = {"populate_by_name": True}


# -- WebSocket frame types, server → client --------------------------------

WELCOME = "welcome"
STATE = "state"
QUEUE = "queue"
MEMBERS = "members"
ACTIVITY = "activity"
PONG = "pong"
ERROR = "error"
BYE = "bye"

# -- WebSocket frame types, client → server --------------------------------

PING = "ping"
CONTROL = "control"
SYNC = "sync"
SYNC_QUEUE = "syncQueue"
REPORT = "report"

# -- Control actions -------------------------------------------------------

ACTION_PLAY = "play"
ACTION_PAUSE = "pause"
ACTION_SEEK = "seek"
ACTION_SET_TRACK = "setTrack"
ACTION_SET_QUEUE = "setQueue"
ACTION_QUEUE_ADD = "queueAdd"
ACTION_QUEUE_REMOVE = "queueRemove"
ACTION_QUEUE_CLEAR = "queueClear"
ACTION_QUEUE_MOVE = "queueMove"
ACTION_NEXT = "next"
ACTION_PREVIOUS = "previous"
ACTION_KICK = "kick"
ACTION_SET_MAX_MEMBERS = "setMaxMembers"
ACTION_SET_AUTOPLAY = "setAutoplay"
ACTION_SET_HOST_ONLY_CONTROL = "setHostOnlyControl"

# Actions restricted when HostOnlyControl is active
CONTROL_ACTIONS: set[str] = {
    ACTION_PLAY,
    ACTION_PAUSE,
    ACTION_SEEK,
    ACTION_SET_TRACK,
    ACTION_SET_QUEUE,
    ACTION_QUEUE_ADD,
    ACTION_QUEUE_REMOVE,
    ACTION_QUEUE_CLEAR,
    ACTION_QUEUE_MOVE,
    ACTION_NEXT,
    ACTION_PREVIOUS,
    ACTION_SET_AUTOPLAY,
}
