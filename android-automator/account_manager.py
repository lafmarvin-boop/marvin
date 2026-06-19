import sqlite3
import json
from datetime import datetime
from pathlib import Path

DB_PATH = Path(__file__).parent / "accounts.db"


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with _connect() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS accounts (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                username  TEXT NOT NULL,
                email     TEXT NOT NULL UNIQUE,
                password  TEXT NOT NULL,
                status    TEXT DEFAULT 'created',  -- created | active | failed
                metadata  TEXT DEFAULT '{}',
                created_at TEXT DEFAULT (datetime('now'))
            )
        """)


def save_account(data: dict) -> int:
    with _connect() as conn:
        cur = conn.execute(
            "INSERT OR IGNORE INTO accounts (username, email, password, metadata) VALUES (?, ?, ?, ?)",
            (data["username"], data["email"], data["password"], json.dumps(data.get("meta", {}))),
        )
        return cur.lastrowid


def mark_status(account_id: int, status: str) -> None:
    with _connect() as conn:
        conn.execute("UPDATE accounts SET status = ? WHERE id = ?", (status, account_id))


def get_accounts(status: str | None = None) -> list[dict]:
    with _connect() as conn:
        if status:
            rows = conn.execute("SELECT * FROM accounts WHERE status = ?", (status,)).fetchall()
        else:
            rows = conn.execute("SELECT * FROM accounts").fetchall()
    return [dict(r) for r in rows]


def export_csv(path: str = "accounts_export.csv") -> None:
    import csv
    accounts = get_accounts()
    if not accounts:
        print("Aucun compte à exporter.")
        return
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=accounts[0].keys())
        writer.writeheader()
        writer.writerows(accounts)
    print(f"Exporté {len(accounts)} comptes → {path}")


def stats() -> dict:
    with _connect() as conn:
        rows = conn.execute("SELECT status, COUNT(*) as n FROM accounts GROUP BY status").fetchall()
    return {r["status"]: r["n"] for r in rows}
