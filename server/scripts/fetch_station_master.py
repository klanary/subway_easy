"""서울시 역사마스터 정보를 OpenAPI로 내려받아 data/raw/station_master.json에 저장한다.

사용법 (server/에서): SEOUL_OPENAPI_KEY=<일반 인증키> python -m scripts.fetch_station_master

- 일반 인증키(서울 열린데이터광장)가 필요하다. 실시간 지하철 인증키와는 다른 키다.
- 샘플 키(sample)는 5행까지만 준다. 저장한 파일은 scripts.build_stations의 입력이다.
"""

import json
import os
import sys
from pathlib import Path

import httpx

# 키가 URL 경로에 들어가므로 이 주소를 그대로 출력하지 않는다(스펙 6.8).
MASTER_URL = "http://openapi.seoul.go.kr:8088/{key}/json/subwayStationMaster/{start}/{end}/"
PAGE_SIZE = 1000  # 한 번에 받을 수 있는 최대 행 수
OUT_PATH = Path("data/raw/station_master.json")


def fetch_all(client: httpx.Client, key: str) -> list[dict]:
    rows: list[dict] = []
    start = 1
    while True:
        response = client.get(MASTER_URL.format(key=key, start=start, end=start + PAGE_SIZE - 1))
        body = response.json().get("subwayStationMaster")
        if body is None:  # 오류는 RESULT만 온다
            result = response.json().get("RESULT", {})
            raise RuntimeError(f"{result.get('CODE')}: {result.get('MESSAGE')}")
        rows.extend(body["row"])
        if len(rows) >= body["list_total_count"] or not body["row"]:
            return rows
        start += PAGE_SIZE


def main() -> int:
    key = os.environ.get("SEOUL_OPENAPI_KEY", "")
    if not key:
        print("SEOUL_OPENAPI_KEY 환경 변수에 서울 열린데이터광장 일반 인증키를 넣으세요.")
        return 2
    try:
        with httpx.Client(timeout=20.0) as client:
            rows = fetch_all(client, key)
    except (httpx.HTTPError, RuntimeError) as e:
        print(f"실패: {str(e).replace(key, '***')}")
        return 1
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(rows, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"저장 {OUT_PATH} ({len(rows)}행)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
