"""서울시 지하철역 정보(역 코드·외부 코드)를 내려받아 data/raw/station_info.json에 저장한다.

사용법 (server/에서): SEOUL_OPENAPI_KEY=<일반 인증키> python -m scripts.fetch_station_info

- `STATION_CD`는 시간표 API(SearchSTNTimeTableByIDService)의 역 코드다(재설계 스펙 5.2).
- 저장한 파일은 scripts.build_stations의 세 번째 입력이다.
"""

import json
import os
import sys
from pathlib import Path

import httpx

# 키가 URL 경로에 들어가므로 이 주소를 그대로 출력하지 않는다(스펙 6.8).
INFO_URL = "http://openapi.seoul.go.kr:8088/{key}/json/SearchInfoBySubwayNameService/{start}/{end}/"
PAGE_SIZE = 1000
OUT_PATH = Path("data/raw/station_info.json")
FIELDS = ("STATION_CD", "STATION_NM", "LINE_NUM", "FR_CODE")


def fetch_all(client: httpx.Client, key: str) -> list[dict]:
    rows: list[dict] = []
    start = 1
    while True:
        response = client.get(INFO_URL.format(key=key, start=start, end=start + PAGE_SIZE - 1))
        body = response.json().get("SearchInfoBySubwayNameService")
        if body is None:
            result = response.json().get("RESULT", {})
            raise RuntimeError(f"{result.get('CODE')}: {result.get('MESSAGE')}")
        rows.extend({k: r.get(k) for k in FIELDS} for r in body["row"])
        if len(rows) >= body["list_total_count"] or not body["row"]:
            return sorted(rows, key=lambda r: (r["LINE_NUM"], r["FR_CODE"], r["STATION_CD"]))
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
