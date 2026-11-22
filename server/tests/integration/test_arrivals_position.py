"""[지금]에 열차 위치 계산 합치기(설계 2026-11-22 §7): 켠 노선의 barvlDt 0 열차만 바꾼다."""

from datetime import datetime, timedelta

import httpx
import pytest

from app.clients.endpoints import SEOUL_OPENAPI_BASE
from app.core.clock import KST
from app.main import create_app
from app.services.measured import MeasuredData
from app.services.trips import TripsData
from tests.helpers import fixture_response

NOW = datetime(2026, 11, 3, 8, 0, 0, tzinfo=KST)  # conftest 시계(화요일)
STAMP = "%Y-%m-%d %H:%M:%S"


@pytest.fixture
async def pos_client(test_settings, clock):
    app = create_app(
        test_settings.model_copy(update={"position_now_lines": ["1호선"]}), clock=clock
    )
    services = app.state.services
    services.measured.replace(
        MeasuredData.from_artifacts(
            {
                "kind": "b1-server", "version": 1, "as_of": "2026-11-02", "built_at": "",
                "modes": {}, "dests": [], "cells": {}, "scopes": {},
                "stations": {
                    "1호선|S1": {"station_id": "서울", "name": "서울"},
                    "1호선|S2": {"station_id": "시청", "name": "시청"},
                },
            },
            {"kind": "b2-server", "version": 1, "hops": {"1호선|0|S1|S2|0": 100}},
        )
    )  # fmt: skip
    services.measured.replace_trips(
        TripsData.from_artifact(
            {
                "kind": "trips-server", "version": 1, "built_on": "2026-11-02",
                "stations": ["서울", "시청", "종각"],
                "day_types": {"1": [{"line": "1호선", "direction": "상행", "stops": [0, 1, 2],
                                     "trips": [["0145", False, "광운대", 0, False, [0] * 6]]}]},
            }
        )
    )  # fmt: skip
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


@pytest.fixture(autouse=True)
def no_timetable(respx_mock):
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=fixture_response("seoul_timetable_nodata.json")
    )


def arrival(subway="1001", updn="상행", barvl="0", msg="[1]번째 전역 (서울)", no="0145"):
    return {
        "subwayId": subway, "updnLine": updn, "trainLineNm": "광운대행 - 종각방면",
        "statnNm": "시청", "btrainNo": no, "bstatnNm": "광운대",
        "recptnDt": (NOW - timedelta(seconds=5)).strftime(STAMP),
        "arvlMsg2": msg, "arvlMsg3": "서울", "barvlDt": barvl,
        "lstcarAt": "0", "btrainSttus": "일반",
    }  # fmt: skip


def answer_arrivals(respx_mock, items):
    body = {"errorMessage": {"status": 200, "code": "INFO-000"}, "realtimeArrivalList": items}
    return respx_mock.get(url__regex=r".*/realtimeStationArrival/.*").mock(
        return_value=httpx.Response(200, json=body)
    )


def answer_positions(respx_mock, rows=None, *, status=200, code="INFO-000"):
    if status != 200:
        response = httpx.Response(status)
    elif code == "INFO-200":
        response = httpx.Response(200, json={"status": 500, "code": "INFO-200"})
    else:
        body = {"errorMessage": {"status": 200, "code": code}, "realtimePositionList": rows or []}
        response = httpx.Response(200, json=body)
    return respx_mock.get(url__regex=r".*/realtimePosition/.*").mock(return_value=response)


def position_row(no="0145"):
    return {
        "statnNm": "서울", "trainNo": no, "updnLine": "0", "trainSttus": "1", "directAt": "0",
        "statnTnm": "광운대", "recptnDt": (NOW - timedelta(seconds=10)).strftime(STAMP),
    }  # fmt: skip


def first_train(body, line, direction):
    group = next(g for g in body["groups"] if (g["line"], g["direction"]) == (line, direction))
    return group["trains"][0]


async def test_zero_barvl_train_uses_position(pos_client, respx_mock):
    answer_arrivals(respx_mock, [arrival()])
    answer_positions(respx_mock, [position_row()])
    train = first_train(
        (await pos_client.get("/v1/stations/시청/arrivals")).json(), "1호선", "상행"
    )
    assert train["basis"] == "position"
    assert train["remainingSec"] == 90  # 10초 전 서울 도착 + 서울→시청 100초


async def test_exact_seconds_kept(pos_client, respx_mock):
    answer_arrivals(respx_mock, [arrival(barvl="200", msg="3분 후")])
    answer_positions(respx_mock, [position_row()])
    train = first_train(
        (await pos_client.get("/v1/stations/시청/arrivals")).json(), "1호선", "상행"
    )
    assert train["basis"] == "realtime" and train["remainingSec"] == 195


async def test_line_not_enabled_unchanged(pos_client, respx_mock):
    item = arrival(subway="1002", updn="내선", msg="[2]번째 전역 (충정로)")
    item["trainLineNm"], item["bstatnNm"] = "성수행 - 을지로입구방면", "성수"
    answer_arrivals(respx_mock, [item])
    route = answer_positions(respx_mock, [position_row()])
    body = (await pos_client.get("/v1/stations/시청/arrivals")).json()
    assert first_train(body, "2호선", "내선")["basis"] == "realtime"
    assert route.call_count == 0


@pytest.mark.parametrize("outage", [{"code": "INFO-200"}, {"status": 500}])
async def test_position_outage_keeps_today_behavior(pos_client, respx_mock, outage):
    answer_arrivals(respx_mock, [arrival()])
    answer_positions(respx_mock, **outage)
    train = first_train(
        (await pos_client.get("/v1/stations/시청/arrivals")).json(), "1호선", "상행"
    )
    assert train["basis"] == "realtime"
    assert train["remainingSec"] == 120  # 지금의 문구 어림([1]번째 전역 × 120초)
