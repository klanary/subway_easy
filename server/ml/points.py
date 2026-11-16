"""하루치 출발 시각과 시간대별 수집 횟수를 담는 작은 파생 DB(`ml_data/points.sqlite`).

수집 DB(하루 약 130MB)를 여러 날 한꺼번에 읽으면 VM 메모리(836MB)가 모자란다.
그래서 밤마다 전날 하루치만 호선 하나씩 읽어 "역마다 출발 시각 하나"로 줄여 여기 넣고,
변경 판단과 B1은 이 파일만 읽는다.

출발 시각은 수집 DB에서 다시 만들 수 있지만, 수집 횟수(coverage)는 raw 응답이 지워지는
14일 뒤에는 다시 만들 수 없다. 이 파일을 지우면 그보다 오래된 날의 수집 횟수는 "모름"이 된다.
"""

import sqlite3
from collections import Counter
from collections.abc import Sequence
from datetime import date, datetime, time, timedelta
from pathlib import Path

from app.core.clock import KST
from ml.b1 import Departure, departure_points
from ml.dataset import _open_ro, build_runs, load_events
from ml.parse import CODE_OK, SERVICE_DAY_START_HOUR
from ml.scopes import band_of

_SCHEMA = """
CREATE TABLE IF NOT EXISTS departures(
  service_date TEXT NOT NULL, day_type INTEGER NOT NULL, line TEXT NOT NULL,
  direction INTEGER NOT NULL, statn_id TEXT NOT NULL, train_no TEXT NOT NULL,
  secs INTEGER NOT NULL, estimated INTEGER NOT NULL, dest_name TEXT, express INTEGER NOT NULL,
  PRIMARY KEY(line, day_type, service_date, direction, statn_id, train_no)
);
CREATE INDEX IF NOT EXISTS departures_day ON departures(service_date);
CREATE TABLE IF NOT EXISTS coverage(
  service_date TEXT NOT NULL, line TEXT NOT NULL, band INTEGER NOT NULL, n_ok INTEGER NOT NULL,
  PRIMARY KEY(service_date, line, band)
);
"""


def _service_day_bounds(day: date) -> tuple[datetime, datetime, datetime]:
    """(그 날 0시, 운행일 시작 01:00, 다음 운행일 시작)."""
    midnight = datetime.combine(day, time(0, 0), tzinfo=KST)
    start = midnight + timedelta(hours=SERVICE_DAY_START_HOUR)
    return midnight, start, start + timedelta(days=1)


class PointStore:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._con = sqlite3.connect(self.path, timeout=30)
        self._con.executescript(_SCHEMA)
        self._con.commit()

    def extract_day(
        self, db_path: str | Path, day: date, *, lines: Sequence[str] | None = None
    ) -> int:
        """수집 DB에서 그 운행일의 출발 시각과 수집 횟수를 뽑아 넣는다. 넣은 출발 수.

        호선 하나씩 읽는다. 같은 날을 다시 돌리면 그 (날짜, 호선)을 지우고 다시 넣는다.
        """
        if lines is None:
            con = _open_ro(db_path)
            try:
                rows = con.execute(
                    "SELECT DISTINCT line FROM events WHERE service_date = ? ORDER BY line",
                    (day.isoformat(),),
                )
                lines = [line for (line,) in rows]
            finally:
                con.close()
        total = 0
        for line in lines:
            events = load_events(db_path, date_from=day, date_to=day, line=line)
            points = departure_points(build_runs(events))
            self._con.execute(
                "DELETE FROM departures WHERE service_date = ? AND line = ?",
                (day.isoformat(), line),
            )
            self._con.executemany(
                "INSERT INTO departures VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    (
                        p.service_date.isoformat(),
                        p.day_type,
                        p.line,
                        p.direction,
                        p.statn_id,
                        p.train_no,
                        p.secs,
                        int(p.estimated),
                        p.dest_name,
                        p.express,
                    )  # fmt: skip
                    for p in points
                ],
            )
            counts = self._count_ok(db_path, line, day)
            if counts is not None:
                self.set_coverage(line, day, counts)
            self._con.commit()
            total += len(points)
        return total

    @staticmethod
    def _count_ok(db_path: str | Path, line: str, day: date) -> dict[int, int] | None:
        """그 운행일의 시간대별 정상 응답 수. 그 호선의 raw가 한 줄도 없으면 None(모름)."""
        midnight, start, end = _service_day_bounds(day)
        con = _open_ro(db_path)
        try:
            rows = con.execute(
                "SELECT received_at, code FROM raw_snapshots"
                " WHERE line = ? AND received_at >= ? AND received_at < ?",
                (line, start.isoformat(timespec="seconds"), end.isoformat(timespec="seconds")),
            ).fetchall()
        finally:
            con.close()
        if not rows:
            return None
        counts: Counter[int] = Counter()
        for received_at, code in rows:
            band = band_of((datetime.fromisoformat(received_at) - midnight).total_seconds())
            counts[band] += int(code == CODE_OK)
        return dict(counts)

    def set_coverage(self, line: str, day: date, counts: dict[int, int]) -> None:
        self._con.execute(
            "DELETE FROM coverage WHERE service_date = ? AND line = ?", (day.isoformat(), line)
        )
        self._con.executemany(
            "INSERT INTO coverage VALUES (?, ?, ?, ?)",
            [(day.isoformat(), line, band, n) for band, n in counts.items()],
        )
        self._con.commit()

    def coverage(self, line: str, day: date) -> dict[int, int] | None:
        """시간대 → 정상 응답 수. 그 날 그 호선의 기록이 없으면 None(모름)."""
        rows = self._con.execute(
            "SELECT band, n_ok FROM coverage WHERE service_date = ? AND line = ?",
            (day.isoformat(), line),
        ).fetchall()
        if not rows:
            return None
        return {band: n for band, n in rows if n > 0}

    def points(self, line: str, day_type: int, dates: Sequence[date]) -> list[Departure]:
        out: list[Departure] = []
        for day in dates:
            rows = self._con.execute(
                "SELECT service_date, day_type, line, direction, statn_id, train_no, secs,"
                " estimated, dest_name, express FROM departures"
                " WHERE line = ? AND day_type = ? AND service_date = ?",
                (line, day_type, day.isoformat()),
            )
            out.extend(
                Departure(date.fromisoformat(d), dt, ln, dr, st, tn, secs, bool(est), dest, exp)
                for d, dt, ln, dr, st, tn, secs, est, dest, exp in rows
            )
        return out

    def dates(self, line: str, day_type: int, *, upto: date, n: int) -> list[date]:
        """출발이 있는 날짜 가운데 `upto` 이하 최근 n개(오름차순)."""
        rows = self._con.execute(
            "SELECT DISTINCT service_date FROM departures"
            " WHERE line = ? AND day_type = ? AND service_date <= ?"
            " ORDER BY service_date DESC LIMIT ?",
            (line, day_type, upto.isoformat(), n),
        ).fetchall()
        return sorted(date.fromisoformat(d) for (d,) in rows)

    def lines(self, day: date) -> list[tuple[str, int]]:
        """그 날 출발이 있는 (호선, 요일 구분)."""
        return self._con.execute(
            "SELECT DISTINCT line, day_type FROM departures WHERE service_date = ? ORDER BY 1, 2",
            (day.isoformat(),),
        ).fetchall()

    def has_day(self, day: date) -> bool:
        row = self._con.execute(
            "SELECT 1 FROM departures WHERE service_date = ? LIMIT 1", (day.isoformat(),)
        ).fetchone()
        return row is not None

    def all_lines(self) -> list[str]:
        return [line for (line,) in self._con.execute("SELECT DISTINCT line FROM departures")]

    def prune(self, before: date) -> int:
        """`before`보다 앞선 날을 지운다. 지운 출발 수."""
        cur = self._con.execute(
            "DELETE FROM departures WHERE service_date < ?", (before.isoformat(),)
        )
        self._con.execute("DELETE FROM coverage WHERE service_date < ?", (before.isoformat(),))
        self._con.commit()
        return cur.rowcount

    def close(self) -> None:
        if self._con is not None:
            self._con.commit()
            self._con.close()
            self._con = None
