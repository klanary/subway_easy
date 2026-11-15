"""B2 구간 시간 추정(설계 §4): 열차의 마지막 관측 + 학습한 구간 시간으로 하류 역 도착을 예측한다.

사용법 (server/에서):
    python -m ml.b2 --db ml_data/collector.sqlite --out data/segment_times.json

배우는 것(모두 중앙값과 10·90백분위, 표본 수):
- hops: 한 역 도착 → 다음에 도착이 찍힌 역 도착(정차 + 이동). 일반·급행을 따로 배운다.
  조건은 (요일 구분, 시간대) → (요일 구분) → (전체) 순으로 풀어 표본 `min_n`개 이상인 것을 쓴다.
- dwell: 같은 역의 도착 → 출발.
- lead: 진입(0)·전역출발(3)이 찍힌 뒤 그 역 도착까지 남는 시간.
"""

import argparse
import json
import statistics
import sys
from collections import defaultdict, deque
from datetime import datetime
from pathlib import Path

from app.core.clock import KST
from ml.dataset import APPROACH, ARRIVE, DEPART, ENTER, Run, build_runs, load_events

VERSION = 1
MIN_N = 5  # 이보다 표본이 적은 칸은 쓰지 않는다
MAX_HOP_SEC = 1200  # 이보다 긴 구간은 관측 오류로 보고 버린다
MAX_DWELL_SEC = 600
MAX_LEAD_SEC = 600
_MAX_PATH_STOPS = 120


def _percentile(sorted_values: list[float], q: float) -> float:
    """선형 보간 백분위(q는 0~1)."""
    pos = (len(sorted_values) - 1) * q
    low = int(pos)
    high = min(low + 1, len(sorted_values) - 1)
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (pos - low)


def stats(values: list[float]) -> dict:
    ordered = sorted(values)
    return {
        "median": statistics.median(ordered),
        "p10": round(_percentile(ordered, 0.1), 1),
        "p90": round(_percentile(ordered, 0.9), 1),
        "n": len(ordered),
    }


def _summarize(samples: dict[str, list[float]], min_n: int) -> dict[str, dict]:
    return {key: stats(values) for key, values in sorted(samples.items()) if len(values) >= min_n}


def _exp(express: int) -> int:
    return 1 if express else 0


def hop_samples(runs: list[Run], *, max_hop_sec: int = MAX_HOP_SEC):
    """(run, 앞 역, 뒤 역, 앞 역 도착 초, 걸린 초). 도착이 찍힌 역끼리만 잇는다."""
    for run in runs:
        stops = [v for v in run.visits if v.arr is not None]
        for a, b in zip(stops, stops[1:], strict=False):
            took = b.arr - a.arr
            if 0 < took <= max_hop_sec:
                yield run, a.statn_id, b.statn_id, a.arr, took


def build_b2(
    runs: list[Run],
    *,
    min_n: int = MIN_N,
    max_hop_sec: int = MAX_HOP_SEC,
    now: datetime | None = None,
) -> dict:
    hops: dict[str, list[float]] = defaultdict(list)
    hops_day: dict[str, list[float]] = defaultdict(list)
    hops_hour: dict[str, list[float]] = defaultdict(list)
    for run, a, b, at, took in hop_samples(runs, max_hop_sec=max_hop_sec):
        base = f"{run.line}|{run.direction}|{a}|{b}|{_exp(run.express)}"
        hops[base].append(took)
        hops_day[f"{base}|{run.day_type}"].append(took)
        hops_hour[f"{base}|{run.day_type}|{at // 3600 % 24}"].append(took)

    dwell: dict[str, list[float]] = defaultdict(list)
    dwell_line: dict[str, list[float]] = defaultdict(list)
    lead: dict[str, list[float]] = defaultdict(list)
    for run in runs:
        for v in run.visits:
            if v.arr is None:
                continue
            if v.dep is not None and 0 < v.dep - v.arr <= MAX_DWELL_SEC:
                dwell[f"{run.line}|{v.statn_id}"].append(v.dep - v.arr)
                dwell_line[run.line].append(v.dep - v.arr)
                dwell_line["*"].append(v.dep - v.arr)
            for status, at in ((ENTER, v.enter), (APPROACH, v.approach)):
                if at is not None and 0 <= v.arr - at <= MAX_LEAD_SEC:
                    lead[f"{run.line}|{status}"].append(v.arr - at)
                    lead[f"*|{status}"].append(v.arr - at)

    dates = sorted({run.service_date for run in runs})
    return {
        "version": VERSION,
        "kind": "b2",
        "built_at": (now or datetime.now(KST)).astimezone(KST).isoformat(timespec="seconds"),
        "date_from": dates[0].isoformat() if dates else None,
        "date_to": dates[-1].isoformat() if dates else None,
        "n_dates": len(dates),
        "min_n": min_n,
        "max_hop_sec": max_hop_sec,
        "hops": _summarize(hops, min_n),
        "hops_day": _summarize(hops_day, min_n),
        "hops_hour": _summarize(hops_hour, min_n),
        "dwell": _summarize(dwell, min_n),
        "dwell_line": _summarize(dwell_line, min_n),
        "lead": _summarize(lead, min_n),
    }


class SegmentModel:
    """결과물을 읽어 구간 시간·도착 예측을 준다."""

    def __init__(self, artifact: dict):
        self._a = artifact
        # (호선, 방향, 급행) → 역 → 다음 역들(관측된 이웃)
        self._next: dict[tuple[str, int, int], dict[str, list[str]]] = defaultdict(dict)
        for key in artifact["hops"]:
            line, direction, a, b, exp = key.split("|")
            self._next[(line, int(direction), int(exp))].setdefault(a, []).append(b)

    def _median(self, table: str, key: str) -> float | None:
        found = self._a[table].get(key)
        return found["median"] if found else None

    def hop(
        self, line: str, direction: int, a: str, b: str, *, express: int, day_type: int, hour: int
    ) -> float | None:
        base = f"{line}|{direction}|{a}|{b}|{_exp(express)}"
        for table, key in (
            ("hops_hour", f"{base}|{day_type}|{hour}"),
            ("hops_day", f"{base}|{day_type}"),
            ("hops", base),
        ):
            median = self._median(table, key)
            if median is not None:
                return median
        return None

    def path(self, line: str, direction: int, a: str, b: str, *, express: int) -> list[str] | None:
        """관측된 이웃을 따라 a에서 b까지(정거장 수가 가장 적은 길). 없으면 None."""
        graph = self._next.get((line, direction, _exp(express)), {})
        if a == b:
            return [a]
        previous: dict[str, str] = {}
        queue: deque[tuple[str, int]] = deque([(a, 0)])
        while queue:
            here, depth = queue.popleft()
            if depth >= _MAX_PATH_STOPS:
                continue
            for nxt in graph.get(here, ()):
                if nxt in previous or nxt == a:
                    continue
                previous[nxt] = here
                if nxt == b:
                    path = [b]
                    while path[-1] != a:
                        path.append(previous[path[-1]])
                    return path[::-1]
                queue.append((nxt, depth + 1))
        return None

    def arrival_here(self, line: str, statn_id: str, status: int, secs: float) -> float | None:
        """관측(상태, 시각)을 "그 역 도착 시각"으로 바꾼다."""
        if status == ARRIVE:
            return secs
        if status == DEPART:
            for table, key in (
                ("dwell", f"{line}|{statn_id}"),
                ("dwell_line", line),
                ("dwell_line", "*"),
            ):
                median = self._median(table, key)
                if median is not None:
                    return secs - median
            return None
        for key in (f"{line}|{status}", f"*|{status}"):
            median = self._median("lead", key)
            if median is not None:
                return secs + median
        return None

    def eta(
        self, line: str, direction: int, statn_id: str, status: int, secs: float, target: str, *,
        express: int = 0, day_type: int,
    ) -> float | None:  # fmt: skip
        """`statn_id`에서 (status, secs)로 관측된 열차가 `target`에 도착할 시각(초). 모르면 None."""
        at = self.arrival_here(line, statn_id, status, secs)
        if at is None:
            return None
        path = self.path(line, direction, statn_id, target, express=express)
        if path is None:
            return None
        for a, b in zip(path, path[1:], strict=False):
            took = self.hop(
                line, direction, a, b, express=express, day_type=day_type,
                hour=int(at) // 3600 % 24,
            )  # fmt: skip
            if took is None:
                return None
            at += took
        return at

    def is_outlier(
        self, line: str, direction: int, a: str, b: str, seconds: float, *, express: int
    ) -> bool:
        """배운 구간 시간에서 크게 벗어났는가(중앙값의 0.4배 미만 또는 2.5배 + 60초 초과)."""
        median = self._median("hops", f"{line}|{direction}|{a}|{b}|{_exp(express)}")
        if median is None:
            return False
        return seconds < 0.4 * median or seconds > 2.5 * median + 60


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="B2 구간 시간 만들기")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--out", default="data/segment_times.json")
    parser.add_argument("--min-n", type=int, default=MIN_N)
    args = parser.parse_args(argv)
    if not Path(args.db).is_file():
        print(f"수집 DB가 없습니다: {args.db}", file=sys.stderr)
        return 2
    artifact = build_b2(build_runs(load_events(args.db)), min_n=args.min_n)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(artifact, ensure_ascii=False, separators=(",", ":")), "utf-8")
    print(
        f"B2: 날짜 {artifact['n_dates']}일 → 구간 {len(artifact['hops'])}개"
        f"(요일별 {len(artifact['hops_day'])}, 시간대별 {len(artifact['hops_hour'])}), "
        f"정차 {len(artifact['dwell'])}역 ({out})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
