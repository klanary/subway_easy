"""GET /v1/routes — 운행표 RAPTOR로 직접 찾은 지하철 경로(경로 설계 2026-11-18).

테스트 시계는 2026-11-03(화) 08:00. 운행표는 실제 역 id로 만든 작은 것이다.
"""

import pytest

from app.clients.endpoints import SEOUL_OPENAPI_BASE, SEOUL_REALTIME_BASE
from app.services.trips import TripsData
from tests.helpers import fixture_response

URL = "/v1/routes"
STATIONS = ["마포", "여의나루", "여의도", "샛강", "노량진"]


def hm(h: int, m: int) -> int:
    return h * 3600 + m * 60


def at8(*minutes: int) -> list[int]:
    """8시 m분에 서고 바로 떠나는 정차들의 [도착, 출발, ...]."""
    return [x for m in minutes for x in (hm(8, m), hm(8, m))]


def trips_artifact() -> dict:
    five = {"line": "5호선", "direction": "하행", "stops": [0, 1, 2], "trips": [
        ["5301", 0, "마천", 0, 0, at8(5, 7, 9)],
        ["5303", 0, "마천", 0, 0, at8(15, 17, 19)],
    ]}  # fmt: skip
    nine = {"line": "9호선", "direction": "하행", "stops": [2, 3, 4], "trips": [
        ["9101", 0, "중앙보훈병원", 0, 0, at8(10, 12, 14)],
        ["9103", 0, "중앙보훈병원", 0, 0, at8(20, 22, 24)],
    ]}  # fmt: skip
    return {"kind": "trips-server", "version": 1, "built_on": "2026-11-03", "stations": STATIONS,
            "day_types": {"1": [five, nine], "2": [], "3": []}}  # fmt: skip


@pytest.fixture
def trips_loaded(services):
    services.measured.replace_trips(TripsData.from_artifact(trips_artifact()))


@pytest.fixture(autouse=True)
def upstream_defaults(respx_mock):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        return_value=fixture_response("seoul_realtime_nodata.json")
    )
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=fixture_response("seoul_timetable_nodata.json")
    )


def steps(route: dict) -> list[dict]:
    return [s for leg in route["legs"] for s in leg["steps"]]


async def get(client, **params):
    return await client.get(URL, params=params)


async def test_route_ends_at_destination(client, trips_loaded):
    body = (await get(client, **{"from": "마포", "to": "노량진"})).json()
    route = body["routes"][0]
    assert steps(route)[-1]["alightStationId"] == "노량진"
    assert [s["lineName"] for s in steps(route)] == ["5호선", "9호선"]
    assert route["transfers"] == 1


async def test_no_external_calls_with_depart_at(client, trips_loaded, respx_mock):
    params = {"from": "마포", "to": "노량진", "departAt": "2026-11-03T08:00:00+09:00"}
    r = await get(client, **params)
    assert r.status_code == 200 and not respx_mock.calls


async def test_fare_is_null_and_steps_are_subway_only(client, trips_loaded):
    route = (await get(client, **{"from": "마포", "to": "노량진"})).json()["routes"][0]
    assert route["fare"] is None and route["type"] == "SUBWAY"
    assert {s["mode"] for s in steps(route)} == {"SUBWAY"}


async def test_steps_carry_train_times(client, trips_loaded):
    params = {"from": "마포", "to": "노량진", "departAt": "2026-11-03T08:00:00+09:00"}
    route = (await get(client, **params)).json()["routes"][0]
    first, second = steps(route)
    assert first["trainNo"] == "5301" and first["departsAt"][11:16] == "08:05"
    assert first["arrivesAt"][11:16] == "08:09" and first["stops"] == ["마포", "여의나루", "여의도"]
    assert second["trainNo"] == "9101" and second["departsAt"][11:16] == "08:10"
    assert route["departAt"][11:16] == "08:05" and route["arriveAt"][11:16] == "08:14"
    assert route["totalSec"] == 9 * 60
    assert first["source"] == "official" and first["estimated"] is False
    assert route["firstBoarding"]["stationId"] == "마포"
    assert route["firstBoarding"]["towards"] == "여의나루"


async def test_via_route(client, trips_loaded):
    params = {"from": "마포", "to": "노량진", "via": "여의도"}
    route = (await get(client, **params)).json()["routes"][0]
    assert len(route["legs"]) == 2
    assert route["legs"][0]["steps"][-1]["alightStationId"] == "여의도"


async def test_non_seoul_station_is_no_route(client, trips_loaded):
    r = await get(client, **{"from": "마포", "to": "가재울"})
    assert r.status_code == 404 and r.json()["error"]["code"] == "NO_ROUTE"


async def test_routes_503_before_trips_loaded(client):
    r = await get(client, **{"from": "마포", "to": "노량진"})
    assert r.status_code == 503 and r.json()["error"]["code"] == "ROUTES_NOT_READY"
    assert "준비 중" in r.json()["error"]["message"]


async def test_same_station_is_400(client, trips_loaded):
    r = await get(client, **{"from": "마포", "to": "마포"})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "SAME_STATION"


async def test_unknown_station_is_404(client, trips_loaded):
    assert (await get(client, **{"from": "마포", "to": "없는역"})).status_code == 404


async def test_bad_depart_at(client, trips_loaded):
    r = await get(client, **{"from": "마포", "to": "노량진", "departAt": "어제"})
    assert r.status_code == 400


async def test_next_trains_from_timetable_with_depart_at(client, trips_loaded):
    # 루틴 미리 보기가 이 목록("HH:MM 출발")을 쓴다(최종 리뷰 #2).
    params = {"from": "마포", "to": "노량진", "departAt": "2026-11-03T08:00:00+09:00"}
    boarding = (await get(client, **params)).json()["routes"][0]["firstBoarding"]
    assert [t["message"] for t in boarding["nextTrains"]] == ["08:05 출발", "08:15 출발"]
    assert boarding["nextTrains"][0]["trainNo"] == "5301"


async def test_now_route_has_next_trains_without_external_calls(client, trips_loaded, respx_mock):
    body = (await get(client, **{"from": "마포", "to": "노량진"})).json()
    trains = body["routes"][0]["firstBoarding"]["nextTrains"]
    assert [t["remainingSec"] for t in trains] == [300, 900]  # 시계 08:00 기준
    assert not respx_mock.calls


async def test_route_id_is_stable_across_departure_times(client, trips_loaded):
    # 루틴 편집은 같은 경로를 id로 다시 찾는다. 출발 시각이 달라도 같은 길이면 id가 같아야 한다.
    def ids(body):
        return [r["id"] for r in body["routes"]]

    early = await get(
        client, **{"from": "마포", "to": "노량진", "departAt": "2026-11-03T08:00:00+09:00"}
    )
    late = await get(
        client, **{"from": "마포", "to": "노량진", "departAt": "2026-11-03T08:12:00+09:00"}
    )
    assert ids(early.json())[0] == ids(late.json())[0]


async def test_search_runs_off_the_event_loop(client, trips_loaded, monkeypatch):
    # 경유 경로는 검색을 여러 번 한다. 요청 처리를 막지 않게 다른 스레드에서 돌린다.
    import threading

    from app.services import route_service

    seen: list[int] = []
    original = route_service.search

    def spy(*args, **kwargs):
        seen.append(threading.get_ident())
        return original(*args, **kwargs)

    monkeypatch.setattr(route_service, "search", spy)
    await get(client, **{"from": "마포", "to": "노량진"})
    assert seen and all(t != threading.get_ident() for t in seen)
