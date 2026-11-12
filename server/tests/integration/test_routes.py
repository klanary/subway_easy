import httpx
import pytest

from app.clients.endpoints import KAKAO_ROUTE_URL
from tests.helpers import fixture_response, kakao_error_response

HONGDAE, GANGNAM = "37.5571,126.9245", "37.4979,127.0276"
ROUTE_KEYS = {"id", "type", "totalSec", "distanceM", "transfers", "fare", "legs", "firstBoarding"}


async def test_routes_success(client, kakao_route):
    kakao_route("kakao_route_transfer.json")
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    body = r.json()
    assert r.status_code == 200 and 1 <= len(body["routes"]) <= 5
    assert body["updatedAt"].endswith("+09:00") and body["stale"] is False
    assert all(len(rt["legs"]) == 1 for rt in body["routes"])
    first = body["routes"][0]
    assert ROUTE_KEYS <= set(first)
    assert first["firstBoarding"]["stationId"] == "홍대입구"


async def test_kakao_gets_x_as_longitude(client, kakao_route):
    route = kakao_route("kakao_route_subway_only.json")
    params = {"from": HONGDAE, "to": GANGNAM, "fromName": "출발지"}
    await client.get("/v1/routes", params=params)
    p = route.calls.last.request.url.params
    got = (p["start_x"], p["start_y"], p["end_x"], p["end_y"], p["s_name"])
    assert got == ("126.9245", "37.5571", "127.0276", "37.4979", "출발지")


async def test_equal_points_rejected_without_calling_kakao(client, respx_mock):
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": "37.55712,126.92451"})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "EQUAL_POINTS"
    assert not respx_mock.calls


@pytest.mark.parametrize(
    "kakao_code, status, code, reason",
    [
        ("EQUAL_POINTS", 400, "INVALID_REQUEST", "EQUAL_POINTS"),
        ("STARTNODES_NULL", 404, "NO_ROUTE", "NO_START_STOP"),
        ("ENDNODES_NULL", 404, "NO_ROUTE", "NO_END_STOP"),
        ("NO_RESULTS", 404, "NO_ROUTE", "NO_RESULTS"),
        ("INVALID_REQUEST", 502, "UPSTREAM_ERROR", "INVALID_REQUEST"),
    ],
)
async def test_kakao_error_codes(client, respx_mock, kakao_code, status, code, reason):
    respx_mock.get(KAKAO_ROUTE_URL).mock(return_value=kakao_error_response(kakao_code))
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert r.status_code == status and r.json()["error"]["code"] == code
    assert r.json()["error"]["reason"] == reason


async def test_real_no_results_sample(client, kakao_route):
    kakao_route("kakao_route_error_far.json")
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": "33.5070,126.4929"})
    assert r.status_code == 404 and r.json()["error"]["reason"] == "NO_RESULTS"


async def test_cache_key_rounds_to_4_decimals_and_lasts_10_minutes(client, kakao_route, clock):
    route = kakao_route("kakao_route_subway_only.json")
    for origin in ["37.55711,126.92451", "37.55714,126.92449"]:
        await client.get("/v1/routes", params={"from": origin, "to": GANGNAM})
    clock.advance(599)
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert route.call_count == 1
    clock.advance(1)
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert route.call_count == 2


@pytest.mark.parametrize("bad", ["abc", "37.5", "91,127", "37.5,181", ""])
async def test_bad_coordinates(client, bad):
    r = await client.get("/v1/routes", params={"from": bad, "to": GANGNAM})
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"


async def test_stale_route_when_kakao_down(client, respx_mock, clock):
    respx_mock.get(KAKAO_ROUTE_URL).mock(
        side_effect=[
            fixture_response("kakao_route_transfer.json"),
            httpx.Response(500),
            httpx.Response(500),
        ]
    )
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    clock.advance(601)
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert r.status_code == 200 and r.json()["stale"] is True
