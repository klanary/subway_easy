"""GET /v1/routes(재설계 스펙 5.3).

역 id로 묻고, 지하철 경로만 빠른 순으로, 첫 탑승 다음 열차를 붙인다.

카카오 샘플은 2026-11-12 본인 키로 받은 실제 응답(홍대입구 → 강남 등)이다.
"""

from datetime import datetime, timedelta

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


# --- 경유역과 출발 시각 지정(재설계 스펙 5.3) ---


async def test_via_calls_kakao_per_leg_and_caches(client, kakao_route):
    route = kakao_route("kakao_route_subway_only.json")
    params = {**TRIP, "via": "시청"}
    for _ in range(2):
        r = await client.get(ROUTES, params=params)
        body = r.json()
        assert r.status_code == 200 and 1 <= len(body["routes"]) <= 5
        assert all(len(rt["legs"]) == 2 for rt in body["routes"])
    assert route.call_count == 2  # 출발→경유, 경유→도착 각 1번, 두 번째 요청은 캐시
    names = [(c.request.url.params["s_name"], c.request.url.params["e_name"]) for c in route.calls]
    assert names == [("홍대입구", "시청"), ("시청", "강남")]


@pytest.mark.parametrize("via", ["홍대입구", "강남"])
async def test_via_equal_to_end_rejected(client, via):
    r = await client.get(ROUTES, params={**TRIP, "via": via})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "SAME_STATION"


async def test_depart_at_uses_timetable(client, kakao_route, respx_mock):
    kakao_route("kakao_route_subway_only.json")
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE, name="seoul_timetable").mock(
        return_value=fixture_response("seoul_timetable_yeongdeungpo_weekday_up.json")
    )
    depart = datetime(2026, 11, 13, 8, 0, tzinfo=KST)
    r = await client.get(ROUTES, params={**TRIP, "departAt": depart.isoformat()})
    scheduled = [rt for rt in r.json()["routes"] if rt["departAt"]]
    assert scheduled
    for rt in scheduled:
        fb = rt["firstBoarding"]
        leave = datetime.fromisoformat(rt["departAt"])
        arrive = datetime.fromisoformat(rt["arriveAt"])
        assert leave.timestamp() >= depart.timestamp() + fb["secToBoarding"]
        assert arrive.timestamp() == leave.timestamp() + rt["totalSec"] - fb["secToBoarding"]
        assert fb["nextTrains"] and fb["nextTrains"][0]["message"].endswith("출발")


async def test_without_depart_at_fields_are_null(client, kakao_route):
    kakao_route("kakao_route_subway_only.json")
    rt = (await client.get(ROUTES, params=TRIP)).json()["routes"][0]
    assert rt["departAt"] is None and rt["arriveAt"] is None


async def test_bad_depart_at(client):
    r = await client.get(ROUTES, params={**TRIP, "departAt": "내일"})
    assert r.status_code == 400


# --- 환승역 열차: 걸어서 탈 수 있는 빠른 열차와 그다음 열차(2026-11-12 사용자 요청) ---

GYEONGBOKGUNG = {"from": "홍대입구", "to": "경복궁"}


def transfer_steps(route: dict) -> list[dict]:
    subway = [s for leg in route["legs"] for s in leg["steps"] if s["mode"] == "SUBWAY"]
    return subway[1:]


async def test_transfer_trains_with_depart_at(client, kakao_route, respx_mock):
    kakao_route("kakao_route_transfer.json")
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE, name="seoul_timetable").mock(
        return_value=fixture_response("seoul_timetable_yeongdeungpo_weekday_up.json")
    )
    depart = datetime(2026, 11, 13, 8, 0, tzinfo=KST)
    r = await client.get(ROUTES, params={**GYEONGBOKGUNG, "departAt": depart.isoformat()})
    route = r.json()["routes"][0]
    step = transfer_steps(route)[0]  # 을지로3가 3호선
    trains = step["transferTrains"]
    assert len(trains) == 2
    ready = datetime.fromisoformat(step["boardAfter"])
    # 첫 열차 출발 + 2호선 탑승 810초. 환승 걷기는 더하지 않는다(사용자 요청).
    assert ready == datetime.fromisoformat(route["departAt"]) + timedelta(seconds=810)
    first, second = (datetime.fromisoformat(t["departsAt"]) for t in trains)
    assert ready <= first < second
    assert {t["source"] for t in trains} == {"timetable"}


async def test_transfer_trains_now_fall_back_to_timetable(client, kakao_route, respx_mock):
    kakao_route("kakao_route_transfer.json")
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE, name="seoul_timetable").mock(
        return_value=fixture_response("seoul_timetable_yeongdeungpo_weekday_up.json")
    )
    route = (await client.get(ROUTES, params=GYEONGBOKGUNG)).json()["routes"][0]
    step = transfer_steps(route)[0]
    assert step["boardAfter"] and 1 <= len(step["transferTrains"]) <= 2
    assert datetime.fromisoformat(step["transferTrains"][0]["departsAt"]) >= datetime.fromisoformat(
        step["boardAfter"]
    )


async def test_no_transfer_trains_without_first_train(client, kakao_route, respx_mock):
    kakao_route("kakao_route_transfer.json")
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE, name="seoul_realtime").mock(
        return_value=httpx.Response(500)
    )
    route = (await client.get(ROUTES, params=GYEONGBOKGUNG)).json()["routes"][0]
    assert all(s.get("transferTrains") is None for s in transfer_steps(route))
