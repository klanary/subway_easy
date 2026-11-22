"""위치 계산 되돌려 보기(설계 2026-11-22 열차 위치 도착 계산 §9).

사용자가 보는 질문 그대로 견준다: "시각 t에 이 역에서 t+h 이후 처음 오는 열차는 언제?"

- 위치 계산: t 직전(600초 안) 각 열차의 마지막 관측 → `estimate_arrival` → t+h 이후 첫 값.
- 문구 어림(h=0만): 같은 열차들의 "이 역까지 남은 역 수 × 120초"(지금 서버의 `[N]번째 전역` 어림).
- 시간표: t+h 이후 첫 시간표 출발(1~9호선 공식, 그 밖은 실측 B1).
- 실제: 그 역·방향의 도착(1) 가운데 t+h 이후 첫 것(이 역이 종착인 열차는 뺀다).

구간 시간(B2)과 실측 시간표(1~9호선 밖)는 앞 날짜로 배우고 뒤 날짜로 평가한다.
1~9호선 시간표는 공식 시간표 캐시다. 수집 DB는 읽기만 한다. 05시~다음 날 01시를 본다.

사용법 (VM server/에서):
    python -m ml.position_eval --db ml_data/collector.sqlite --trips <운행표 결과물> \\
        --timetable-cache ml_data/timetable_cache --out ml_data/position_eval.md
"""

import argparse
import gzip
import json
import sys
from bisect import bisect_left
from collections import defaultdict
from collections.abc import Callable
from datetime import date, datetime, time, timedelta
from pathlib import Path

from app.core.clock import KST
from app.services.measured import B1_KIND, B1_VERSION, MeasuredData, direction_name
from app.services.position_eta import estimate_arrival, path_between
from app.services.position_service import TrainPosition
from app.services.station_index import StationIndex
from app.services.timetable import OFFICIAL_LINES, week_tag
from app.services.trips import TripsData
from ml.b0 import B0, TimetableCache, station_codes
from ml.b1 import build_b1
from ml.b2 import build_b2
from ml.dataset import Run, build_runs, load_events, station_ids
from ml.evaluate import NotEnoughDates, metrics, split_by_date
from ml.publish import server_b2

HORIZONS = (0, 5, 15, 30, 60)  # 분
TEXT_SEC_PER_STOP = 120  # 지금 서버의 문구 어림(remaining_time._SEC_PER_PREVIOUS_STATION)
LOOKBACK_SEC = 600  # 이보다 오래전 관측은 위치로 쓰지 않는다(서버와 같은 값)
ACTUAL_WITHIN_SEC = 1800  # 실제 도착이 이보다 늦으면(운행 끝 등) 그 질문은 세지 않는다
STEP_SEC = 600
STATIONS_PER_LINE = 20
MIN_N = 30  # 켜기 판단에 쓰는 최소 건수

# (호선, 방향 이름, 역 id, 운행일, 기준 초) → 그 뒤 첫 시간표 출발 초(운행일 0시 기준)
TimetableFirst = Callable[[str, str, str, date, int], int | None]
Scores = dict[str, dict[tuple[str, int], dict]]

_STATUS_FIELDS = (("approach", 3), ("enter", 0), ("arr", 1), ("dep", 2))


def measured_from_runs(
    train: list[Run], statn_to_station: dict[tuple[str, str], str]
) -> MeasuredData:
    """앞 날짜 운행으로 배운 구간 시간과 역 연결만 담은 실측 결과물."""
    stations = {
        f"{line}|{statn}": {"station_id": sid, "name": sid}
        for (line, statn), sid in statn_to_station.items()
        if sid
    }
    as_of = max((run.service_date for run in train), default=date.today())
    b1 = {
        "kind": B1_KIND, "version": B1_VERSION, "as_of": as_of.isoformat(), "built_at": "",
        "modes": {}, "dests": [], "cells": {}, "stations": stations, "scopes": {},
    }  # fmt: skip
    return MeasuredData.from_artifacts(b1, server_b2(build_b2(train)))


def _events(run: Run) -> list[tuple[int, str, int]]:
    out = []
    for v in run.visits:
        for field, status in _STATUS_FIELDS:
            secs = getattr(v, field)
            if secs is not None:
                out.append((secs, v.statn_id, status))
    out.sort()
    return out


def _first_at_or_after(values: list[int], target: int) -> int | None:
    i = bisect_left(values, target)
    return values[i] if i < len(values) else None


def _positions_at(
    timelines: list[tuple[Run, list[tuple[int, str, int]]]],
    t: int,
    base: datetime,
    line: str,
    name: str,
    statn_to_station: dict[tuple[str, str], str],
) -> list[TrainPosition]:
    """시각 t에 서버가 위치 API로 보았을 열차들(각 열차의 t 이전 마지막 관측)."""
    active = []
    for run, events in timelines:
        i = bisect_left(events, (t + 1,)) - 1
        if i < 0 or t - events[i][0] > LOOKBACK_SEC:
            continue
        secs, statn, status = events[i]
        sid = statn_to_station.get((line, statn))
        if sid is None or statn == run.dest_statn_id:
            continue
        seen = base + timedelta(seconds=secs)
        active.append(
            TrainPosition(
                line, name, run.train_no, sid, status, seen, run.dest_name, run.express != 0
            )
        )
    return active


def evaluate(
    test: list[Run],
    *,
    measured: MeasuredData,
    trips: TripsData,
    statn_to_station: dict[tuple[str, str], str],
    timetable_first: TimetableFirst,
    hours: tuple[int, int] = (5, 25),  # 자정 넘은 막차까지(운행일 0시부터 시)
    horizons: tuple[int, ...] = HORIZONS,
    stations_per_line: int = STATIONS_PER_LINE,
    step_sec: int = STEP_SEC,
) -> Scores:
    errors: dict[str, dict[tuple[str, int], list[float]]] = defaultdict(lambda: defaultdict(list))
    groups: dict[tuple[str, date, int], list[Run]] = defaultdict(list)
    for run in test:
        groups[(run.line, run.service_date, run.direction)].append(run)
    for (line, day, code), runs in groups.items():
        name = direction_name(line, code)
        base = datetime.combine(day, time(0), tzinfo=KST)
        arrivals: dict[str, list[int]] = defaultdict(list)
        for run in runs:
            for v in run.visits:
                sid = statn_to_station.get((line, v.statn_id))
                if sid and v.arr is not None and v.statn_id != run.dest_statn_id:
                    arrivals[sid].append(v.arr)
        for values in arrivals.values():
            values.sort()
        names = sorted(arrivals)
        stride = max(1, len(names) // stations_per_line)
        sample = names[::stride][:stations_per_line]
        timelines = [(run, _events(run)) for run in runs]
        day_trips = trips.day(runs[0].day_type)
        for t in range(hours[0] * 3600, hours[1] * 3600, step_sec):
            now = base + timedelta(seconds=t)
            active = _positions_at(timelines, t, base, line, name, statn_to_station)
            for target in sample:
                pos_etas, text_etas = [], []
                for p in active:
                    eta = estimate_arrival(p, target, trips=trips, measured=measured, now=now)
                    if eta is not None:
                        pos_etas.append(int((eta - base).total_seconds()))
                    path = path_between(
                        day_trips, line, name, p.station_id, target,
                        dest_name=p.dest_name, express=p.express,
                    )  # fmt: skip
                    if path is not None or p.station_id == target:
                        stops = 0 if path is None else len(path) - 1
                        seen = int((p.seen_at - base).total_seconds())
                        text_etas.append(max(t, seen + stops * TEXT_SEC_PER_STOP))
                pos_etas.sort()
                text_etas.sort()
                for h in horizons:
                    ask = t + h * 60
                    actual = _first_at_or_after(arrivals[target], ask)
                    if actual is None or actual - ask > ACTUAL_WITHIN_SEC:
                        continue
                    predicted = {
                        "position": _first_at_or_after(pos_etas, ask),
                        "text": _first_at_or_after(text_etas, ask) if h == 0 else None,
                        "timetable": timetable_first(line, name, target, day, ask),
                    }
                    # 서버가 보이는 것: 위치 계산, 없으면 시간표. 시간표와 같은 질문에서만 센다
                    # (최종 리뷰 Important 3: 위치가 답한 쉬운 질문만 세면 견줄 수 없다).
                    if h > 0 and predicted["timetable"] is not None:
                        predicted["combined"] = predicted["position"] or predicted["timetable"]
                    for method, value in predicted.items():
                        if value is not None:
                            errors[line][(method, h)].append(value - actual)
    out: Scores = {}
    for line, cells in errors.items():
        out[line] = {key: m for key, values in cells.items() if (m := metrics(values)) is not None}
    return out


def suggest(scores: Scores, *, min_n: int = MIN_N) -> tuple[list[str], list[str]]:
    """설계 §9의 켜기 기준.

    [지금]: 위치 MAE < 문구 MAE이고 ±60초 비율이 같거나 높다.
    [시각 선택]: 건수가 충분한 모든 지평(5·15·30·60분)에서 서버가 보일 것(위치, 없으면 시간표)의
    MAE < 시간표 MAE(같은 질문).
    """
    now_lines, at_lines = [], []
    for line in sorted(scores):
        cells = scores[line]
        pos, text = cells.get(("position", 0)), cells.get(("text", 0))
        if pos and text and min(pos["n"], text["n"]) >= min_n:
            if pos["mae"] < text["mae"] and pos["within60"] >= text["within60"]:
                now_lines.append(line)
        compared = 0
        wins = True
        for h in HORIZONS[1:]:
            p, tt = cells.get(("combined", h)), cells.get(("timetable", h))
            if p and tt and min(p["n"], tt["n"]) >= min_n:
                compared += 1
                wins = wins and p["mae"] < tt["mae"]
        if compared and wins:
            at_lines.append(line)
    return now_lines, at_lines


def render(scores: Scores, *, note: str) -> str:
    lines = ["# 위치 계산 되돌려 보기", "", note, ""]
    header = "| 호선 | 방법 | " + " | ".join(f"{h}분" for h in HORIZONS) + " |"
    lines += [header, "|---|---|" + "---|" * len(HORIZONS)]
    for line in sorted(scores):
        for method in ("text", "position", "combined", "timetable"):
            cells = []
            for h in HORIZONS:
                m = scores[line].get((method, h))
                cells.append(f"{m['mae']:.0f}초 ±60 {m['within60']:.0%} ({m['n']})" if m else "-")
            lines.append(f"| {line} | {method} | " + " | ".join(cells) + " |")
    now_lines, at_lines = suggest(scores)
    lines += [
        "",
        "## 제안 켜기 목록",
        "",
        f"position_now_lines = {json.dumps(now_lines, ensure_ascii=False)}",
        f"position_at_lines = {json.dumps(at_lines, ensure_ascii=False)}",
        "",
    ]
    return "\n".join(lines)


def timetable_from_runs(
    train: list[Run], statn_to_station: dict[tuple[str, str], str]
) -> TimetableFirst:
    """학습 날짜로만 만든 실측 시간표의 "그 뒤 첫 출발"(시험 날짜가 섞이지 않게, 최종 리뷰).

    결과물(measured_timetable.json)은 시험 날짜까지 배운 것이라 시간표 쪽이 유리해진다.
    """
    cells = build_b1(train, min_days=1)["cells"]
    statn_of = {(line, sid): statn for (line, statn), sid in statn_to_station.items() if sid}
    table: dict[str, list[int]] = {
        key: sorted(int(e["secs"]) for e in entries) for key, entries in cells.items()
    }

    def first(line: str, direction: str, station_id: str, day: date, after: int) -> int | None:
        statn = statn_of.get((line, station_id))
        if statn is None:
            return None
        code = 0 if direction in ("상행", "내선") else 1
        return _first_at_or_after(table.get(f"{week_tag(day)}|{line}|{code}|{statn}", []), after)

    return first


def _load_json(path: str | Path) -> dict:
    raw = Path(path).read_bytes()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return json.loads(raw.decode("utf-8"))


def _official_first(db: str, stations_db: str, cache_dir: str) -> TimetableFirst:
    """1~9호선 공식 시간표(VM 캐시)의 "그 뒤 첫 출발"."""
    codes = station_codes(db, StationIndex.load(stations_db))
    b0 = B0(TimetableCache(Path(cache_dir)), codes) if Path(cache_dir).is_dir() else None
    statn_of: dict[str, dict[str, str]] = defaultdict(dict)
    for (line, statn), sid in station_ids(db).items():
        if sid:
            statn_of[line][sid] = statn

    def first(line: str, direction: str, station_id: str, day: date, after: int) -> int | None:
        statn = statn_of[line].get(station_id)
        if b0 is None or statn is None:
            return None
        code = 0 if direction in ("상행", "내선") else 1
        deps = [dep for _, dep in b0.departures(week_tag(day), line, code, statn)]
        return _first_at_or_after(deps, after)

    return first


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="위치 계산 되돌려 보기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--trips", required=True, help="운행표 결과물(trips-server, json 또는 gz)")
    parser.add_argument("--timetable-cache", default="ml_data/timetable_cache")
    parser.add_argument("--stations-db", default="data/stations.sqlite")
    parser.add_argument("--test-days", type=int, default=None)
    parser.add_argument("--out", default="ml_data/position_eval.md")
    args = parser.parse_args(argv)
    trips = TripsData.from_artifact(_load_json(args.trips))
    official = _official_first(args.db, args.stations_db, args.timetable_cache)
    all_ids = station_ids(args.db)
    scores: Scores = {}
    train_dates: set[date] = set()
    test_dates: set[date] = set()
    for line in sorted({line for line, _ in all_ids}):
        runs = build_runs(load_events(args.db, line=line))
        try:
            train, test = split_by_date(runs, test_days=args.test_days)
        except NotEnoughDates:
            continue
        statn = {key: sid for key, sid in all_ids.items() if key[0] == line and sid}
        measured = measured_from_runs(train, statn)
        first = official if line in OFFICIAL_LINES else timetable_from_runs(train, statn)
        scores.update(
            evaluate(
                test, measured=measured, trips=trips, statn_to_station=statn, timetable_first=first
            )  # fmt: skip
        )
        train_dates |= {r.service_date for r in train}
        test_dates |= {r.service_date for r in test}
        print(f"{line}: 시험 운행 {len(test)}", file=sys.stderr)
    span = (
        f"학습 {min(train_dates)}~{max(train_dates)}, 시험 {min(test_dates)}~{max(test_dates)}"
        if train_dates and test_dates
        else "평가할 날짜 없음"
    )
    Path(args.out).write_text(render(scores, note=span), encoding="utf-8")
    now_lines, at_lines = suggest(scores)
    print(f"[지금] {now_lines}\n[시각 선택] {at_lines}\n→ {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
