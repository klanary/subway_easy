"""서울시 공식 시간표 사본 만들기(수동 검토 2026-11-22).

서울시 시간표 API가 어떤 노선을 갑자기 비워 줄 때(2026-11-22 새벽부터 1호선 102개 역 모두 INFO-200)
서버가 대신 쓸 사본을 VM 시간표 캐시(`ml_data/timetable_cache`)에서 뽑는다.
서버가 쓰는 칸만 남기고 gzip으로 묶는다(1호선 약 550KB).

    python -m ml.official_snapshot --cache ml_data/timetable_cache --lines 1호선 \
        --out official_snapshot.json.gz

외부 모듈 없이 표준 라이브러리만 쓴다(VM에서 바로 돌린다).
"""

import argparse
import gzip
import json
import sqlite3
from datetime import date
from pathlib import Path

# 서버의 departures()가 읽는 칸
FIELDS = ("LEFTTIME", "DESTSTATION", "SUBWAYENAME", "EXPRESS_YN", "TRAIN_NO")


def build(cache_dir: Path, codes: set[str], taken_on: str) -> dict:
    tables: dict[str, list[dict]] = {}
    for path in sorted(cache_dir.glob("*.json")):
        station_cd, week, inout = path.stem.split("_")
        if station_cd not in codes:
            continue
        rows = json.loads(path.read_text(encoding="utf-8"))
        tables[f"{station_cd}|{week}|{inout}"] = [{k: r.get(k) for k in FIELDS} for r in rows]
    return {"version": 1, "takenOn": taken_on, "tables": tables}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--cache", default="ml_data/timetable_cache")
    p.add_argument("--stations-db", default="data/stations.sqlite")
    p.add_argument("--lines", nargs="+", default=["1호선"])
    p.add_argument("--taken-on", default=date.today().isoformat(), help="캐시를 받은 날")
    p.add_argument("--out", default="official_snapshot.json.gz")
    a = p.parse_args()
    db = sqlite3.connect(a.stations_db)
    marks = ",".join("?" * len(a.lines))
    query = f"select station_cd from station_codes where line_name in ({marks})"
    codes = {r[0] for r in db.execute(query, a.lines)}
    snapshot = build(Path(a.cache), codes, a.taken_on)
    empty = sum(1 for v in snapshot["tables"].values() if not v)
    raw = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    Path(a.out).write_bytes(gzip.compress(raw))
    print(f"역 코드 {len(codes)}개, 표 {len(snapshot['tables'])}개(빈 표 {empty}개) → {a.out}")


if __name__ == "__main__":
    main()
