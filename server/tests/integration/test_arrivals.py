"""실시간 도착 API. 서울시 샘플은 공식 샘플 키로 받은 '서울'역의 실제 응답이다(4개 호선, 5행)."""

from datetime import datetime

import httpx
import pytest

from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.core.clock import KST
from tests.helpers import fixture_response, load_fixture, quota_exceeded_response

ARRIVALS = "/v1/stations/{}/arrivals"
SAMPLE = "seoul_realtime_sample_seoul.json"
SEOUL_LINES = {"1호선", "4호선", "경의중앙선", "공항철도"}


@pytest.fixture(autouse=True)
def at_sample_time(clock):
    """샘플 첫 항목이 만들어진 10초 뒤로 시계를 맞춘다."""
    first = load_fixture(SAMPLE)[1]["realtimeArrivalList"][0]["recptnDt"]
    generated = datetime.strptime(first, "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
    clock.set(generated.timestamp() + 10)


async def test_groups_and_trains(client, seoul):
    seoul(SAMPLE)
    r = await client.get(ARRIVALS.format("서울"))
    body = r.json()
    assert r.status_code == 200 and body["stale"] is False
    assert body["stationId"] == "서울" and body["updatedAt"].endswith("+09:00")
    assert body["groups"] and {g["line"] for g in body["groups"]} <= SEOUL_LINES
    for g in body["groups"]:
        secs = [t["remainingSec"] for t in g["trains"]]
        assert len(secs) <= 2 and secs == sorted(secs, key=lambda s: (s is None, s or 0))
        assert set(g) == {"line", "direction", "towards", "trains"}
    train = body["groups"][0]["trains"][0]
    assert {"destination", "remainingSec", "message", "isLast", "isExpress", "trainNo"} <= set(
        train
    )


async def test_station_name_with_suffix_also_works(client, seoul):
    seoul(SAMPLE)
    assert (await client.get(ARRIVALS.format("서울역"))).json()["stationId"] == "서울"


async def test_filter_by_line_and_towards(client, seoul):
    seoul(SAMPLE)
    first = (await client.get(ARRIVALS.format("서울"))).json()["groups"][0]
    params = {"line": first["line"], "towards": first["towards"]}
    groups = (await client.get(ARRIVALS.format("서울"), params=params)).json()["groups"]
    assert groups
    assert all((g["line"], g["towards"]) == (first["line"], first["towards"]) for g in groups)


async def test_nodata_returns_empty_groups(client, seoul):
    seoul("seoul_realtime_nodata.json")
    r = await client.get(ARRIVALS.format("서울"))
    assert r.status_code == 200 and r.json()["groups"] == []


async def test_seoul_quota_error_in_200_body(client, respx_mock, services):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(return_value=quota_exceeded_response())
    r = await client.get(ARRIVALS.format("서울"))
    assert r.status_code == 503 and r.json()["error"]["code"] == "QUOTA_EXCEEDED"
    assert not services.quotas["seoulRealtime"].can_call()


async def test_flat_error_body_is_upstream_error(client, seoul):
    """서울시는 오류도 HTTP 200으로 주고, errorMessage 없이 code가 바로 온다(실제 샘플)."""
    seoul("seoul_realtime_sample_error.json")
    r = await client.get(ARRIVALS.format("서울"))
    assert r.status_code == 502 and r.json()["error"]["code"] == "UPSTREAM_ERROR"


async def test_unknown_station_404_without_call(client, respx_mock):
    r = await client.get(ARRIVALS.format("없는역"))
    assert r.status_code == 404 and r.json()["error"]["code"] == "NOT_FOUND"
    assert not respx_mock.calls


async def test_cached_for_20_seconds(client, seoul, clock):
    route = seoul(SAMPLE)
    await client.get(ARRIVALS.format("서울"))
    clock.advance(19)
    await client.get(ARRIVALS.format("서울"))
    assert route.call_count == 1
    clock.advance(2)
    await client.get(ARRIVALS.format("서울"))
    assert route.call_count == 2


async def test_stale_after_failure(client, respx_mock, clock):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        side_effect=[fixture_response(SAMPLE), httpx.Response(500), httpx.Response(500)]
    )
    first = (await client.get(ARRIVALS.format("서울"))).json()
    clock.advance(21)
    r = await client.get(ARRIVALS.format("서울"))
    assert r.status_code == 200 and r.json()["stale"] is True
    keys = [(g["line"], g["towards"]) for g in r.json()["groups"]]
    assert keys == [(g["line"], g["towards"]) for g in first["groups"]]


async def test_502_without_cache(client, respx_mock):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        side_effect=[httpx.Response(500), httpx.Response(500)]
    )
    assert (await client.get(ARRIVALS.format("서울"))).json()["error"]["code"] == "UPSTREAM_ERROR"


async def test_departed_train_excluded(client, seoul):
    """샘플에는 이미 떠난 열차("서울 출발")가 실제로 들어 있다."""
    assert any(i["arvlMsg2"] == "서울 출발" for i in load_fixture(SAMPLE)[1]["realtimeArrivalList"])
    seoul(SAMPLE)
    groups = (await client.get(ARRIVALS.format("서울"))).json()["groups"]
    assert all(t["message"] != "서울 출발" for g in groups for t in g["trains"])


async def test_unknown_subway_id_is_skipped(client, respx_mock):
    def unknown_first(body):
        body["realtimeArrivalList"][0]["subwayId"] = "9999"

    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        return_value=fixture_response(SAMPLE, unknown_first)
    )
    r = await client.get(ARRIVALS.format("서울"))
    assert r.status_code == 200 and {g["line"] for g in r.json()["groups"]} <= SEOUL_LINES


async def test_separate_station_keeps_only_own_lines(sample_client, respx_mock):
    def as_yangpyeong(body):  # 같은 이름의 두 역(5호선·경의중앙선) 열차가 섞인 응답을 만든다
        for i, item in enumerate(body["realtimeArrivalList"]):
            item["subwayId"] = "1005" if i % 2 == 0 else "1063"
            item["arvlMsg2"] = "전역 도착"

    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        return_value=fixture_response(SAMPLE, as_yangpyeong)
    )
    groups = (await sample_client.get(ARRIVALS.format("양평"))).json()["groups"]
    assert groups and {g["line"] for g in groups} == {"5호선"}
