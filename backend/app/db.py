"""Persistent party storage and recovery layer.

Supports PostgreSQL via asyncpg/psycopg or SQLite fallback for local test/dev.
When DATABASE_URL is not set or empty, runs as a no-op so in-memory store operates
without external database dependencies.
"""

from __future__ import annotations

import json
import logging
import sqlite3
from typing import Any

from . import config

log = logging.getLogger("jam.db")


class Database:
    def __init__(self, db_url: str = "") -> None:
        self.db_url = db_url.strip()
        self._is_sqlite = False
        self._sqlite_conn: sqlite3.Connection | None = None
        self._enabled = bool(self.db_url)

    async def init(self) -> None:
        if not self._enabled:
            return

        if self.db_url.startswith("sqlite") or "://" not in self.db_url:
            path = self.db_url.replace("sqlite:///", "").replace("sqlite://", "")
            if not path or path == ":memory:":
                path = ":memory:"
            self._is_sqlite = True
            self._sqlite_conn = sqlite3.connect(path, check_same_thread=False)
            self._sqlite_conn.execute("PRAGMA journal_mode=WAL")
            self._sqlite_conn.execute("""
                CREATE TABLE IF NOT EXISTS parties (
                    code TEXT PRIMARY KEY,
                    data TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
            """)
            self._sqlite_conn.commit()
            log.info("Initialized SQLite persistent store at %s", path)
        else:
            # PostgreSQL URL handling
            try:
                import asyncpg  # type: ignore
                # Test connection and create tables
                conn = await asyncpg.connect(self.db_url)
                await conn.execute("""
                    CREATE TABLE IF NOT EXISTS parties (
                        code VARCHAR(8) PRIMARY KEY,
                        data JSONB NOT NULL,
                        updated_at_ms BIGINT NOT NULL
                    );
                """)
                await conn.close()
                log.info("Initialized PostgreSQL persistent store")
            except Exception as exc:
                log.warning("Could not initialize PostgreSQL store (%s). Running in-memory.", exc)
                self._enabled = False

    async def save_party(self, code: str, party_wire: dict[str, Any], updated_at_ms: int) -> None:
        if not self._enabled:
            return
        data_str = json.dumps(party_wire)
        try:
            if self._is_sqlite and self._sqlite_conn:
                self._sqlite_conn.execute(
                    "INSERT OR REPLACE INTO parties (code, data, updated_at_ms) VALUES (?, ?, ?)",
                    (code, data_str, updated_at_ms),
                )
                self._sqlite_conn.commit()
            else:
                import asyncpg  # type: ignore
                conn = await asyncpg.connect(self.db_url)
                try:
                    await conn.execute(
                        """
                        INSERT INTO parties (code, data, updated_at_ms)
                        VALUES ($1, $2::jsonb, $3)
                        ON CONFLICT (code) DO UPDATE
                        SET data = EXCLUDED.data, updated_at_ms = EXCLUDED.updated_at_ms
                        """,
                        code,
                        data_str,
                        updated_at_ms,
                    )
                finally:
                    await conn.close()
        except Exception as exc:
            log.warning("Failed to persist party %s: %s", code, exc)

    async def delete_party(self, code: str) -> None:
        if not self._enabled:
            return
        try:
            if self._is_sqlite and self._sqlite_conn:
                self._sqlite_conn.execute("DELETE FROM parties WHERE code = ?", (code,))
                self._sqlite_conn.commit()
            else:
                import asyncpg  # type: ignore
                conn = await asyncpg.connect(self.db_url)
                try:
                    await conn.execute("DELETE FROM parties WHERE code = $1", code)
                finally:
                    await conn.close()
        except Exception as exc:
            log.warning("Failed to delete party %s from db: %s", code, exc)

    async def load_all(self, max_age_ms: int, now_ms: int) -> list[dict[str, Any]]:
        if not self._enabled:
            return []
        cutoff = now_ms - max_age_ms
        loaded: list[dict[str, Any]] = []
        try:
            if self._is_sqlite and self._sqlite_conn:
                cursor = self._sqlite_conn.execute(
                    "SELECT data FROM parties WHERE updated_at_ms > ?", (cutoff,)
                )
                for row in cursor.fetchall():
                    loaded.append(json.loads(row[0]))
            else:
                import asyncpg  # type: ignore
                conn = await asyncpg.connect(self.db_url)
                try:
                    rows = await conn.fetch(
                        "SELECT data FROM parties WHERE updated_at_ms > $1", cutoff
                    )
                    for r in rows:
                        loaded.append(json.loads(r["data"]) if isinstance(r["data"], str) else r["data"])
                finally:
                    await conn.close()
            log.info("Recovered %d parties from persistent storage", len(loaded))
        except Exception as exc:
            log.warning("Failed to load parties from db: %s", exc)
        return loaded


db = Database(config.DATABASE_URL)
