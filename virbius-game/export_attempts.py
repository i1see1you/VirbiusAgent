"""Dump stored attacks to data/attempts.jsonl for later VirbiusGuard training."""

from pathlib import Path
import db

if __name__ == "__main__":
    db.init()
    path = db.export_attempts_jsonl()
    print(path)
    print("lines", sum(1 for _ in Path(path).open(encoding="utf-8") if _.strip()))
