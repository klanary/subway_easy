"""수집 DB(SQLite 한 파일, 설계 §2.4). 표 4개와 그 쓰기 규칙만 안다.

키 값과 URL은 어떤 표에도 넣지 않는다. 키는 이름(position·spare1·spare2)으로만 남긴다.
"""

import sqlite3
import zlib
from collections.abc import Iterable, Mapping
from datetime import date, datetime
from pathlib import Path

from app.core.clock import KST
from ml.parse import Event

_SCHEMA = """
CREATE TABLE IF NOT EXISTS raw_snapshots(
  id INTEGER PRIMARY KEY,
  line TEXT NOT NULL,
  received_at TEXT NOT NULL,
  code TEXT,
  body BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS raw_received ON raw_snapshots(received_at);
CREATE TABLE IF NOT EXISTS events(
  service_date TEXT NOT NULL,
  day_type INTEGER NOT NULL,
  line TEXT NOT NULL,
  direction INTEGER NOT NULL,
  train_no TEXT NOT NULL,
  station_id TEXT,
  statn_id TEXT NOT NULL,
  status INTEGER NOT NULL,
  event_at TEXT NOT NULL,
  first_seen_at TEXT NOT NULL,
  dest_statn_id TEXT,
  dest_name TEXT,
  express INTEGER NOT NULL,
  last_train INTEGER NOT NULL,
  flag TEXT,
  PRIMARY KEY(service_date, line, train_no, statn_id, status)
);
CREATE TABLE IF NOT EXISTS station_map(
  line TEXT NOT NULL,
  statn_id TEXT NOT NULL,
  statn_nm TEXT NOT NULL,
  station_id TEXT,
  first_seen TEXT NOT NULL,
  PRIMARY KEY(line, statn_id)
);
CREATE TABLE IF NOT EXISTS key_usage(
  day TEXT NOT NULL,
  key_name TEXT NOT NULL,
  calls INTEGER NOT NULL DEFAULT 0,
  errors INTEGER NOT NULL DEFAULT 0,
  quota_hit_at TEXT,
  PRIMARY KEY(day, key_name)
);
"""

# 같은 (운행일, 호선, 열차, 역, 상태)가 다시 오면 가장 이른 recptnDt를 남긴다.
# first_seen_at은 처음 본 시각이므로 바꾸지 않는다.
_UPSERT_EVENT = """
INSERT INTO events(service_date, day_type, line, direction, train_no, station_id, statn_id,
  status, event_at, first_seen_at, dest_statn_id, dest_name, express, last_train, flag)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(service_date, line, train_no, statn_id, status) DO UPDATE SET
  event_at = excluded.event_at, flag = excluded.flag
WHERE excluded.event_at < events.event_at
"""


def iso(at: datetime) -> str:
    """KST ISO 8601. 모든 시각을 같은 꼴로 써서 문자열 비교가 시간 비교와 같게 한다."""
    return at.astimezone(KST).isoformat(timespec="seconds")


class Store:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._con = sqlite3.connect(self.path, timeout=30)
        self._con.execute("PRAGMA journal_mode=WAL")  # 백업·분석이 읽는 동안에도 쓴다
        self._con.execute("PRAGMA synchronous=NORMAL")
        self._con.executescript(_SCHEMA)
        self._con.commit()

    def add_snapshot(self, line: str, received_at: datetime, code: str | None, body: str) -> None:
        self._con.execute(
            "INSERT INTO raw_snapshots(line, received_at, code, body) VALUES (?, ?, ?, ?)",
            (line, iso(received_at), code, zlib.compress(body.encode("utf-8"), 6)),
        )

    def upsert_events(
        self,
        events: Iterable[Event],
        station_ids: Mapping[str, str | None],
        first_seen_at: datetime,
    ) -> int:
        """새로 들어간 이벤트 수(이미 있던 키는 세지 않는다)."""
        seen = iso(first_seen_at)
        last_rowid = self._con.execute("SELECT COALESCE(MAX(rowid), 0) FROM events").fetchone()[0]
        self._con.executemany(
            _UPSERT_EVENT,
            [
                (
                    e.service_date.isoformat(),
                    e.day_type,
                    e.line,
                    e.direction,
                    e.train_no,
                    station_ids.get(e.statn_id),
                    e.statn_id,
                    e.status,
                    iso(e.event_at),
                    seen,
                    e.dest_statn_id,
                    e.dest_name,
                    e.express,
                    e.last_train,
                    e.flag,
                )  # fmt: skip
                for e in events
            ],
        )
        # 새 행만 rowid가 커진다(갱신은 그대로). 표 전체를 세지 않으려고 이렇게 센다.
        return self._con.execute(
            "SELECT COUNT(*) FROM events WHERE rowid > ?", (last_rowid,)
        ).fetchone()[0]

    def map_station(
        self, line: str, statn_id: str, statn_nm: str, station_id: str | None, first_seen: date
    ) -> bool:
        """처음 본 (호선, statnId)만 넣는다. 넣었으면 True."""
        cur = self._con.execute(
            "INSERT OR IGNORE INTO station_map(line, statn_id, statn_nm, station_id, first_seen)"
            " VALUES (?, ?, ?, ?, ?)",
            (line, statn_id, statn_nm, station_id, first_seen.isoformat()),
        )
        return cur.rowcount == 1

    def set_station(self, line: str, statn_id: str, station_id: str) -> None:
        """나중에 이은 역을 station_map과 이미 쌓인 이벤트에 반영한다."""
        self._con.execute(
            "UPDATE station_map SET station_id = ? WHERE line = ? AND statn_id = ?",
            (station_id, line, statn_id),
        )
        self._con.execute(
            "UPDATE events SET station_id = ? WHERE line = ? AND statn_id = ?",
            (station_id, line, statn_id),
        )

    def known_stations(self, line: str) -> dict[str, str | None]:
        return dict(
            self._con.execute(
                "SELECT statn_id, station_id FROM station_map WHERE line = ?", (line,)
            )
        )

    def unmapped_stations(self) -> list[tuple[str, str, str]]:
        return self._con.execute(
            "SELECT line, statn_id, statn_nm FROM station_map WHERE station_id IS NULL"
            " ORDER BY line, statn_id"
        ).fetchall()

    def record_call(self, day: date, key_name: str, *, error: bool) -> None:
        self._con.execute(
            "INSERT INTO key_usage(day, key_name, calls, errors) VALUES (?, ?, 1, ?)"
            " ON CONFLICT(day, key_name) DO UPDATE SET calls = calls + 1,"
            " errors = errors + excluded.errors",
            (day.isoformat(), key_name, int(error)),
        )

    def mark_quota_hit(self, day: date, key_name: str, at: datetime) -> None:
        self._con.execute(
            "INSERT INTO key_usage(day, key_name, quota_hit_at) VALUES (?, ?, ?)"
            " ON CONFLICT(day, key_name) DO UPDATE SET"
            " quota_hit_at = COALESCE(key_usage.quota_hit_at, excluded.quota_hit_at)",
            (day.isoformat(), key_name, iso(at)),
        )

    def blocked_keys(self, day: date) -> list[str]:
        rows = self._con.execute(
            "SELECT key_name FROM key_usage WHERE day = ? AND quota_hit_at IS NOT NULL"
            " ORDER BY key_name",
            (day.isoformat(),),
        )
        return [name for (name,) in rows]

    def commit(self) -> None:
        self._con.commit()

    def close(self) -> None:
        """여러 번 불러도 된다."""
        if self._con is not None:
            self._con.commit()
            self._con.close()
            self._con = None
