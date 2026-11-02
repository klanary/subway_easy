import json
import logging

import httpx
import pytest

from app.cache.quota_guard import QuotaGuard
from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.clients.http import call_upstream, mask_url
from app.core.errors import QuotaExceeded, UpstreamError, UpstreamTimeout
from app.core.logging import configure_logging

SEOUL_URL = f"{SEOUL_REALTIME_BASE}/SECRETKEY123/json/realtimeStationArrival/0/50/홍대입구"


@pytest.fixture
def guard(clock):
    return QuotaGuard("seoulRealtime", 1000, clock)


async def send(guard):
    async with httpx.AsyncClient() as http:
        return await call_upstream(
            http, guard, http.build_request("GET", SEOUL_URL), timeout_sec=5, retry_delay_sec=0
        )


def mock(respx_mock, *responses):
    return respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(side_effect=list(responses))


async def test_retry_once_after_timeout(respx_mock, guard):
    route = mock(respx_mock, httpx.ReadTimeout("t"), httpx.Response(200, json={}))
    assert (await send(guard)).status_code == 200 and route.call_count == 2
    assert (guard.calls_today, guard.errors_today) == (2, 1)


async def test_two_timeouts_raise_upstream_timeout(respx_mock, guard):
    mock(respx_mock, httpx.ReadTimeout("t"), httpx.ReadTimeout("t"))
    with pytest.raises(UpstreamTimeout):
        await send(guard)


async def test_two_5xx_raise_upstream_error(respx_mock, guard):
    mock(respx_mock, httpx.Response(500), httpx.Response(503))
    with pytest.raises(UpstreamError):
        await send(guard)


async def test_429_marks_exhausted_without_retry(respx_mock, guard):
    route = mock(respx_mock, httpx.Response(429))
    with pytest.raises(QuotaExceeded):
        await send(guard)
    assert route.call_count == 1 and not guard.can_call()


async def test_exhausted_guard_sends_nothing(respx_mock, guard):
    route = mock(respx_mock, httpx.Response(200, json={}))
    guard.mark_exhausted()
    with pytest.raises(QuotaExceeded):
        await send(guard)
    assert not route.called


async def test_other_4xx_returned_without_retry(respx_mock, guard):
    route = mock(respx_mock, httpx.Response(400, json={"code": "X"}))
    assert (await send(guard)).status_code == 400 and route.call_count == 1


async def test_seoul_key_never_logged(respx_mock, guard, caplog):
    configure_logging()
    caplog.set_level(logging.INFO)
    mock(respx_mock, httpx.ReadTimeout("t"), httpx.Response(200, json={}))
    await send(guard)
    dumped = [json.dumps(r.__dict__, default=str, ensure_ascii=False) for r in caplog.records]
    assert dumped and not any("SECRETKEY123" in d for d in dumped)
    assert any("/api/subway/***/" in d for d in dumped)


def test_mask_url_rounds_coordinates():
    assert (
        mask_url("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=126.924512&y=37.557112")
        == "https://dapi.kakao.com/v2/local/geo/coord2address.json?x=126.925&y=37.557"
    )
