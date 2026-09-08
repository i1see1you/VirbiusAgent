# -*- coding: utf-8 -*-
"""Bank transaction database (ported from dvla-test).
Contains the intentionally vulnerable SQL string-concatenation query.
"""
import json
import os
import sqlite3


def _db_path(db_name="transactions.db"):
    """SQLite 文件放到可写目录。

    Helm 以 uid 999 跑，镜像层 /app 只读，相对路径 transactions.db 会
    unable to open database file。Compose / Helm 都注入 VIRBIUS_CONFIG_DIR=/data
    （PVC / named volume），本地未注入则回退到 demo 根目录。
    """
    if os.path.isabs(db_name):
        parent = os.path.dirname(db_name)
        if parent:
            os.makedirs(parent, exist_ok=True)
        return db_name
    root = os.environ.get("VIRBIUS_CONFIG_DIR") or os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))
    )
    os.makedirs(root, exist_ok=True)
    return os.path.join(root, db_name)


class TransactionDb:
    def __init__(self, db_name="transactions.db"):
        self.conn = sqlite3.connect(_db_path(db_name))
        self.create_tables()
        self.seed_data()

    def create_tables(self):
        cursor = self.conn.cursor()
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS Users (
                userId INTEGER PRIMARY KEY,
                username TEXT NOT NULL,
                password TEXT NOT NULL
            )
            """
        )
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS Transactions (
                transactionId INTEGER PRIMARY KEY,
                userId INTEGER NOT NULL,
                reference TEXT,
                recipient TEXT,
                amount REAL
            )
            """
        )
        self.conn.commit()

    def seed_data(self):
        cursor = self.conn.cursor()
        users = [
            (1, "MartyMcFly", "Password1"),
            (2, "DocBrown", "flux-capacitor-123"),
            (3, "BiffTannen", "Password3"),
            (4, "GeorgeMcFly", "Password4"),
        ]
        cursor.executemany(
            "INSERT OR IGNORE INTO Users (userId, username, password) VALUES (?, ?, ?)",
            users,
        )
        transactions = [
            (1, 1, "DeLoreanParts", "AutoShop", 1000.0),
            (2, 1, "SkateboardUpgrade", "SportsStore", 150.0),
            (3, 2, "PlutoniumPurchase", "FLAG:plutonium-256", 5000.0),
            (4, 2, "FluxCapacitor", "InnovativeTech", 3000.0),
            (5, 3, "SportsAlmanac", "RareBooks", 200.0),
            (6, 4, "WritingSupplies", "OfficeStore", 40.0),
            (7, 4, "SciFiNovels", "BookShop", 60.0),
        ]
        cursor.executemany(
            "INSERT OR IGNORE INTO Transactions "
            "(transactionId, userId, reference, recipient, amount) VALUES (?, ?, ?, ?, ?)",
            transactions,
        )
        self.conn.commit()

    def get_user_transactions(self, userId):
        """!! VULNERABLE: string concatenation enables SQL injection."""
        cursor = self.conn.cursor()
        cursor.execute(f"SELECT * FROM Transactions WHERE userId = '{str(userId)}'")
        rows = cursor.fetchall()
        columns = [column[0] for column in cursor.description]
        transactions = [dict(zip(columns, row)) for row in rows]
        return json.dumps(transactions, indent=4)

    def get_user(self, user_id):
        cursor = self.conn.cursor()
        cursor.execute(
            f"SELECT userId,username FROM Users WHERE userId = {str(user_id)}"
        )
        rows = cursor.fetchall()
        columns = [column[0] for column in cursor.description]
        users = [dict(zip(columns, row)) for row in rows]
        return json.dumps(users, indent=4)

    def close(self):
        self.conn.close()