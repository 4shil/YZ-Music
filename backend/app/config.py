"""Deployment knobs, all overridable from the environment on Render."""

from __future__ import annotations

import os


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _csv(name: str, default: str) -> list[str]:
    raw = os.environ.get(name) or default
    return [item.strip() for item in raw.split(",") if item.strip()]


def _str(name: str, default: str) -> str:
    raw = os.environ.get(name)
    return raw.strip() if raw and raw.strip() else default


# --- Core Configuration & Environment ---

DATABASE_URL = _str("DATABASE_URL", "")
PUBLIC_BASE_URL = _str("PUBLIC_BASE_URL", "https://yz-music.vercel.app")
WEBSOCKET_PUBLIC_URL = _str("WEBSOCKET_PUBLIC_URL", "wss://yz-music-party.onrender.com/ws/parties")
SESSION_SECRET = _str("SESSION_SECRET", "yz-music-together-session-secret-change-in-prod")
INVITE_SECRET = _str("INVITE_SECRET", "yz-music-together-invite-secret-change-in-prod")

# Allowed CORS origins, including Vercel frontend and local development
ALLOWED_ORIGINS = _csv(
    "CORS_ALLOWED_ORIGINS",
    "https://yz-music.vercel.app,http://localhost:3000,http://127.0.0.1:3000",
)

# Trust reverse-proxy headers (Render terminates TLS and provides real IP in X-Forwarded-For)
TRUST_PROXY = _bool("JAM_TRUST_PROXY", True)

# --- Party Limits & Timers ---

MAX_MEMBERS = _int("JAM_MAX_MEMBERS", 5)
MAX_UPCOMING_QUEUE = _int("JAM_MAX_UPCOMING_QUEUE", 25)
MAX_QUEUE_LENGTH = _int("JAM_MAX_QUEUE_LENGTH", 500)
MAX_PARTIES = _int("JAM_MAX_PARTIES", 100)
CREATE_RATE_PER_MINUTE = _int("JAM_CREATE_RATE_PER_MINUTE", 0)

STATE_HEARTBEAT_MS = _int("JAM_STATE_HEARTBEAT_MS", 5_000)
PLAY_LEAD_MS = _int("JAM_PLAY_LEAD_MS", 350)
DISCONNECT_GRACE_MS = _int("JAM_DISCONNECT_GRACE_MS", 45_000)
EMPTY_PARTY_TTL_MS = _int("JAM_EMPTY_PARTY_TTL_MS", 120_000)
PARTY_MAX_AGE_MS = _int("JAM_PARTY_MAX_AGE_MS", 12 * 60 * 60 * 1000)

# --- Rate Limiting & Security ---

CONTROL_RATE_PER_SECOND = _int("JAM_CONTROL_RATE_PER_SECOND", 25)
FRAME_RATE_PER_SECOND = _int("JAM_FRAME_RATE_PER_SECOND", 35)
MAX_FRAME_BYTES = _int("JAM_MAX_FRAME_BYTES", 64 * 1024)

# Host control policy: whether only the host can drive playback and queue by default
HOST_ONLY_CONTROL_DEFAULT = _bool("JAM_HOST_ONLY_CONTROL_DEFAULT", False)
