"""서울시 실시간 도착 API의 알려진 함정(개발자 댓글 1년치 요약, 2026-11-22 실제 응답으로 확인).

- 같은 역도 노선마다 조회 이름이 다르다:
  서울역 GTX-A는 "서울역", 신촌 경의중앙선은 "신촌(경의중앙선)".
- 서울역·신촌 경의중앙선 응답에 그 역을 지나지 않는 본선 열차(팔당·지평행 등)가 섞여 온다.
- 조회한 역이 아닌 역의 행이 섞여 올 수 있다(인천 예술회관에서 김포공항·신창 열차).
- 이 역에서 끝나는 열차(서울역 공항철도 "서울행")는 탈 수 없다.
- 같은 열차가 두 행(도착·출발 등)으로 올 수 있다.
"""

import re
from datetime import datetime, timedelta
from urllib.parse import quote

import httpx
import pytest

from app.clients.endpoints import SEOUL_OPENAPI_BASE, SEOUL_REALTIME_BASE
from app.core.clock import KST
from tests.helpers import fixture_response

ARRIVALS = "/v1/stations/{}/arrivals"
NOW = datetime(2026, 11, 22, 11, 30, 0, tzinfo=KST)


@pytest.fixture(autouse=True)
def at_now(clock):
    clock.set(NOW.timestamp())


@pytest.fixture(autouse=True)
def no_timetable(respx_mock):
    respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=fixture_response("seoul_timetable_nodata.json")
    )


def item(subway_id, updn, dest, statn, msg="3분 후", no="1234", ago=10):
    return {
        "subwayId": subway_id,
        "updnLine": updn,
        "trainLineNm": f"{dest}행 - 다음방면",
        "statnNm": statn,
        "btrainNo": no,
        "bstatnNm": dest,
        "recptnDt": (NOW - timedelta(seconds=ago)).strftime("%Y-%m-%d %H:%M:%S"),
        "arvlMsg2": msg,
        "arvlMsg3": "어딘가",
        "barvlDt": "0",
        "lstcarAt": "0",
        "btrainSttus": "일반",
    }


def answer(respx_mock, name, items):
    body = {
        "errorMessage": {"status": 200, "code": "INFO-000", "total": len(items)},
        "realtimeArrivalList": items,
    }
    return respx_mock.get(
        url__regex=re.escape(SEOUL_REALTIME_BASE) + ".*/" + re.escape(quote(name)) + "$"
    ).mock(return_value=httpx.Response(200, json=body))


def trains(body, line, direction):
    return [
        t["destination"]
        for g in body["groups"]
        if (g["line"], g["direction"], g["source"]) == (line, direction, "realtime")
        for t in g["trains"]
    ]


async def test_seoul_station_also_asks_gtx_name(client, respx_mock):
    answer(respx_mock, "서울", [item("1001", "상행", "광운대", "서울")])
    gtx = answer(respx_mock, "서울역", [item("1032", "상행", "운정중앙", "서울역", no="G1")])
    body = (await client.get(ARRIVALS.format("서울"))).json()
    assert gtx.called
    assert trains(body, "GTX-A", "상행") == ["운정중앙"]
    assert trains(body, "1호선", "상행") == ["광운대"]


async def test_sinchon_gyeongui_uses_own_name(client, respx_mock):
    answer(respx_mock, "신촌", [item("1002", "내선", "성수", "신촌")])
    answer(
        respx_mock,
        "신촌(경의중앙선)",
        [item("1063", "상행", "문산", "신촌(경의중앙선)", no="K1")],
    )
    body = (await client.get(ARRIVALS.format("신촌"))).json()
    assert trains(body, "경의중앙선", "상행") == ["문산"]
    assert trains(body, "2호선", "내선") == ["성수"]


async def test_mainline_trains_dropped_at_seoul_and_sinchon(client, respx_mock):
    """관리자 안내: 1063의 서울역·신촌에서 덕소·팔당·용문·지평·청량리·용산행은 오지 않는 열차다."""
    answer(
        respx_mock,
        "서울",
        [
            item("1063", "하행", "팔당", "서울", no="K2"),
            item("1063", "하행", "지평", "서울", no="K3"),
            item("1063", "상행", "문산", "서울", no="K4"),
        ],
    )
    answer(respx_mock, "서울역", [])
    body = (await client.get(ARRIVALS.format("서울"))).json()
    assert trains(body, "경의중앙선", "하행") == []
    assert trains(body, "경의중앙선", "상행") == ["문산"]


async def test_rows_of_another_station_dropped(client, respx_mock):
    answer(
        respx_mock,
        "시청",
        [
            item("1001", "하행", "인천", "시청", no="A1"),
            item("1001", "하행", "신창", "김포공항", no="A2"),
        ],
    )
    body = (await client.get(ARRIVALS.format("시청"))).json()
    assert trains(body, "1호선", "하행") == ["인천"]


async def test_trains_ending_here_dropped(client, respx_mock):
    answer(
        respx_mock,
        "서울",
        [
            item("1065", "하행", "서울", "서울", msg="전역 출발", no="B1"),
            item("1065", "상행", "인천공항2터미널", "서울", no="B2"),
        ],
    )
    answer(respx_mock, "서울역", [])
    body = (await client.get(ARRIVALS.format("서울"))).json()
    assert trains(body, "공항철도", "하행") == []
    assert trains(body, "공항철도", "상행") == ["인천공항2터미널"]


async def test_same_train_twice_shown_once_latest(client, respx_mock):
    answer(
        respx_mock,
        "시청",
        [
            item("1001", "하행", "인천", "시청", msg="전역 출발", no="0145", ago=40),
            item("1001", "하행", "인천", "시청", msg="시청 도착", no="0145", ago=5),
        ],
    )
    body = (await client.get(ARRIVALS.format("시청"))).json()
    group = next(g for g in body["groups"] if (g["line"], g["direction"]) == ("1호선", "하행"))
    assert [t["message"] for t in group["trains"]] == ["시청 도착"]
