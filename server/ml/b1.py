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
from collections.abc import Set
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path

from app.core.clock import KST
from ml.dataset import Run, Visit, build_runs, load_events, station_ids, station_names
from ml.scopes import Scope, band_of, scope_key

VERSION = 2  # 2: first_seen·last_seen, scopes, 창·기준일 정보
MIN_DAYS = 3  # 관측 일수 하한
SLOT_WIDTH_SEC = 120  # 열차 번호 없이 묶을 때 한 칸의 폭
FALLBACK_DWELL_SEC = 30  # 출발이 안 찍힌 역: 도착 + 이만큼(호선 정차 중앙값이 없을 때)
STABLE_WITHIN_SEC = 300  # 같은 열차 번호의 출발이 중앙값에서 이 안이면 "같은 시각"
STABLE_DAY_SHARE = 0.7  # 관측한 날 가운데 이 비율 이상이 같은 시각이면 그 번호는 안정적
STABLE_RATIO = 0.8  # 이 비율 이상이면 열차 번호로 묶는다
WINDOW = {1: 20, 2: 8, 3: 8}  # 요일 구분별로 쓰는 최근 날짜 수
STALE_DAYS = {1: 3, 2: 2, 3: 2}  # 이만큼 연속으로 안 보인 열차는 뺀다
MIN_DAYS_AFTER_CHANGE = 2  # 변경이 확정된 단위의 관측 일수 하한
EPOCH_MARGIN_SEC = 300  # 기준일을 시간대 경계 양쪽으로 번지게 하는 폭
PASS_MAX_DWELL_SEC = 20  # 급행의 도착→출발이 이보다 짧으면 서지 않고 지나간 것
PASS_MIN_EVIDENCE = 5  # 역이 "지나가는 역"인지 정하는 데 필요한 방문 수(도착·출발이 둘 다 있는 것)
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
    first_seen: str | None = None  # 이 시각을 만든 관측의 첫 날짜(ISO)
    last_seen: str | None = None
    official: bool = False  # 관측이 아니라 공식 시각 + 평소 지연으로 채운 항목


PassKey = tuple[str, int, int, str]  # (호선, 방향, express 값, statn_id)


def _visit_passed(visit: Visit) -> bool | None:
    """도착·출발이 둘 다 있으면 지나갔는지(True)·섰는지(False). 하나만 있으면 None."""
    if visit.arr is None or visit.dep is None:
        return None
    return 0 <= visit.dep - visit.arr < PASS_MAX_DWELL_SEC


def passed_stations(runs: list[Run]) -> set[PassKey]:
    """급행이 서지 않고 지나가는 것으로 본 (호선, 방향, express 값, 역).

    위치 API는 급행이 서지 않는 역에서도 이벤트를 보낸다. 도착·출발이 둘 다 있는 방문이
    `PASS_MIN_EVIDENCE`개 이상이고 그 과반이 지나간 것이면 그 역을 지나가는 역으로 본다.
    """
    counts: dict[PassKey, list[int]] = defaultdict(lambda: [0, 0])  # [지나감, 섬]
    for run in runs:
        if not run.express:
            continue
        for v in run.visits:
            passed = _visit_passed(v)
            if passed is not None:
                counts[(run.line, run.direction, run.express, v.statn_id)][0 if passed else 1] += 1
    return {
        key
        for key, (n_pass, n_stop) in counts.items()
        if n_pass + n_stop >= PASS_MIN_EVIDENCE and n_pass > n_stop
    }


def is_pass(run: Run, visit: Visit, passed: Set[PassKey]) -> bool:
    """이 방문이 서지 않고 지나간 것인가. 급행이 아니면 언제나 False."""
    if not run.express:
        return False
    known = _visit_passed(visit)
    if known is not None:
        return known
    return (run.line, run.direction, run.express, visit.statn_id) in passed


def line_dwell(runs: list[Run], fallback: int) -> dict[str, int]:
    """호선별 정차 시간 중앙값(초). 표본이 20개 미만인 호선은 `fallback`. 지나간 방문은 뺀다."""
    samples: dict[str, list[int]] = defaultdict(list)
    passed = passed_stations(runs)
    for run in runs:
        for v in run.visits:
            if is_pass(run, v, passed):
                continue
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
    """역마다 출발 시각 하나. 종착역과 급행이 지나간 역은 뺀다.

    출발(2)이 없으면 도착(1) + 정차 시간.

    `dwell`을 주면 그 호선별 정차 시간을 쓴다(평가에서 학습 날짜의 값을 시험 날짜에 쓸 때).
    """
    if dwell is None:
        dwell = line_dwell(runs, fallback_dwell)
    points: list[Departure] = []
    passed = passed_stations(runs)
    for run in runs:
        for v in run.visits:
            if v.statn_id == run.dest_statn_id or is_pass(run, v, passed):
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


def stability_from_points(points: list[Departure]) -> dict[str, float]:
    """호선별로, 같은 요일 구분에서 2일 이상 본 (역, 열차 번호) 가운데 시각이 안정적인 것의 비율.

    안정적 = 관측한 날의 70% 이상이 그 번호의 중앙값에서 300초 안.

    2일 이상 본 것이 없는 호선은 결과에 없다(판단할 수 없음).
    """
    by_key: dict[tuple, dict[date, int]] = defaultdict(dict)
    for p in points:
        days = by_key[(p.line, p.day_type, p.direction, p.statn_id, p.train_no)]
        days[p.service_date] = min(p.secs, days.get(p.service_date, p.secs))
    stable: Counter[str] = Counter()
    total: Counter[str] = Counter()
    for (line, *_), days in by_key.items():
        if len(days) < 2:
            continue
        total[line] += 1
        # 크게 늦은 날 하루가 "번호가 날마다 다르다"로 보이지 않게, 중앙값 근처인 날의 비율로 본다.
        middle = statistics.median(days.values())
        near = sum(abs(secs - middle) <= STABLE_WITHIN_SEC for secs in days.values())
        if near >= STABLE_DAY_SHARE * len(days):
            stable[line] += 1
    return {line: stable[line] / total[line] for line in total}


def train_no_stability(runs: list[Run]) -> dict[str, float]:
    return stability_from_points(departure_points(runs))


def _common(values: list) -> object:
    return Counter(values).most_common(1)[0][0] if values else None


def _entry(group: list[Departure], train_no: str | None) -> Entry:
    dates = [p.service_date for p in group]
    return Entry(
        secs=round(statistics.median(p.secs for p in group)),
        train_no=train_no,
        n_days=len(set(dates)),
        dest_name=_common([p.dest_name for p in group]),
        express=_common([p.express for p in group]),
        first_seen=min(dates).isoformat(),
        last_seen=max(dates).isoformat(),
    )


def _by_train_no(points: list[Departure]) -> list[Entry]:
    """열차 번호마다 하나. 관측 일수 하한은 부르는 쪽이 건다."""
    by_train: dict[str, dict[date, Departure]] = defaultdict(dict)
    for p in points:
        seen = by_train[p.train_no].get(p.service_date)
        if seen is None or p.secs < seen.secs:
            by_train[p.train_no][p.service_date] = p
    return [_entry(list(days.values()), train_no) for train_no, days in by_train.items()]


def _by_slots(points: list[Departure], slot_width: int) -> list[Entry]:
    entries: list[Entry] = []
    slot: list[Departure] = []
    for p in sorted(points, key=lambda p: p.secs):
        # 한 칸에는 하루에 한 대만 들어간다. 같은 날 열차가 또 오면 다음 칸이다.
        too_far = slot and p.secs - slot[0].secs > slot_width
        same_day = any(q.service_date == p.service_date for q in slot)
        if too_far or same_day:
            entries.append(_entry(slot, None))
            slot = []
        slot.append(p)
    if slot:
        entries.append(_entry(slot, None))
    return entries


def _near_bands(secs: int) -> set[int]:
    """그 시각의 시간대와, 경계 5분 안이면 이웃 시간대까지."""
    return {band_of(secs), band_of(secs - EPOCH_MARGIN_SEC), band_of(secs + EPOCH_MARGIN_SEC)}


def build_b1_from_points(
    points: list[Departure],
    *,
    modes: dict[str, str] | None = None,
    min_days: int = MIN_DAYS,
    slot_width: int = SLOT_WIDTH_SEC,
    window: dict[int, int] | None = None,
    stale_days: dict[int, int] | None = None,
    epochs: dict[Scope, date] | None = None,
    active: dict[Scope, set[date]] | None = None,
    min_days_after_change: int = MIN_DAYS_AFTER_CHANGE,
    as_of: date | None = None,
    now: datetime | None = None,
    dwell: dict[str, int] | None = None,
) -> dict:
    """출발 시각 목록으로 결과물을 만든다. B1을 지키는 세 겹을 여기서 건다.

    1. 창(`window`): 요일 구분별로 가장 최근 N개 날짜만 쓴다.
    2. 안 보인 열차 빼기(`stale_days`): 그 단위의 최근 활동 날짜에 연속으로 안 보인 열차를 뺀다.
    3. 기준일(`epochs`): 시간표가 바뀐 것으로 확정된 단위는 변경일 이후 날짜만 쓴다.
    """
    epochs = epochs or {}
    if as_of is not None:
        points = [p for p in points if p.service_date <= as_of]
    if window is not None:
        dates_of: dict[tuple[str, int], set[date]] = defaultdict(set)
        for p in points:
            dates_of[(p.line, p.day_type)].add(p.service_date)
        keep = {
            key: set(sorted(dates)[-window[key[1]] :]) if key[1] in window else dates
            for key, dates in dates_of.items()
        }
        points = [p for p in points if p.service_date in keep[(p.line, p.day_type)]]
    if epochs:
        # 시간대 경계를 넘나드는 열차의 옛 관측이 섞이지 않게 기준일을 양쪽 5분으로 번지게 한다.
        points = [
            p
            for p in points
            if not any(
                (epoch := epochs.get((p.day_type, p.line, p.direction, band))) is not None
                and p.service_date < epoch
                for band in _near_bands(p.secs)
            )
        ]

    stability = stability_from_points(points) if modes is None else {}
    lines = sorted({p.line for p in points})
    if modes is None:
        modes = {
            line: "train_no" if stability.get(line, 1.0) >= STABLE_RATIO else "slots"
            for line in lines
        }
    else:
        modes = {line: modes.get(line, "train_no") for line in lines}

    # 단위별 활동 날짜(안 보인 열차를 셀 때 쓴다)와 호선·요일 구분별 쓰인 날짜.
    seen_dates: dict[Scope, set[date]] = defaultdict(set)
    used_dates: dict[tuple[str, int], set[date]] = defaultdict(set)
    by_cell: dict[tuple, list[Departure]] = defaultdict(list)
    for p in points:
        seen_dates[(p.day_type, p.line, p.direction, band_of(p.secs))].add(p.service_date)
        used_dates[(p.line, p.day_type)].add(p.service_date)
        by_cell[(p.day_type, p.line, p.direction, p.statn_id)].append(p)

    # 기준일 뒤 날짜가 모자란 단위는 시각을 내지 않는다(다시 배우는 중).
    scopes: dict[str, dict] = {}
    retraining: set[Scope] = set()
    for scope, epoch in sorted(epochs.items()):
        day_type, line, _, _ = scope
        after = [d for d in used_dates.get((line, day_type), ()) if d >= epoch]
        if (line, day_type) in used_dates and len(after) < min_days_after_change:
            retraining.add(scope)
            scopes[scope_key(scope)] = {"state": "retraining", "since": epoch.isoformat()}

    cells: dict[str, list[dict]] = {}
    for (day_type, line, direction, statn_id), group in sorted(by_cell.items()):
        if modes[line] == "train_no":
            entries = _by_train_no(group)
        else:
            entries = _by_slots(group, slot_width)
        kept: list[Entry] = []
        for entry in entries:
            scope = (day_type, line, direction, band_of(entry.secs))
            if scope in retraining:
                continue
            changed = any((day_type, line, direction, b) in epochs for b in _near_bands(entry.secs))
            if entry.n_days < (min_days_after_change if changed else min_days):
                continue
            if stale_days is not None and day_type in stale_days:
                activity = active[scope] if active is not None and scope in active else None
                if activity is None:
                    activity = seen_dates[scope]
                last = date.fromisoformat(entry.last_seen)
                if sum(d > last for d in activity) >= stale_days[day_type]:
                    continue
            kept.append(entry)
        if kept:
            kept.sort(key=lambda e: (e.secs, e.train_no or ""))
            cells[f"{day_type}|{line}|{direction}|{statn_id}"] = [vars(e).copy() for e in kept]

    dates_by_type: dict[int, set[date]] = defaultdict(set)
    for (_, day_type), dates in used_dates.items():
        dates_by_type[day_type] |= dates
    all_dates = sorted(set().union(*used_dates.values())) if used_dates else []
    return {
        "version": VERSION,
        "kind": "b1",
        "built_at": (now or datetime.now(KST)).astimezone(KST).isoformat(timespec="seconds"),
        "as_of": as_of.isoformat() if as_of is not None else None,
        "date_from": all_dates[0].isoformat() if all_dates else None,
        "date_to": all_dates[-1].isoformat() if all_dates else None,
        "n_dates": {str(k): len(v) for k, v in sorted(dates_by_type.items())},
        "n_departures": len(points),
        "min_days": min_days,
        "min_days_after_change": min_days_after_change,
        "slot_width": slot_width,
        "window": {str(k): v for k, v in sorted(window.items())} if window else None,
        "stale_days": {str(k): v for k, v in sorted(stale_days.items())} if stale_days else None,
        "modes": modes,
        "dwell": dict(sorted((dwell or {}).items())),
        "train_no_stability": {k: round(v, 3) for k, v in sorted(stability.items())},
        "scopes": scopes,
        "cells": cells,
    }


def build_b1(
    runs: list[Run],
    *,
    min_days: int = MIN_DAYS,
    slot_width: int = SLOT_WIDTH_SEC,
    fallback_dwell: int = FALLBACK_DWELL_SEC,
    mode: str = "auto",
    now: datetime | None = None,
    window: dict[int, int] | None = None,
    stale_days: dict[int, int] | None = None,
    epochs: dict[Scope, date] | None = None,
    as_of: date | None = None,
) -> dict:
    """결과물(JSON으로 쓸 dict). mode: auto(호선별로 고름), train_no, slots."""
    dwell = line_dwell(runs, fallback_dwell)
    points = departure_points(runs, fallback_dwell=fallback_dwell, dwell=dwell)
    modes = None if mode == "auto" else {p.line: mode for p in points}
    return build_b1_from_points(
        points, modes=modes, min_days=min_days, slot_width=slot_width, window=window,
        stale_days=stale_days, epochs=epochs, as_of=as_of, now=now, dwell=dwell,
    )  # fmt: skip


class MeasuredTimetable:
    """결과물을 읽어 조회한다(서버 통합 때 그대로 쓸 모양)."""

    def __init__(self, artifact: dict):
        self._cells: dict[str, list[Entry]] = {
            key: [Entry(**e) for e in entries] for key, entries in artifact["cells"].items()
        }
        self._scopes: dict[str, dict] = artifact.get("scopes") or {}

    def scope_state(self, day_type: int, line: str, direction: int, secs: int) -> str:
        """그 시각이 속한 단위의 상태: retraining(다시 배우는 중), suspect(변경 의심), ok."""
        found = self._scopes.get(scope_key((day_type, line, direction, band_of(secs))))
        state = found.get("state") if found else None
        return state if state in ("retraining", "suspect") else "ok"

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
