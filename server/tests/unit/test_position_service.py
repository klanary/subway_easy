"""실시간 위치 받기(설계 2026-11-22 열차 위치 도착 계산 §4): 노선별 20초 캐시, 실패는 빈 목록."""

from datetime import datetime, timedelta

import httpx
import pytest

from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.core.clock import KST
from app.services.position_service import TrainPosition

NOW = datetime(2026, 11, 3, 8, 0, 0, tzinfo=KST)  # conftest의 시계와 같은 시각


def row(**kw):
    base = {
        "subwayId": "1002",
        "statnId": "1002000201",
        "statnNm": "시청",
        "trainNo": "2155",
        "updnLine": "0",
        "trainSttus": "1",
        "directAt": "0",
        "statnTid": "1002000211",
        "statnTnm": "성수",
        "lstcarAt": "0",
        "recptnDt": (NOW - timedelta(seconds=10)).strftime("%Y-%m-%d %H:%M:%S"),
    }
    base.update(kw)
    return base


def answer(respx_mock, rows=None, *, status=200, code="INFO-000"):
    if status != 200:
        response = httpx.Response(status)
    elif code == "INFO-200":
        response = httpx.Response(200, json={"status": 500, "code": "INFO-200", "message": "없음"})
    else:
        body = {"errorMessage": {"status": 200, "code": code}, "realtimePositionList": rows or []}
        response = httpx.Response(200, json=body)
    return respx_mock.get(url__regex=r".*/realtimePosition/0/200/.*").mock(return_value=response)


@pytest.fixture
def positions(services):
    return services.positions


async def test_parses_rows_into_positions(positions, respx_mock):
    answer(respx_mock, [row()])
    (got,) = await positions.positions("2호선")
    assert got == TrainPosition(
        line="2호선",
        direction="내선",
        train_no="2155",
        station_id="시청",
        status=1,
        seen_at=NOW - timedelta(seconds=10),
        dest_name="성수",
        express=False,
    )


async def test_itx_counts_as_express(positions, respx_mock):
    answer(respx_mock, [row(directAt="9")])
    (got,) = await positions.positions("2호선")
    assert got.express is True


async def test_stale_and_unknown_rows_dropped(positions, respx_mock):
    old = (NOW - timedelta(seconds=601)).strftime("%Y-%m-%d %H:%M:%S")
    answer(respx_mock, [row(recptnDt=old), row(statnNm="없는역", trainNo="9")])
    assert await positions.positions("2호선") == []


async def test_future_seen_at_becomes_now(positions, respx_mock):
    future = (NOW + timedelta(seconds=120)).strftime("%Y-%m-%d %H:%M:%S")
    answer(respx_mock, [row(recptnDt=future)])
    (got,) = await positions.positions("2호선")
    assert got.seen_at == NOW


async def test_no_data_and_errors_give_empty_list(positions, respx_mock, clock):
    answer(respx_mock, code="INFO-200")
    assert await positions.positions("2호선") == []
    clock.advance(21)
    answer(respx_mock, status=500)
    assert await positions.positions("2호선") == []


async def test_cached_for_20_seconds(positions, respx_mock, clock):
    route = answer(respx_mock, [row()])
    await positions.positions("2호선")
    clock.advance(19)
    await positions.positions("2호선")
    assert route.call_count == 1
    clock.advance(2)
    await positions.positions("2호선")
    assert route.call_count == 2
    assert SEOUL_REALTIME_BASE in str(route.calls.last.request.url)
