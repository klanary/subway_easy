"""GET /v1/stations/{id}/timetable (재설계 스펙 6). 서울시 응답은 실제 샘플이다."""

import httpx

from app.clients.endpoints import SEOUL_OPENAPI_BASE
from tests.helpers import fixture_response

URL = "/v1/stations/{}/timetable"
SAMPLE = "seoul_timetable_yeongdeungpo_weekday_up.json"
FRIDAY_8 = "2026-11-13T08:00:00+09:00"


def mock_timetable(respx_mock, name=SAMPLE):
    return respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=fixture_response(name)
    )


async def test_timetable_endpoint(client, respx_mock):
    route = mock_timetable(respx_mock)
    params = {"line": "5호선", "direction": "상행", "from": FRIDAY_8}
    r = await client.get(URL.format("영등포구청"), params=params)
    body = r.json()
    assert r.status_code == 200 and len(body["trains"]) == 4
    assert body["stationId"] == "영등포구청" and body["line"] == "5호선"
    assert set(body["trains"][0]) == {"departsAt", "destination", "isExpress", "trainNo", "source"}
    assert body["trains"][0]["departsAt"] >= "2026-11-13T08:00:00"
    sent = str(route.calls.last.request.url)
    assert "/SearchSTNTimeTableByIDService/" in sent and sent.endswith("/2524/1/1/")


async def test_timetable_count(client, respx_mock):
    mock_timetable(respx_mock)
    params = {"line": "5호선", "direction": "상행", "from": FRIDAY_8, "count": 2}
    assert len((await client.get(URL.format("영등포구청"), params=params)).json()["trains"]) == 2


async def test_timetable_cached_per_day(client, respx_mock):
    route = mock_timetable(respx_mock)
    params = {"line": "5호선", "direction": "상행", "from": FRIDAY_8}
    await client.get(URL.format("영등포구청"), params=params)
    await client.get(
        URL.format("영등포구청"), params={**params, "from": "2026-11-13T18:00:00+09:00"}
    )
    assert route.call_count == 1


async def test_timetable_holiday_uses_tag_3(client, respx_mock):
    route = mock_timetable(respx_mock)
    params = {"line": "5호선", "direction": "하행", "from": "2026-12-25T08:00:00+09:00"}
    await client.get(URL.format("영등포구청"), params=params)
    assert str(route.calls.last.request.url).endswith("/2524/3/2/")


async def test_timetable_non_seoul_line_is_empty(client, respx_mock):
    route = mock_timetable(respx_mock)
    params = {"line": "인천1호선", "direction": "상행", "from": FRIDAY_8}
    r = await client.get(URL.format("계양"), params=params)
    assert r.json()["trains"] == [] and route.call_count == 0


async def test_timetable_no_data(client, respx_mock):
    mock_timetable(respx_mock, "seoul_timetable_nodata.json")
    params = {"line": "5호선", "direction": "상행", "from": FRIDAY_8}
    assert (await client.get(URL.format("영등포구청"), params=params)).json()["trains"] == []


async def test_timetable_bad_params(client):
    base = {"line": "5호선", "direction": "상행", "from": FRIDAY_8}
    for bad in ({"direction": "위로"}, {"from": "어제"}, {"count": 11}, {"count": 0}):
        r = await client.get(URL.format("영등포구청"), params={**base, **bad})
        assert r.status_code == 400, bad
    assert (await client.get(URL.format("없는역"), params=base)).status_code == 404


async def test_timetable_normalizes_line_and_logs_cache(client, respx_mock, caplog):
    """응답 호선 이름은 정규화하고, 접근 로그에 캐시 상태를 남긴다(리뷰 사소한 문제 9)."""
    mock_timetable(respx_mock)
    params = {"line": "05호선", "direction": "상행", "from": FRIDAY_8}
    r = await client.get(URL.format("영등포구청"), params=params)
    assert r.json()["line"] == "5호선"
    r = await client.get(URL.format("영등포구청"), params=params)
    assert any(getattr(rec, "cache", None) == "hit" for rec in caplog.records)


async def test_late_night_fills_from_next_day(client, respx_mock):
    # 금요일 23:58: 그날 시간표에는 24:07·24:20·24:34 세 대뿐이다. 넷째는 토요일 첫차다.
    route = mock_timetable(respx_mock)
    params = {"line": "5호선", "direction": "상행", "from": "2026-11-13T23:58:00+09:00"}
    trains = (await client.get(URL.format("영등포구청"), params=params)).json()["trains"]
    assert [t["departsAt"][:16] for t in trains] == [
        "2026-11-14T00:07",
        "2026-11-14T00:20",
        "2026-11-14T00:34",
        "2026-11-14T05:35",
    ]
    assert any(str(c.request.url).endswith("/2524/2/1/") for c in route.calls)  # 토요일 시간표


async def test_enough_trains_today_does_not_fetch_next_day(client, respx_mock):
    route = mock_timetable(respx_mock)
    await client.get(
        URL.format("영등포구청"), params={"line": "5호선", "direction": "상행", "from": FRIDAY_8}
    )
    assert route.call_count == 1


async def test_early_morning_survives_failed_previous_day(client, respx_mock):
    # 토요일 02:00에는 금요일(자정 넘은 열차)과 토요일 시간표를 함께 본다.
    # 금요일만 실패해도 토요일 열차는 준다.
    respx_mock.get(url__regex=r"/2524/1/1/$").mock(return_value=httpx.Response(500))
    respx_mock.get(url__regex=r"/2524/2/1/$").mock(return_value=fixture_response(SAMPLE))
    params = {"line": "5호선", "direction": "상행", "from": "2026-11-14T02:00:00+09:00", "count": 1}
    r = await client.get(URL.format("영등포구청"), params=params)
    assert r.status_code == 200 and r.json()["trains"][0]["departsAt"][:16] == "2026-11-14T05:35"


async def test_line_without_official_timetable_uses_measured(client, respx_mock, measured_loaded):
    mock_timetable(respx_mock, "seoul_timetable_nodata.json")
    params = {"line": "신분당선", "direction": "상행", "from": "2026-11-18T07:00:00+09:00"}
    body = (await client.get(URL.format("강남"), params=params)).json()
    assert body["source"] == "measured"
    assert body["trains"][0]["departsAt"][11:16] == "07:05"
    assert body["trains"][0]["source"] == "measured"


async def test_official_line_stays_official(client, respx_mock, measured_loaded):
    mock_timetable(respx_mock)
    params = {"line": "5호선", "direction": "상행", "from": FRIDAY_8}
    body = (await client.get(URL.format("영등포구청"), params=params)).json()
    assert body["source"] == "official" and body["trains"]


async def test_first_request_does_not_wait_for_download(client, respx_mock):
    # 결과물을 아직 못 받았으면 기다리지 않고 예전처럼 빈 목록이다.
    mock_timetable(respx_mock, "seoul_timetable_nodata.json")
    params = {"line": "신분당선", "direction": "상행", "from": "2026-11-18T07:00:00+09:00"}
    body = (await client.get(URL.format("강남"), params=params)).json()
    assert body == {**body, "trains": [], "source": "none"}


async def test_seoul_failure_does_not_block_measured(client, respx_mock, measured_loaded):
    # 시간표 API가 없는 노선은 서울시를 부르지 않고 바로 실측으로 간다(최종 리뷰 #2).
    route = respx_mock.get(url__startswith=SEOUL_OPENAPI_BASE).mock(
        return_value=httpx.Response(500)
    )
    params = {"line": "신분당선", "direction": "상행", "from": "2026-11-18T07:00:00+09:00"}
    r = await client.get(URL.format("강남"), params=params)
    assert r.status_code == 200 and r.json()["source"] == "measured"
    assert route.call_count == 0
