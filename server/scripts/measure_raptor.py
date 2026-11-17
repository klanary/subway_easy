"""운행표 메모리·검색 시간 재기(경로 계획 2026-11-18 Task 4).

실제 규모의 합성 운행표: 19개 노선 × 37역 × 2방향 × 요일 3 × 하루 170·120·120대(정차 약 72만).
노선끼리 일부 역을 함께 써서 환승이 생기게 한다.

사용법 (server/에서):  python scripts/measure_raptor.py
"""

import gc
import gzip
import json
import random
import sys
import time
import tracemalloc
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.clock import KST  # noqa: E402
from app.services.raptor import search  # noqa: E402
from app.services.trips import TripsData  # noqa: E402

LINES, STATIONS_PER_LINE = 19, 37


def build() -> dict:
    random.seed(7)
    stations: list[str] = []
    index: dict[str, int] = {}

    def sid(name: str) -> int:
        if name not in index:
            index[name] = len(stations)
            stations.append(name)
        return index[name]

    lines = []
    for li in range(LINES):
        names = []
        for s in range(STATIONS_PER_LINE):
            # 5역마다 환승역. 노선마다 순서를 돌려 8개 환승역을 모두 지난다(모든 노선이 이어짐)
            names.append(f"환승{(s // 5 + li) % 8}" if s % 5 == 0 else f"L{li}_역{s}")
        lines.append([sid(n) for n in names])
    day_types: dict[str, list] = {}
    stops_total = 0
    for day, per_day in (("1", 170), ("2", 120), ("3", 120)):
        patterns = []
        for li, stops in enumerate(lines):
            for direction, order in (("하행", stops), ("상행", stops[::-1])):
                trips = []
                for t in range(per_day):
                    start = 5 * 3600 + t * (19 * 3600 // per_day) + random.randint(0, 30)
                    times = []
                    clock = start
                    for _ in order:
                        times += [clock, clock + 30]
                        clock += 120
                    trips.append([f"{li}{t:04d}", 0, "끝", 0, 0, times])
                    stops_total += len(order)
                patterns.append(
                    {"line": f"{li}호선", "direction": direction, "stops": order, "trips": trips}
                )
        day_types[day] = patterns
    print(f"정차 {stops_total:,}개, 역 {len(stations)}개")
    return {"kind": "trips-server", "version": 1, "built_on": "2026-11-18",
            "stations": stations, "day_types": day_types}  # fmt: skip


def main() -> None:
    raw = build()
    body = gzip.compress(json.dumps(raw, ensure_ascii=False, separators=(",", ":")).encode())
    print(f"gzip {len(body) / 1e6:.1f}MB")
    del raw
    gc.collect()
    tracemalloc.start()
    data = TripsData.from_artifact(json.loads(gzip.decompress(body)))
    _, peak = tracemalloc.get_traced_memory()
    gc.collect()
    current, _ = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    print(f"올리는 동안 최대 {peak / 1e6:.0f}MB, 올린 뒤 {current / 1e6:.0f}MB")
    names = data.day(1)._stations  # noqa: SLF001 — 측정 스크립트
    random.seed(1)
    pairs = [(random.choice(names), random.choice(names)) for _ in range(30)]
    reached = 0
    depart = datetime(2026, 11, 18, 8, 0, tzinfo=KST)
    took = []
    for origin, dest in pairs:
        started = time.perf_counter()
        reached += bool(search(data, origin, dest, depart))
        took.append(time.perf_counter() - started)
    took.sort()
    print(f"검색 30번(찾음 {reached}): 중앙 {took[15]:.3f}초, 최대 {took[-1]:.3f}초")


if __name__ == "__main__":
    main()
