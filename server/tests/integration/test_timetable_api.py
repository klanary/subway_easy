"""GET /v1/stations/{id}/timetable (재설계 스펙 6). 서울시 응답은 실제 샘플이다."""

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
    assert set(body["trains"][0]) == {"departsAt", "destination", "isExpress", "trainNo"}
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
