"""GET /v1/routes(재설계 스펙 5.3).

역 id로 묻고, 지하철 경로만 빠른 순으로, 첫 탑승 다음 열차를 붙인다.

카카오 샘플은 2026-11-12 본인 키로 받은 실제 응답(홍대입구 → 강남 등)이다.
"""

from datetime import datetime

import httpx
import pytest

from app.clients.endpoints import KAKAO_ROUTE_URL, SEOUL_OPENAPI_BASE, SEOUL_REALTIME_BASE
from app.core.clock import KST
from tests.helpers import fixture_response, kakao_error_response, load_fixture

ROUTES = "/v1/routes"
TRIP = {"from": "홍대입구", "to": "강남"}
ROUTE_KEYS = {"id", "type", "totalSec", "distanceM", "transfers", "fare", "legs", "firstBoarding"}


@pytest.fixture(autouse=True)
def upstream_defaults(respx_mock, clock):
    """홍대입구 실시간은 실제 샘플, 시간표는 데이터 없음. 시계는 홍대입구 샘플을 받은 직후."""
    first = load_fixture("seoul_realtime_hongdae.json")[1]["realtimeArrivalList"][0]["recptnDt"]
    generated = datetime.strptime(first, "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
    clock.set(generated.timestamp() + 10)
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE, name="seoul_realtime").mock(
        return_value=fixture_response("seoul_realtime_hongdae.json")
    )
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE, name="seoul_timetable").mock(
        return_value=fixture_response("seoul_timetable_nodata.json")
    )


def only_bus(body: dict) -> None:
    body["routes"] = [
        r for r in body["routes"] if all(s["properties"]["type"] != "SUBWAY" for s in r["steps"])
    ]


async def test_routes_by_station_ids(client, kakao_route):
    kakao_route("kakao_route_subway_only.json")
    r = await client.get(ROUTES, params=TRIP)
    body = r.json()
    assert r.status_code == 200 and 1 <= len(body["routes"]) <= 5
    assert body["updatedAt"].endswith("+09:00") and body["stale"] is False
    routes = body["routes"]
    keys = [(rt["totalSec"], rt["transfers"]) for rt in routes]
    assert keys == sorted(keys)
    assert all(rt["type"] in ("SUBWAY", "WALK") for rt in routes)
    assert ROUTE_KEYS <= set(routes[0])
    first = routes[0]["firstBoarding"]
    assert first["stationId"] == "홍대입구" and first["lineName"] == "2호선"
    assert first["nextTrains"] and len(first["nextTrains"]) <= 2


async def test_kakao_gets_station_coordinates_and_names(client, kakao_route, real_index):
    route = kakao_route("kakao_route_subway_only.json")
    await client.get(ROUTES, params=TRIP)
    p = route.calls.last.request.url.params
    start, end = real_index.get("홍대입구"), real_index.get("강남")
    assert (float(p["start_x"]), float(p["start_y"])) == (start.lng, start.lat)
    assert (float(p["end_x"]), float(p["end_y"])) == (end.lng, end.lat)
    assert (p["s_name"], p["e_name"]) == (start.name, end.name)


async def test_same_station_rejected(client, respx_mock):
    kakao = respx_mock.get(KAKAO_ROUTE_URL)
    r = await client.get(ROUTES, params={"from": "홍대입구", "to": "홍대입구"})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "SAME_STATION"
    assert r.json()["error"]["message"] == "출발역과 도착역이 같아요."
    assert not kakao.called


async def test_bus_only_is_no_subway(client, respx_mock):
    kakao = respx_mock.get(KAKAO_ROUTE_URL).mock(
        return_value=fixture_response("kakao_route_subway_only.json", only_bus)
    )
    for _ in range(2):
        r = await client.get(ROUTES, params=TRIP)
        assert r.status_code == 404 and r.json()["error"]["reason"] == "NO_SUBWAY"
        assert r.json()["error"]["message"] == "지하철로 갈 수 있는 경로가 없어요."
    assert kakao.call_count == 1  # 결과 없음도 캐시한다


@pytest.mark.parametrize("params", [{"from": "없는역", "to": "강남"}, {"from": "홍대입구"}])
async def test_unknown_or_missing_station(client, params):
    r = await client.get(ROUTES, params=params)
    assert r.status_code in (400, 404)
    if "to" in params:
        assert r.json()["error"]["code"] == "NOT_FOUND"


@pytest.mark.parametrize(
    "kakao_code, status, code, reason",
    [
        ("STARTNODES_NULL", 404, "NO_ROUTE", "NO_START_STOP"),
        ("ENDNODES_NULL", 404, "NO_ROUTE", "NO_END_STOP"),
        ("NO_RESULTS", 404, "NO_ROUTE", "NO_RESULTS"),
        ("INVALID_REQUEST", 502, "UPSTREAM_ERROR", "INVALID_REQUEST"),
    ],
)
async def test_kakao_error_codes(client, respx_mock, kakao_code, status, code, reason):
    respx_mock.get(KAKAO_ROUTE_URL).mock(return_value=kakao_error_response(kakao_code))
    r = await client.get(ROUTES, params=TRIP)
    assert r.status_code == status and r.json()["error"]["code"] == code
    assert r.json()["error"]["reason"] == reason


async def test_cached_for_10_minutes(client, kakao_route, clock):
    route = kakao_route("kakao_route_subway_only.json")
    await client.get(ROUTES, params=TRIP)
    clock.advance(599)
    await client.get(ROUTES, params=TRIP)
    assert route.call_count == 1
    clock.advance(1)
    await client.get(ROUTES, params=TRIP)
    assert route.call_count == 2


async def test_next_trains_fail_soft(client, kakao_route, respx_mock):
    """실시간·시간표가 모두 실패해도 경로는 준다. 다음 열차만 비운다."""
    kakao_route("kakao_route_subway_only.json")
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE, name="seoul_realtime").mock(
        return_value=httpx.Response(500)
    )
    r = await client.get(ROUTES, params=TRIP)
    assert r.status_code == 200 and r.json()["routes"][0]["firstBoarding"]["nextTrains"] is None


async def test_stale_route_when_kakao_down(client, respx_mock, clock):
    respx_mock.get(KAKAO_ROUTE_URL).mock(
        side_effect=[
            fixture_response("kakao_route_subway_only.json"),
            httpx.Response(500),
            httpx.Response(500),
        ]
    )
    await client.get(ROUTES, params=TRIP)
    clock.advance(601)
    r = await client.get(ROUTES, params=TRIP)
    assert r.status_code == 200 and r.json()["stale"] is True
