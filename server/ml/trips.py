"""서버용 열차 운행표 만들기(설계 2026-11-18 §5.1).

- 공식(1~9호선): 시간표 캐시 행을 (요일, 호선, 방향, TRAIN_NO)로 묶어 운행 하나로.
  급행은 서는 역에만 행이 있으므로 자연히 서는 역만 남는다.
  같은 번호가 3시간 넘게 끊기면 운행을 나눈다.
- 실측(그 밖 노선): B1 칸을 열차 번호로 묶는다(도착 = 출발 − 그 역 평소 정차). 번호로 못 묶는 노선
  (`slots`)은 첫 역 출발마다 B2 구간 중앙값을 이어 어림 운행을 만든다.
- 결과는 같은 정차 순서를 가진 운행끼리 묶은 패턴 목록이다. 한 패턴 안의 운행은 첫 출발 순이고
  서로 앞지르지 않는다(RAPTOR가 기대하는 성질).

시각은 운행일 0시부터 초(24시를 넘으면 86400 이상).
"""

import json
import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from app.services.geo import haversine_m
from app.services.measured import direction_code, direction_name
from app.services.timetable import OFFICIAL_LINES

KIND, VERSION = "trips-server", 1
TRIP_GAP_SEC = 1_800  # 정차 사이가 이만큼 비면 같은 번호라도 다른 운행(역 사이는 길어야 몇 분)
DEFAULT_DWELL_SEC = 30
AVG_SPEED_MPS = 12.5  # 종착역 어림용 평균 속도(시속 45km, 정차 포함)
MIN_HOP_SEC = 60


@dataclass
class _Stop:
    station: str
    arr: int
    dep: int


@dataclass
class _Trip:
    line: str
    direction: str
    day_type: int
    train_no: str | None
    express: int
    dest: str | None
    source: int  # 0 공식, 1 실측
    estimated: int
    stops: list[_Stop]
    last_statn: str | None = None  # 실측 운행의 마지막 관측 역(statn_id). 종착역까지 잇는 데 쓴다


def _secs(text: str | None) -> int | None:
    if not text or text == "00:00:00":
        return None
    try:
        h, m, s = (int(x) for x in text.split(":"))
    except ValueError:
        return None
    return h * 3600 + m * 60 + s


def _code_map(stations_db: Path) -> dict[str, tuple[str, str]]:
    con = sqlite3.connect(f"file:{Path(stations_db).as_posix()}?mode=ro", uri=True)
    try:
        rows = con.execute("SELECT station_cd, station_id, line_name FROM station_codes").fetchall()
    finally:
        con.close()
    return {code: (station_id, line) for code, station_id, line in rows}


def _coords(stations_db: Path) -> dict[str, tuple[float, float]]:
    con = sqlite3.connect(f"file:{Path(stations_db).as_posix()}?mode=ro", uri=True)
    try:
        return {
            sid: (lat, lng) for sid, lat, lng in con.execute("SELECT id, lat, lng FROM stations")
        }
    finally:
        con.close()


def _official(cache_dir: Path, stations_db: Path) -> list[_Trip]:
    codes = _code_map(stations_db)
    groups: dict[tuple, list[tuple[int, int, str, int, str | None]]] = defaultdict(list)
    for path in sorted(Path(cache_dir).glob("*.json")):
        try:
            code, week, inout = path.stem.split("_")
        except ValueError:
            continue
        mapped = codes.get(code)
        if mapped is None:
            continue
        station_id, line = mapped
        if line not in OFFICIAL_LINES:
            continue
        direction = direction_name(line, 0 if inout == "1" else 1)
        for r in json.loads(path.read_text(encoding="utf-8")):
            arr, dep = _secs(r.get("ARRIVETIME")), _secs(r.get("LEFTTIME"))
            if arr is None and dep is None:
                continue
            arr = dep if arr is None else arr
            dep = arr if dep is None else dep
            express = 1 if r.get("EXPRESS_YN") == "D" else 0
            key = (int(week), line, direction, str(r.get("TRAIN_NO") or ""))
            groups[key].append((arr, dep, station_id, express, r.get("SUBWAYENAME") or None))
    trips: list[_Trip] = []
    for (day_type, line, direction, train_no), stops in groups.items():
        stops.sort()
        run: list[tuple] = []
        for stop in stops:
            if run and stop[0] - run[-1][1] > TRIP_GAP_SEC:
                trips.append(_official_trip(day_type, line, direction, train_no, run))
                run = []
            run.append(stop)
        if run:
            trips.append(_official_trip(day_type, line, direction, train_no, run))
    return [t for t in trips if len(t.stops) >= 2]


def _official_trip(day_type, line, direction, train_no, run) -> _Trip:
    return _Trip(
        line=line,
        direction=direction,
        day_type=day_type,
        train_no=train_no or None,
        express=max(s[3] for s in run),
        dest=run[-1][4] or run[0][4],
        source=0,
        estimated=0,
        stops=[_Stop(s[2], s[0], s[1]) for s in run],
    )


def _measured(
    b1: dict, b2: dict | None, coords: dict[str, tuple[float, float]] | None = None
) -> list[_Trip]:
    coords = coords or {}
    stations = b1.get("stations") or {}
    modes = b1.get("modes") or {}
    dwell = {k: v["median"] for k, v in ((b2 or {}).get("dwell") or {}).items()}
    hops = (b2 or {}).get("hops") or {}

    def our_id(line: str, statn: str) -> str | None:
        return (stations.get(f"{line}|{statn}") or {}).get("station_id")

    def dwell_of(line: str, statn: str) -> int:
        return int(round(dwell.get(f"{line}|{statn}", DEFAULT_DWELL_SEC)))

    numbered: dict[tuple, list[tuple]] = defaultdict(list)
    slots: dict[tuple, list[tuple]] = defaultdict(list)
    for key, entries in (b1.get("cells") or {}).items():
        day_type, line, code, statn = key.split("|")
        if line in OFFICIAL_LINES or our_id(line, statn) is None:
            continue
        direction = direction_name(line, int(code))
        for e in entries:
            item = (int(e["secs"]), statn, e.get("dest_name"), 1 if e.get("express") else 0)
            if modes.get(line) == "train_no" and e.get("train_no"):
                numbered[(int(day_type), line, direction, e["train_no"])].append(item)
            else:
                slots[(int(day_type), line, direction, int(code), statn)].append(item)

    # B2 구간: (호선, 방향 코드, 앞 역) → (다음 역, 중앙값). 같은 앞 역에 여럿이면 표본이 많은 것.
    next_of: dict[tuple[str, int, str], tuple[str, float]] = {}
    has_incoming: set[tuple[str, int, str]] = set()
    best_n: dict[tuple[str, int, str], int] = {}
    for key, stats in hops.items():
        line, code, a, b, express = key.split("|")
        if express != "0":
            continue
        k = (line, int(code), a)
        if stats.get("n", 0) >= best_n.get(k, -1):
            next_of[k] = (b, float(stats["median"]))
            best_n[k] = stats.get("n", 0)
        has_incoming.add((line, int(code), b))
    reverse = {
        (key.split("|")[0], int(key.split("|")[1]), key.split("|")[2], key.split("|")[3]): float(
            stats["median"]
        )
        for key, stats in hops.items()
        if key.endswith("|0")
    }
    statn_by_name = {
        (key.split("|")[0], value.get("name")): key.split("|")[1] for key, value in stations.items()
    }

    def to_terminal(trip: _Trip, last_statn: str) -> None:
        """B1에는 종착역 출발이 없다(그 역은 반대 방향에만 나온다). 행선지까지 B2 구간으로 잇는다.

        행선지까지 구간이 이어지지 않으면 그대로 둔다(최종 리뷰 #1).
        """
        target = statn_by_name.get((trip.line, trip.dest))
        if target is None or target == last_statn:
            return
        code = direction_code(trip.direction)
        current, arr, added, seen = last_statn, trip.stops[-1].arr, [], {last_statn}
        while current != target:
            step = next_of.get((trip.line, code, current))
            if step is None:
                # 종착역으로 들어오는 구간은 관측이 거의 없다(종착 도착이 잘 안 찍힘).
                # 바로 다음이 종착역이면 반대 방향의 같은 구간 시간을 쓴다.
                back = reverse.get((trip.line, 1 - code, target, current))
                if back is None:
                    # 양방향 모두 관측이 없으면(공항철도 종착역) 직선거리 ÷ 평균 속도로 어림한다.
                    a, b = (
                        coords.get(our_id(trip.line, current)),
                        coords.get(our_id(trip.line, target)),
                    )
                    if a is None or b is None:
                        return
                    back = max(MIN_HOP_SEC, haversine_m(a[0], a[1], b[0], b[1]) / AVG_SPEED_MPS)
                step = (target, back)
            current, took = step
            station = our_id(trip.line, current)
            if current in seen or station is None:
                return
            seen.add(current)
            arr += int(round(took))
            added.append(_Stop(station, arr, arr + dwell_of(trip.line, current)))
        added[-1].dep = added[-1].arr  # 종착역은 떠나지 않는다
        trip.stops.extend(added)

    trips: list[_Trip] = []
    for (day_type, line, direction, train_no), items in numbered.items():
        items.sort()
        run: list[tuple] = []
        for item in items:
            if run and item[0] - run[-1][0] > TRIP_GAP_SEC:
                trips.append(
                    _measured_trip(day_type, line, direction, train_no, run, our_id, dwell_of)
                )
                run = []
            run.append(item)
        if run:
            trips.append(_measured_trip(day_type, line, direction, train_no, run, our_id, dwell_of))
    for trip in trips:
        to_terminal(trip, trip.last_statn)

    # 번호로 못 묶는 노선: 들어오는 구간이 없는 역(첫 역)의 출발마다 구간을 이어 간다.
    for (day_type, line, direction, code, statn), items in slots.items():
        if (line, code, statn) in has_incoming:
            continue  # 첫 역의 출발만 운행의 시작으로 쓴다
        for secs, _, dest, express in items:
            if express:
                continue  # 급행은 서는 역을 모르므로 일반 구간으로 잇지 않는다
            end = statn_by_name.get((line, dest))
            stops: list[_Stop] = []
            arr = secs - dwell_of(line, statn)
            current, seen = statn, set()
            while current is not None and current not in seen and our_id(line, current):
                seen.add(current)
                step = next_of.get((line, code, current)) if current != end else None
                dep = arr + dwell_of(line, current) if step is not None else arr
                if not stops:
                    dep = secs
                stops.append(_Stop(our_id(line, current), arr, dep))
                if step is None:
                    break
                previous = current
                current, took = step
                back = reverse.get((line, 1 - code, current, previous)) if len(stops) == 1 else None
                if back is not None:
                    # 첫 역 도착→다음 역 도착에는 첫 역에서 오래 서 있던 시간이 섞인다.
                    # 반대 방향(다음 역 정차 포함)으로 달리는 시간을 구해 첫 역 출발에 더한다.
                    arr = secs + int(round(back)) - dwell_of(line, current)
                else:
                    arr = stops[-1].arr + int(round(took))
            if len(stops) >= 2:
                trips.append(_Trip(line, direction, day_type, None, express, dest, 1, 1, stops))
    return [t for t in trips if len(t.stops) >= 2]


def _measured_trip(day_type, line, direction, train_no, run, our_id, dwell_of) -> _Trip:
    stops = [
        _Stop(our_id(line, statn), secs - dwell_of(line, statn), secs) for secs, statn, _, _ in run
    ]
    return _Trip(
        line=line,
        direction=direction,
        day_type=day_type,
        train_no=train_no,
        express=max(item[3] for item in run),
        dest=run[-1][2] or run[0][2],
        source=1,
        estimated=0,
        stops=stops,
        last_statn=run[-1][1],
    )


def _overtakes(earlier: _Trip, later: _Trip) -> bool:
    return any(
        b.arr < a.arr or b.dep < a.dep for a, b in zip(earlier.stops, later.stops, strict=True)
    )


def build_trips(
    cache_dir: Path | None,
    stations_db: Path,
    b1: dict | None,
    b2: dict | None,
    *,
    built_on: date,
) -> dict:
    """서버용 운행표(`trips-server`). 형식은 구현 계획 2026-11-18 Task 1."""
    trips = _official(cache_dir, stations_db) if cache_dir is not None else []
    if b1 is not None:
        trips += _measured(b1, b2, _coords(stations_db))

    station_index: dict[str, int] = {}

    def idx(station: str) -> int:
        if station not in station_index:
            station_index[station] = len(station_index)
        return station_index[station]

    grouped: dict[tuple, list[_Trip]] = defaultdict(list)
    for trip in trips:
        key = (trip.day_type, trip.line, trip.direction, tuple(s.station for s in trip.stops))
        grouped[key].append(trip)

    day_types: dict[str, list[dict]] = {"1": [], "2": [], "3": []}
    for (day_type, line, direction, stops), members in sorted(
        grouped.items(), key=lambda kv: kv[0][:3]
    ):
        members.sort(key=lambda t: t.stops[0].dep)
        lanes: list[list[_Trip]] = []  # 앞지르지 않는 운행끼리
        for trip in members:
            for lane in lanes:
                if not _overtakes(lane[-1], trip):
                    lane.append(trip)
                    break
            else:
                lanes.append([trip])
        for lane in lanes:
            day_types.setdefault(str(day_type), []).append(
                {
                    "line": line,
                    "direction": direction,
                    "stops": [idx(s) for s in stops],
                    "trips": [
                        [
                            t.train_no,
                            t.express,
                            t.dest,
                            t.source,
                            t.estimated,
                            [x for s in t.stops for x in (s.arr, s.dep)],
                        ]
                        for t in lane
                    ],
                }
            )
    return {
        "kind": KIND,
        "version": VERSION,
        "built_on": built_on.isoformat(),
        "stations": list(station_index),
        "day_types": day_types,
    }
