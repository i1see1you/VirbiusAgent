"""SQLite for accounts, attempt logs (VirbiusGuard later), and best scores."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DB_PATH = DATA / "arena.db"
NAME_RE = re.compile(r"^[a-zA-Z0-9_]{3,20}$")
APP_SOLACE = "solace_profane_chat"
LEVELS = ("L1", "L2", "L3")


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def connect() -> sqlite3.Connection:
    DATA.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys = ON")
    return con


@contextmanager
def db():
    con = connect()
    try:
        yield con
        con.commit()
    finally:
        con.close()


def init() -> None:
    with db() as con:
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
              id INTEGER PRIMARY KEY,
              username TEXT UNIQUE NOT NULL,
              password_hash TEXT NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS attempts (
              id INTEGER PRIMARY KEY,
              user_id INTEGER NOT NULL REFERENCES users(id),
              app_slug TEXT NOT NULL,
              level TEXT NOT NULL,
              attack TEXT NOT NULL,
              reply TEXT NOT NULL,
              score INTEGER NOT NULL,
              passed INTEGER NOT NULL,
              hits INTEGER NOT NULL,
              tokens INTEGER NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS progress (
              user_id INTEGER NOT NULL REFERENCES users(id),
              app_slug TEXT NOT NULL,
              level TEXT NOT NULL,
              best_score INTEGER NOT NULL,
              cleared INTEGER NOT NULL,
              updated_at TEXT NOT NULL,
              PRIMARY KEY (user_id, app_slug, level)
            );
            CREATE INDEX IF NOT EXISTS idx_attempts_user ON attempts(user_id, created_at);
            CREATE INDEX IF NOT EXISTS idx_attempts_lookup ON attempts(user_id, app_slug, level, id);
            """
        )


def _hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 120_000)
    return salt.hex() + "$" + digest.hex()


def _check_password(password: str, stored: str) -> bool:
    salt_hex, digest_hex = stored.split("$", 1)
    again = _hash_password(password, bytes.fromhex(salt_hex))
    return hmac.compare_digest(again, stored)


def register(username: str, password: str) -> dict:
    username = username.strip()
    if not NAME_RE.match(username):
        raise ValueError("用户名用 3–20 位字母、数字或下划线")
    if len(password) < 6:
        raise ValueError("密码至少 6 位")
    with db() as con:
        try:
            cur = con.execute(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                (username, _hash_password(password), _now()),
            )
        except sqlite3.IntegrityError as e:
            raise ValueError("用户名已被占用") from e
        return {"id": cur.lastrowid, "username": username}


def login(username: str, password: str) -> dict:
    with db() as con:
        row = con.execute("SELECT id, username, password_hash FROM users WHERE username = ?", (username.strip(),)).fetchone()
    if not row or not _check_password(password, row["password_hash"]):
        raise ValueError("用户名或密码不对")
    return {"id": row["id"], "username": row["username"]}


def record_attempt(user_id: int, app_slug: str, level: str, attack: str, reply: str, result: dict) -> int:
    passed = 1 if result["passed"] else 0
    score = int(result["score"])
    with db() as con:
        cur = con.execute(
            """INSERT INTO attempts
               (user_id, app_slug, level, attack, reply, score, passed, hits, tokens, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                user_id,
                app_slug,
                level,
                attack,
                reply,
                score,
                passed,
                int(result.get("hits") or 0),
                int(result.get("tokens") or 0),
                _now(),
            ),
        )
        attempt_id = cur.lastrowid
        prev = con.execute(
            "SELECT best_score, cleared FROM progress WHERE user_id=? AND app_slug=? AND level=?",
            (user_id, app_slug, level),
        ).fetchone()
        best = score if prev is None else max(prev["best_score"], score)
        cleared = 1 if passed or (prev and prev["cleared"]) else 0
        con.execute(
            """INSERT INTO progress (user_id, app_slug, level, best_score, cleared, updated_at)
               VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(user_id, app_slug, level) DO UPDATE SET
                 best_score=excluded.best_score,
                 cleared=excluded.cleared,
                 updated_at=excluded.updated_at""",
            (user_id, app_slug, level, best, cleared, _now()),
        )
    return attempt_id


def list_attempts(user_id: int, app_slug: str, level: str, limit: int = 50) -> list[dict]:
    with db() as con:
        rows = con.execute(
            """SELECT id, level, attack, reply, score, passed, created_at
               FROM attempts
               WHERE user_id=? AND app_slug=? AND level=?
               ORDER BY id DESC
               LIMIT ?""",
            (user_id, app_slug, level, limit),
        ).fetchall()
    return [
        {
            "id": r["id"],
            "level": r["level"],
            "attack": r["attack"],
            "reply": r["reply"],
            "score": int(r["score"]),
            "passed": bool(r["passed"]),
            "created_at": r["created_at"],
        }
        for r in rows
    ]


def _empty_levels() -> dict:
    return {lvl: {"best_score": 0, "cleared": False} for lvl in LEVELS}


def progress_for(user_id: int, app_slug: str = APP_SOLACE) -> dict:
    with db() as con:
        rows = con.execute(
            "SELECT level, best_score, cleared FROM progress WHERE user_id=? AND app_slug=?",
            (user_id, app_slug),
        ).fetchall()
    out = _empty_levels()
    for row in rows:
        out[row["level"]] = {"best_score": row["best_score"], "cleared": bool(row["cleared"])}
    return out


def progress_all(user_id: int) -> dict:
    with db() as con:
        rows = con.execute(
            "SELECT app_slug, level, best_score, cleared FROM progress WHERE user_id=?",
            (user_id,),
        ).fetchall()
    out: dict[str, dict] = {}
    for row in rows:
        bucket = out.setdefault(row["app_slug"], _empty_levels())
        bucket[row["level"]] = {"best_score": row["best_score"], "cleared": bool(row["cleared"])}
    return out


def leaderboard(app_slug: str | None = None, limit: int = 50) -> list[dict]:
    with db() as con:
        if app_slug:
            rows = con.execute(
                """
                SELECT u.username,
                       COALESCE(SUM(p.best_score), 0) AS total,
                       MAX(CASE WHEN p.level='L1' THEN p.best_score END) AS l1,
                       MAX(CASE WHEN p.level='L2' THEN p.best_score END) AS l2,
                       MAX(CASE WHEN p.level='L3' THEN p.best_score END) AS l3,
                       (SELECT COUNT(*) FROM attempts a WHERE a.user_id = u.id AND a.app_slug = ?) AS tries
                FROM users u
                LEFT JOIN progress p ON p.user_id = u.id AND p.app_slug = ?
                GROUP BY u.id
                HAVING tries > 0 OR total > 0
                ORDER BY total DESC, tries ASC, u.username ASC
                LIMIT ?
                """,
                (app_slug, app_slug, limit),
            ).fetchall()
        else:
            rows = con.execute(
                """
                SELECT u.username,
                       COALESCE(SUM(p.best_score), 0) AS total,
                       MAX(CASE WHEN p.level='L1' THEN p.best_score END) AS l1,
                       MAX(CASE WHEN p.level='L2' THEN p.best_score END) AS l2,
                       MAX(CASE WHEN p.level='L3' THEN p.best_score END) AS l3,
                       (SELECT COUNT(*) FROM attempts a WHERE a.user_id = u.id) AS tries
                FROM users u
                LEFT JOIN progress p ON p.user_id = u.id
                GROUP BY u.id
                HAVING tries > 0 OR total > 0
                ORDER BY total DESC, tries ASC, u.username ASC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
    return [
        {
            "rank": i + 1,
            "username": r["username"],
            "total": int(r["total"] or 0),
            "l1": int(r["l1"] or 0),
            "l2": int(r["l2"] or 0),
            "l3": int(r["l3"] or 0),
            "tries": int(r["tries"] or 0),
        }
        for i, r in enumerate(rows)
    ]


def export_attempts_jsonl(path: Path | None = None) -> Path:
    """Dump prompts for later VirbiusGuard training. One JSON object per line."""
    out = path or (DATA / "attempts.jsonl")
    with db() as con:
        rows = con.execute(
            """SELECT a.id, u.username, a.app_slug, a.level, a.attack, a.reply,
                      a.score, a.passed, a.hits, a.tokens, a.created_at
               FROM attempts a JOIN users u ON u.id = a.user_id
               ORDER BY a.id"""
        ).fetchall()
    lines = []
    for r in rows:
        lines.append(
            json.dumps(
                {
                    "id": r["id"],
                    "username": r["username"],
                    "app_slug": r["app_slug"],
                    "level": r["level"],
                    "attack": r["attack"],
                    "reply": r["reply"],
                    "score": r["score"],
                    "passed": bool(r["passed"]),
                    "label": "jailbreak" if r["passed"] else "benign_or_failed",
                    "hits": r["hits"],
                    "tokens": r["tokens"],
                    "created_at": r["created_at"],
                },
                ensure_ascii=False,
            )
        )
    out.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return out


def ensure_secret() -> str:
    """Stable Flask secret, not committed."""
    env = os.environ.get("SNAPSHOT_SECRET")
    if env:
        return env
    path = DATA / ".secret"
    DATA.mkdir(parents=True, exist_ok=True)
    if path.exists():
        return path.read_text(encoding="utf-8").strip()
    token = secrets.token_hex(32)
    path.write_text(token, encoding="utf-8")
    return token
