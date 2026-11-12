"""서울시 실시간 도착을 전 역에 한 번씩 물어 "방향(상행·하행·내선·외선) → 다음 역"을 모은다.

사용법 (server/에서): SEOUL_KEY=<실시간 인증키> python -m scripts.fetch_directions

- 경로의 첫 탑승은 "다음 역"만 안다. 시간표는 방향으로 묻기 때문에 둘을 잇는 표가 필요하다
  (재설계 스펙 5.3). 결과는 data/raw/realtime_directions.json이고 scripts.build_stations의
  네 번째 입력이다.
- 낮(열차 운행 중)에 실행해야 한다. 역마다 1회, 서울 노선 역 수만큼 호출한다.
"""

import json
import os
import sys
import time
from pathlib import Path
from urllib.parse import quote

import httpx

from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.services.normalize import SUBWAY_ID_TO_LINE
from app.services.remaining_time import parse_train_line_nm
from app.services.station_index import StationIndex

OUT_PATH = Path("data/raw/realtime_directions.json")
QUOTA_CODE = "ERROR-337"  # 2026-11-12 실제 응답: {"status": 500, "code": "ERROR-337", ...}


def collect(index: StationIndex, rows_by_api_name: dict[str, list[dict]]) -> list[dict]:
    """실시간 행에서 (역, 호선, 방향, 다음 역)을 뽑는다. 다음 역을 우리 역으로 잇지 못하면 뺀다."""
    found: set[tuple[str, str, str, str]] = set()
    for station in index.all():
        for item in rows_by_api_name.get(station.api_name, []):
            line = SUBWAY_ID_TO_LINE.get(str(item.get("subwayId")))
            direction = str(item.get("updnLine") or "")
            if line is None or line not in station.lines or not direction:
                continue
            _, towards = parse_train_line_nm(item.get("trainLineNm"))
            nxt = index.resolve(towards, line) if towards else None
            if nxt is not None and nxt.id != station.id:
                found.add((station.id, line, direction, nxt.id))
    return [
        {"stationId": s, "line": ln, "direction": d, "nextId": n} for s, ln, d, n in sorted(found)
    ]


def main() -> int:
    key = os.environ.get("SEOUL_KEY", "")
    if not key:
        print("SEOUL_KEY 환경 변수에 서울시 실시간 인증키를 넣으세요.")
        return 2
    index = StationIndex.load("data/stations.sqlite")
    names = sorted(
        {s.api_name for s in index.all() if any(index.realtime_supported(x) for x in s.lines)}
    )
    rows: dict[str, list[dict]] = {}
    with httpx.Client(timeout=10.0) as client:
        for name in names:
            url = f"{SEOUL_REALTIME_BASE}/{key}/json/realtimeStationArrival/0/50/{quote(name)}"
            try:
                body = client.get(url).json()
            except (httpx.HTTPError, ValueError):
                continue
            if body.get("code") == QUOTA_CODE:
                print(f"일일 한도 초과로 {len(rows)}개 역에서 멈춤")
                break
            rows[name] = body.get("realtimeArrivalList") or []
            time.sleep(0.05)
    # 이전 실행 결과와 합친다(시각마다 다니는 열차가 달라 모일수록 빈 방향이 줄어든다).
    old = json.loads(OUT_PATH.read_text(encoding="utf-8")) if OUT_PATH.exists() else []
    merged = {tuple(x.values()): x for x in [*old, *collect(index, rows)]}
    result = [merged[k] for k in sorted(merged)]
    OUT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"역 {len(names)}개 조회, 방향 {len(result)}개 저장 {OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
