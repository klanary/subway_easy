"""[시각 선택] 60분 안(설계 2026-11-22 §7): 실시간 → 위치 계산 → 시간표 순으로 4대, 중복 없이."""

import httpx
import pytest

from app.clients.endpoints import SEOUL_OPENAPI_BASE
from app.main import create_app
from tests.helpers import fixture_response
from tests.position_helpers import (
    answer_arrivals,
    answer_positions,
    arrival,
    install_position_data,
    position_row,
    timetable_body,
)

URL = "/v1/stations/시청/arrivals"


def make_app(test_settings, clock, at_lines):
    app = create_app(test_settings.model_copy(update={"position_at_lines": at_lines}), clock=clock)
    install_position_data(app.state.services)
    return app


@pytest.fixture
async def at_client(test_settings, clock):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=make_app(test_settings, clock, ["1호선"])),
        base_url="http://test",
    ) as c:
        yield c


@pytest.fixture
async def off_client(test_settings, clock):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=make_app(test_settings, clock, [])),
        base_url="http://test",
    ) as c:
        yield c


def timetable(respx_mock, times):
    return respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=httpx.Response(200, json=timetable_body(times))
    )


def up_trains(body):
    group = next(g for g in body["groups"] if (g["line"], g["direction"]) == ("1호선", "상행"))
    return group["trains"]


async def test_at_mode_mixes_position_then_timetable(at_client, respx_mock):
    answer_arrivals(respx_mock, [arrival()])  # barvlDt 0: 실시간 칸에는 안 들어간다
    # 서울 도착 10초 전(+100초 = 08:01:30), 남영 도착 10초 전(+400초 = 08:06:30)
    answer_positions(respx_mock, [position_row("0145"), position_row("0147", "남영")])
    timetable(respx_mock, ["08:03:00", "08:07:00", "08:10:00", "08:15:00", "08:20:00"])
    trains = up_trains(
        (await at_client.get(URL, params={"at": "2026-11-03T08:01:00+09:00"})).json()
    )
    assert [t["basis"] for t in trains] == ["position", "position", "timetable", "timetable"]
    assert [t["message"] for t in trains] == [
        "08:01 도착 예정", "08:06 도착 예정", "08:10 출발", "08:15 출발",
    ]  # fmt: skip


async def test_at_mode_no_duplicates(at_client, respx_mock):
    answer_arrivals(respx_mock, [arrival(barvl="300", msg="5분 후", no="0145")])
    answer_positions(respx_mock, [position_row("0145")])
    timetable(respx_mock, [])
    trains = up_trains(
        (await at_client.get(URL, params={"at": "2026-11-03T08:01:00+09:00"})).json()
    )
    assert [(t["trainNo"], t["basis"]) for t in trains] == [("0145", "realtime")]


async def test_at_line_not_enabled_is_timetable_only(off_client, respx_mock):
    answer_arrivals(respx_mock, [arrival()])
    route = answer_positions(respx_mock, [position_row()])
    timetable(respx_mock, ["08:10:00", "08:15:00"])
    trains = up_trains(
        (await off_client.get(URL, params={"at": "2026-11-03T08:05:00+09:00"})).json()
    )
    assert {t["basis"] for t in trains} == {"timetable"}
    assert route.call_count == 0


async def test_at_beyond_60_minutes_is_timetable_only(at_client, respx_mock):
    answer_arrivals(respx_mock, [arrival()])
    route = answer_positions(respx_mock, [position_row()])
    timetable(respx_mock, ["09:05:00", "09:10:00"])
    trains = up_trains(
        (await at_client.get(URL, params={"at": "2026-11-03T09:01:00+09:00"})).json()
    )
    # 4대에 모자라면 시간표 서비스가 다음 날로 채운다(같은 가짜 응답이라 시각이 되풀이된다)
    assert [t["message"] for t in trains][:2] == ["09:05 출발", "09:10 출발"]
    assert {t["basis"] for t in trains} == {"timetable"}
    assert route.call_count == 0


async def test_at_in_past_means_now(at_client, respx_mock):
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=fixture_response("seoul_timetable_nodata.json")
    )
    answer_arrivals(respx_mock, [arrival(barvl="200", msg="3분 후")])
    answer_positions(respx_mock, [])
    now = (await at_client.get(URL)).json()
    past = (await at_client.get(URL, params={"at": "2026-11-03T07:55:00+09:00"})).json()
    assert up_trains(past) == up_trains(now)


async def test_at_mode_survives_realtime_outage(at_client, respx_mock):
    """실시간 도착 API가 죽어도 [시각 선택]은 시간표로 답한다(최종 리뷰 Important 1)."""
    respx_mock.get(url__regex=r".*/realtimeStationArrival/.*").mock(
        return_value=httpx.Response(500)
    )
    answer_positions(respx_mock, [])
    timetable(respx_mock, ["08:10:00", "08:15:00"])
    r = await at_client.get(URL, params={"at": "2026-11-03T08:05:00+09:00"})
    assert r.status_code == 200
    assert [t["message"] for t in up_trains(r.json())][:2] == ["08:10 출발", "08:15 출발"]


async def test_timetable_train_between_live_trains_is_kept(at_client, respx_mock):
    """위치 열차와 90초 넘게 떨어진 시간표 열차는 그 사이에 있어도 남긴다(최종 리뷰 Important 2)."""
    answer_arrivals(respx_mock, [arrival()])
    answer_positions(respx_mock, [position_row("0145"), position_row("0147", "남영")])
    timetable(respx_mock, ["08:03:40", "08:06:50", "08:12:00"])
    trains = up_trains(
        (await at_client.get(URL, params={"at": "2026-11-03T08:01:00+09:00"})).json()
    )
    assert [t["basis"] for t in trains] == ["position", "timetable", "position", "timetable"]


async def test_realtime_train_before_at_not_repeated_by_position(at_client, respx_mock):
    """고른 시각 전에 오는 실시간 열차가 위치 계산으로 다시 나오지 않는다(최종 리뷰 사소한 것)."""
    answer_arrivals(respx_mock, [arrival(barvl="30", msg="곧 도착", no="0145")])
    row = position_row("0145")
    row["trainSttus"] = "3"  # 전역출발로 잡혀 위치 계산은 08:03 무렵이 된다
    answer_positions(respx_mock, [row])
    timetable(respx_mock, [])
    body = (await at_client.get(URL, params={"at": "2026-11-03T08:01:00+09:00"})).json()
    assert all(t["trainNo"] != "0145" for g in body["groups"] for t in g["trains"])
