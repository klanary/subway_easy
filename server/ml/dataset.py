"""수집 DB의 events를 분석이 쓰는 모양으로 읽는다(읽기 전용).

- Obs: 이벤트 한 줄. 시각은 "서비스 날짜 0시부터 지난 초"(자정을 넘기면 86400 이상).
- Visit: 한 열차가 한 역에서 남긴 상태들(전역출발 3, 진입 0, 도착 1, 출발 2).
- Run: 하루의 한 열차(운행일, 호선, 방향, 열차 번호)가 지난 역들.
"""

import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time
from pathlib import Path

from app.core.clock import KST

ENTER, ARRIVE, DEPART, APPROACH = 0, 1, 2, 3  # trainSttus: 진입, 도착, 출발, 전역출발


@dataclass(frozen=True)
class Obs:
    service_date: date
    day_type: int
    line: str
    direction: int
    train_no: str
    statn_id: str
    station_id: str | None
    status: int
    secs: int
    dest_statn_id: str | None
    dest_name: str | None
    express: int
    last_train: int
    flag: str | None


@dataclass(frozen=True)
class Visit:
    statn_id: str
    approach: int | None = None  # 전역출발(3): 앞 역을 떠나 이 역으로 오는 중
    enter: int | None = None  # 진입(0)
    arr: int | None = None  # 도착(1)
    dep: int | None = None  # 출발(2)

    @property
    def first(self) -> int:
        """이 역에서 가장 먼저 찍힌 시각."""
        return min(t for t in (self.approach, self.enter, self.arr, self.dep) if t is not None)


@dataclass(frozen=True)
class Run:
    service_date: date
    day_type: int
    line: str
    direction: int
    train_no: str
    express: int
    dest_statn_id: str | None
    dest_name: str | None
    visits: tuple[Visit, ...]


_FIELD = {APPROACH: "approach", ENTER: "enter", ARRIVE: "arr", DEPART: "dep"}


def _open_ro(db_path: str | Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{Path(db_path).as_posix()}?mode=ro", uri=True)


def load_events(
    db_path: str | Path,
    *,
    include_flagged: bool = False,
    date_from: date | None = None,
    date_to: date | None = None,
    line: str | None = None,
) -> list[Obs]:
    """이상값 표시(flag)가 달린 이벤트는 기본으로 뺀다.

    날짜(양 끝 포함)·호선 조건은 SQL에서 건다(기본 키 앞머리 (service_date, line)를 탄다).
    """
    conditions: list[str] = []
    params: list[str] = []
    if not include_flagged:
        conditions.append("flag IS NULL")
    if date_from is not None:
        conditions.append("service_date >= ?")
        params.append(date_from.isoformat())
    if date_to is not None:
        conditions.append("service_date <= ?")
        params.append(date_to.isoformat())
    if line is not None:
        conditions.append("line = ?")
        params.append(line)
    where = f"WHERE {' AND '.join(conditions)}" if conditions else ""
    con = _open_ro(db_path)
    try:
        rows = con.execute(
            "SELECT service_date, day_type, line, direction, train_no, statn_id, station_id,"
            " status, event_at, dest_statn_id, dest_name, express, last_train, flag"
            f" FROM events {where}",
            params,
        ).fetchall()
    finally:
        con.close()
    out: list[Obs] = []
    for (
        day_text,
        day_type,
        line,
        direction,
        train_no,
        statn_id,
        station_id,
        status,
        at,
        *rest,
    ) in rows:
        day = date.fromisoformat(day_text)
        start = datetime.combine(day, time(0, 0), tzinfo=KST)
        secs = round((datetime.fromisoformat(at) - start).total_seconds())
        out.append(
            Obs(day, day_type, line, direction, train_no, statn_id, station_id, status, secs, *rest)
        )
    return out


def service_dates(db_path: str | Path, *, date_from: date, date_to: date) -> list[date]:
    """그 사이(양 끝 포함)에 이벤트가 있는 서비스 날짜, 오름차순."""
    con = _open_ro(db_path)
    try:
        rows = con.execute(
            "SELECT DISTINCT service_date FROM events"
            " WHERE service_date >= ? AND service_date <= ? ORDER BY 1",
            (date_from.isoformat(), date_to.isoformat()),
        ).fetchall()
    finally:
        con.close()
    return [date.fromisoformat(d) for (d,) in rows]


def build_runs(events: list[Obs]) -> list[Run]:
    """이벤트를 열차 운행으로 묶는다. 역 방문은 그 역에서 처음 찍힌 시각 순서다."""
    grouped: dict[tuple, list[Obs]] = defaultdict(list)
    for obs in events:
        grouped[(obs.service_date, obs.line, obs.direction, obs.train_no)].append(obs)
    runs: list[Run] = []
    for (day, line, direction, train_no), group in sorted(grouped.items()):
        times: dict[str, dict[str, int]] = defaultdict(dict)
        for obs in group:
            field = _FIELD.get(obs.status)
            if field is not None:
                times[obs.statn_id][field] = obs.secs
        visits = sorted((Visit(statn_id, **t) for statn_id, t in times.items()), key=_visit_key)
        if not visits:
            continue
        last = max(group, key=lambda o: o.secs)
        runs.append(
            Run(
                service_date=day,
                day_type=group[0].day_type,
                line=line,
                direction=direction,
                train_no=train_no,
                express=max(o.express for o in group),
                dest_statn_id=last.dest_statn_id,
                dest_name=last.dest_name,
                visits=tuple(visits),
            )
        )
    return runs


def _visit_key(visit: Visit) -> tuple[int, str]:
    return visit.first, visit.statn_id


def station_names(db_path: str | Path) -> dict[tuple[str, str], str]:
    """(호선, statnId) → 실시간 역 이름."""
    con = _open_ro(db_path)
    try:
        rows = con.execute("SELECT line, statn_id, statn_nm FROM station_map").fetchall()
    finally:
        con.close()
    return {(line, statn_id): name for line, statn_id, name in rows}


def station_ids(db_path: str | Path) -> dict[tuple[str, str], str | None]:
    """(호선, statnId) → 우리 역 id(잇지 못했으면 None)."""
    con = _open_ro(db_path)
    try:
        rows = con.execute("SELECT line, statn_id, station_id FROM station_map").fetchall()
    finally:
        con.close()
    return {(line, statn_id): station_id for line, statn_id, station_id in rows}
