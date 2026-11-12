"""외부 API의 실제 응답을 tests/fixtures/에 저장한다.

사용법 (server/에서): python -m scripts.capture_fixtures [--only <api>]

- 키는 Settings(.env 또는 환경 변수)에서 읽고, 저장 전에 응답에서 키 문자열을 가린다.
- 오류 응답도 그대로 저장한다. P2 테스트가 오류 흐름에 쓴다.
- 좌표는 LANDMARKS의 공공 장소만 쓴다. 공개 저장소에 생활 반경이 남지 않게 하기 위해서다.
"""

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Literal
from urllib.parse import quote

import httpx

from app.clients.endpoints import KAKAO_ROUTE_URL, SEOUL_REALTIME_BASE
from app.core.config import Settings

Api = Literal["seoul_realtime", "kakao_route"]
APIS: tuple[Api, ...] = ("seoul_realtime", "kakao_route")

# (위도, 경도)
LANDMARKS: dict[str, tuple[float, float]] = {
    "홍대입구역": (37.5571, 126.9245),
    "강남역": (37.4979, 127.0276),
    "경복궁역": (37.5759, 126.9735),
    "시청역": (37.5657, 126.9769),
    "서울대학교 정문": (37.4664, 126.9480),
    "서해 바다": (37.0, 125.0),
    "제주공항": (33.5070, 126.4929),
}


@dataclass(frozen=True)
class CaptureItem:
    api: Api
    slug: str
    params: dict

    def points(self) -> list[tuple[float, float]]:
        """이 항목이 쓰는 모든 좌표. 좌표는 params에 (위도, 경도) 튜플로만 넣는다."""
        return [v for v in self.params.values() if isinstance(v, tuple)]


def _route(slug: str, start: str, end: str) -> CaptureItem:
    return CaptureItem(
        "kakao_route",
        slug,
        {"start": LANDMARKS[start], "end": LANDMARKS[end], "start_name": start, "end_name": end},
    )


CAPTURE_PLAN: list[CaptureItem] = [
    CaptureItem("seoul_realtime", "hongdae", {"station": "홍대입구"}),
    CaptureItem("seoul_realtime", "cityhall", {"station": "시청"}),
    # 이름 예외 역. P1 Task 5의 예외 표(§2)에서 고른 역으로 바꿀 수 있다.
    CaptureItem("seoul_realtime", "exception", {"station": "응암순환(상선)"}),
    CaptureItem("seoul_realtime", "nodata", {"station": "없는역이름"}),
    _route("subway_only", "홍대입구역", "강남역"),
    _route("transfer", "홍대입구역", "경복궁역"),
    _route("bus_subway", "서울대학교 정문", "홍대입구역"),
    _route("error_equal_points", "홍대입구역", "홍대입구역"),
    _route("error_far", "홍대입구역", "제주공항"),
]


def fixture_path(item: CaptureItem, fixtures_dir: Path) -> Path:
    return fixtures_dir / f"{item.api}_{item.slug}.json"


def redact(text: str, secrets: list[str]) -> str:
    for secret in secrets:
        if secret:
            text = text.replace(secret, "***")
    return text


def _request(client: httpx.Client, item: CaptureItem, settings: Settings) -> httpx.Response:
    p = item.params
    kakao_headers = {"Authorization": f"KakaoAK {settings.kakao_rest_api_key}"}
    if item.api == "seoul_realtime":
        url = (
            f"{SEOUL_REALTIME_BASE}/{settings.seoul_realtime_api_key}"
            f"/json/realtimeStationArrival/0/50/{quote(p['station'])}"
        )
        return client.get(url)
    (start_lat, start_lng), (end_lat, end_lng) = p["start"], p["end"]
    return client.get(
        KAKAO_ROUTE_URL,
        params={
            "start_x": start_lng,
            "start_y": start_lat,
            "end_x": end_lng,
            "end_y": end_lat,
            "s_name": p["start_name"],
            "e_name": p["end_name"],
        },
        headers=kakao_headers,
    )


def _skip_reason(item: CaptureItem, settings: Settings) -> str | None:
    if item.api == "seoul_realtime":
        return None if settings.seoul_realtime_api_key else "SEOUL_REALTIME_API_KEY가 비어 있어요"
    if not settings.kakao_rest_api_key:
        return "KAKAO_REST_API_KEY가 비어 있어요"
    if item.api == "kakao_route" and not KAKAO_ROUTE_URL:
        return "KAKAO_ROUTE_URL을 먼저 채우세요"
    return None


def capture(item: CaptureItem, client: httpx.Client, settings: Settings, out_dir: Path) -> Path:
    response = _request(client, item, settings)
    try:
        body = response.json()
    except ValueError:
        body = response.text
    text = json.dumps({"status": response.status_code, "body": body}, ensure_ascii=False, indent=2)
    text = redact(text, [settings.seoul_realtime_api_key, settings.kakao_rest_api_key])
    path = fixture_path(item, out_dir)
    path.write_text(text + "\n", encoding="utf-8")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="외부 API 응답 샘플을 저장한다.")
    parser.add_argument("--only", choices=APIS, help="이 API의 항목만 수집")
    args = parser.parse_args(argv)

    settings = Settings()
    secrets = [settings.seoul_realtime_api_key, settings.kakao_rest_api_key]
    out_dir = Path(settings.fixtures_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    failed = 0
    with httpx.Client(timeout=10.0) as client:
        for item in CAPTURE_PLAN:
            if args.only and item.api != args.only:
                continue
            name = fixture_path(item, out_dir).name
            reason = _skip_reason(item, settings)
            if reason:
                print(f"건너뜀 {name}: {reason}")
                continue
            try:
                path = capture(item, client, settings, out_dir)
            except httpx.HTTPError as e:
                failed += 1
                print(f"실패 {name}: {redact(repr(e), secrets)}")
                continue
            status = json.loads(path.read_text(encoding="utf-8"))["status"]
            print(f"저장 {path} (HTTP {status})")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
