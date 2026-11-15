"""B1 실측 시간표(설계 §4): 관측한 출발 시각으로 (요일 구분, 호선, 방향, 역)별 시각표를 만든다.

사용법 (server/에서):
    python -m ml.b1 --db ml_data/collector.sqlite --out data/measured_timetable.json

- 열차 번호가 날마다 같은 노선: (요일 구분, 호선, 방향, 역, 열차 번호)별 출발 시각의 중앙값.
- 날마다 다른 노선: 하루 출발 시각을 모아 `slot_width`초 안의 것끼리 묶어 대표 시각을 낸다.
- 관측 일수가 `min_days` 미만인 칸은 내지 않는다.
"""

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path

from app.core.clock import KST
from ml.dataset import Run, build_runs, load_events, station_ids, station_names

VERSION = 1
MIN_DAYS = 3  # 관측 일수 하한
SLOT_WIDTH_SEC = 120  # 열차 번호 없이 묶을 때 한 칸의 폭
FALLBACK_DWELL_SEC = 30  # 출발이 안 찍힌 역: 도착 + 이만큼(호선 정차 중앙값이 없을 때)
STABLE_WITHIN_SEC = 300  # 같은 열차 번호의 출발 시각이 날마다 이 안이면 "같은 열차"
STABLE_RATIO = 0.8  # 이 비율 이상이면 열차 번호로 묶는다
_MIN_DWELL_SAMPLES = 20
_MAX_DWELL_SEC = 600


@dataclass(frozen=True)
class Departure:
    """한 열차가 한 역을 떠난 시각 하나."""

    service_date: date
    day_type: int
    line: str
    direction: int
    statn_id: str
    train_no: str
    secs: int
    estimated: bool  # 출발이 안 찍혀 도착 + 정차 시간으로 어림한 값
    dest_name: str | None
    express: int


@dataclass(frozen=True)
class Entry:
    secs: int
    train_no: str | None
    n_days: int
    dest_name: str | None
    express: int


def line_dwell(runs: list[Run], fallback: int) -> dict[str, int]:
    """호선별 정차 시간 중앙값(초). 표본이 20개 미만인 호선은 `fallback`."""
    samples: dict[str, list[int]] = defaultdict(list)
    for run in runs:
        for v in run.visits:
            if v.arr is not None and v.dep is not None and 0 < v.dep - v.arr <= _MAX_DWELL_SEC:
                samples[run.line].append(v.dep - v.arr)
    return {
        line: round(statistics.median(values)) if len(values) >= _MIN_DWELL_SAMPLES else fallback
        for line, values in samples.items()
    }


def departure_points(
    runs: list[Run],
    *,
    fallback_dwell: int = FALLBACK_DWELL_SEC,
    dwell: dict[str, int] | None = None,
) -> list[Departure]:
    """역마다 출발 시각 하나. 종착역은 뺀다. 출발(2)이 없으면 도착(1) + 정차 시간.

    `dwell`을 주면 그 호선별 정차 시간을 쓴다(평가에서 학습 날짜의 값을 시험 날짜에 쓸 때).
    """
    if dwell is None:
        dwell = line_dwell(runs, fallback_dwell)
    points: list[Departure] = []
    for run in runs:
        for v in run.visits:
            if v.statn_id == run.dest_statn_id:
                continue
            if v.dep is not None:
                secs, estimated = v.dep, False
            elif v.arr is not None:
                secs, estimated = v.arr + dwell.get(run.line, fallback_dwell), True
            else:
                continue
            points.append(
                Departure(
                    run.service_date,
                    run.day_type,
                    run.line,
                    run.direction,
                    v.statn_id,
                    run.train_no,
                    secs,
                    estimated,
                    run.dest_name,
                    run.express,
                )  # fmt: skip
            )
    return points


def train_no_stability(runs: list[Run]) -> dict[str, float]:
    """호선별로, 같은 요일 구분에서 2일 이상 본 (역, 열차 번호)의 출발 시각이 300초 안인 비율.

    2일 이상 본 것이 없는 호선은 결과에 없다(판단할 수 없음).
    """
    by_key: dict[tuple, dict[date, int]] = defaultdict(dict)
    for p in departure_points(runs):
        days = by_key[(p.line, p.day_type, p.direction, p.statn_id, p.train_no)]
        days[p.service_date] = min(p.secs, days.get(p.service_date, p.secs))
    stable: Counter[str] = Counter()
    total: Counter[str] = Counter()
    for (line, *_), days in by_key.items():
        if len(days) < 2:
            continue
        total[line] += 1
        if max(days.values()) - min(days.values()) <= STABLE_WITHIN_SEC:
            stable[line] += 1
    return {line: stable[line] / total[line] for line in total}


def _common(values: list) -> object:
    return Counter(values).most_common(1)[0][0] if values else None


def _by_train_no(points: list[Departure], min_days: int) -> list[Entry]:
    by_train: dict[str, dict[date, Departure]] = defaultdict(dict)
    for p in points:
        seen = by_train[p.train_no].get(p.service_date)
        if seen is None or p.secs < seen.secs:
            by_train[p.train_no][p.service_date] = p
    entries: list[Entry] = []
    for train_no, days in by_train.items():
        if len(days) < min_days:
            continue
        group = list(days.values())
        entries.append(
            Entry(
                secs=round(statistics.median(p.secs for p in group)),
                train_no=train_no,
                n_days=len(days),
                dest_name=_common([p.dest_name for p in group]),
                express=_common([p.express for p in group]),
            )
        )
    return entries


def _by_slots(points: list[Departure], min_days: int, slot_width: int) -> list[Entry]:
    entries: list[Entry] = []
    slot: list[Departure] = []

    def close() -> None:
        if len(slot) >= min_days:
            entries.append(
                Entry(
                    secs=round(statistics.median(p.secs for p in slot)),
                    train_no=None,
                    n_days=len(slot),
                    dest_name=_common([p.dest_name for p in slot]),
                    express=_common([p.express for p in slot]),
                )
            )

    for p in sorted(points, key=lambda p: p.secs):
        # 한 칸에는 하루에 한 대만 들어간다. 같은 날 열차가 또 오면 다음 칸이다.
        too_far = slot and p.secs - slot[0].secs > slot_width
        same_day = any(q.service_date == p.service_date for q in slot)
        if too_far or same_day:
            close()
            slot = []
        slot.append(p)
    close()
    return entries


def build_b1(
    runs: list[Run],
    *,
    min_days: int = MIN_DAYS,
    slot_width: int = SLOT_WIDTH_SEC,
    fallback_dwell: int = FALLBACK_DWELL_SEC,
    mode: str = "auto",
    now: datetime | None = None,
) -> dict:
    """결과물(JSON으로 쓸 dict). mode: auto(호선별로 고름), train_no, slots."""
    dwell = line_dwell(runs, fallback_dwell)
    points = departure_points(runs, fallback_dwell=fallback_dwell, dwell=dwell)
    stability = train_no_stability(runs) if mode == "auto" else {}
    lines = sorted({p.line for p in points})
    if mode == "auto":
        modes = {
            line: "train_no" if stability.get(line, 1.0) >= STABLE_RATIO else "slots"
            for line in lines
        }
    else:
        modes = {line: mode for line in lines}

    by_cell: dict[tuple, list[Departure]] = defaultdict(list)
    for p in points:
        by_cell[(p.day_type, p.line, p.direction, p.statn_id)].append(p)
    cells: dict[str, list[dict]] = {}
    for (day_type, line, direction, statn_id), group in sorted(by_cell.items()):
        if modes[line] == "train_no":
            entries = _by_train_no(group, min_days)
        else:
            entries = _by_slots(group, min_days, slot_width)
        if entries:
            entries.sort(key=lambda e: (e.secs, e.train_no or ""))
            cells[f"{day_type}|{line}|{direction}|{statn_id}"] = [vars(e).copy() for e in entries]

    dates_by_type: dict[int, set[date]] = defaultdict(set)
    for run in runs:
        dates_by_type[run.day_type].add(run.service_date)
    all_dates = sorted({run.service_date for run in runs})
    return {
        "version": VERSION,
        "kind": "b1",
        "built_at": (now or datetime.now(KST)).astimezone(KST).isoformat(timespec="seconds"),
        "date_from": all_dates[0].isoformat() if all_dates else None,
        "date_to": all_dates[-1].isoformat() if all_dates else None,
        "n_dates": {str(k): len(v) for k, v in sorted(dates_by_type.items())},
        "n_departures": len(points),
        "min_days": min_days,
        "slot_width": slot_width,
        "modes": modes,
        "dwell": dict(sorted(dwell.items())),
        "train_no_stability": {k: round(v, 3) for k, v in sorted(stability.items())},
        "cells": cells,
    }


class MeasuredTimetable:
    """결과물을 읽어 조회한다(서버 통합 때 그대로 쓸 모양)."""

    def __init__(self, artifact: dict):
        self._cells: dict[str, list[Entry]] = {
            key: [Entry(**e) for e in entries] for key, entries in artifact["cells"].items()
        }

    def _cell(self, day_type: int, line: str, direction: int, statn_id: str) -> list[Entry]:
        return self._cells.get(f"{day_type}|{line}|{direction}|{statn_id}", [])

    def departures(
        self, day_type: int, line: str, direction: int, statn_id: str, *, after_secs: int,
        count: int,
    ) -> list[Entry]:  # fmt: skip
        cell = self._cell(day_type, line, direction, statn_id)
        return [e for e in cell if e.secs >= after_secs][:count]

    def entry_for(
        self, day_type: int, line: str, direction: int, statn_id: str, train_no: str
    ) -> Entry | None:
        for entry in self._cell(day_type, line, direction, statn_id):
            if entry.train_no == train_no:
                return entry
        return None

    def nearest(
        self, day_type: int, line: str, direction: int, statn_id: str, secs: int
    ) -> Entry | None:
        cell = self._cell(day_type, line, direction, statn_id)
        return min(cell, key=lambda e: abs(e.secs - secs)) if cell else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="B1 실측 시간표 만들기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--out", default="data/measured_timetable.json")
    parser.add_argument("--min-days", type=int, default=MIN_DAYS)
    parser.add_argument("--slot-width", type=int, default=SLOT_WIDTH_SEC)
    parser.add_argument("--fallback-dwell", type=int, default=FALLBACK_DWELL_SEC)
    parser.add_argument("--mode", choices=("auto", "train_no", "slots"), default="auto")
    args = parser.parse_args(argv)
    if not Path(args.db).is_file():
        print(f"수집 DB가 없습니다: {args.db}", file=sys.stderr)
        return 2
    runs = build_runs(load_events(args.db))
    artifact = build_b1(
        runs, min_days=args.min_days, slot_width=args.slot_width,
        fallback_dwell=args.fallback_dwell, mode=args.mode,
    )  # fmt: skip
    names, ids = station_names(args.db), station_ids(args.db)
    artifact["stations"] = {
        f"{line}|{statn_id}": {"station_id": ids.get((line, statn_id)), "name": name}
        for (line, statn_id), name in sorted(names.items())
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(artifact, ensure_ascii=False, separators=(",", ":")), "utf-8")
    n_entries = sum(len(v) for v in artifact["cells"].values())
    print(
        f"B1: 날짜 {artifact['n_dates']}, 출발 {artifact['n_departures']}건 → "
        f"칸 {len(artifact['cells'])}개, 시각 {n_entries}개 ({out})"
    )
    if not artifact["cells"]:
        print(f"낼 칸이 없습니다. 관측 일수가 {args.min_days}일 미만입니다(--min-days).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
