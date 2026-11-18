import httpx
import pytest

from app.main import create_app

AUTH = {"X-Metrics-Token": "TESTMETRICSTOKEN"}


async def test_metrics_on_fresh_server(client):
    r = await client.get("/v1/metrics", headers=AUTH)
    body = r.json()
    assert r.status_code == 200 and body["cache"] == {"hits": 0, "misses": 0, "hitRate": 0.0}
    assert list(body["upstream"]) == [
        "seoulRealtime",
        "seoulTimetable",
    ]  # 카카오는 경로 직접 계산(2026-11-18)으로 걷어 냈다
    assert body["upstream"]["seoulRealtime"] == {
        "callsToday": 0,
        "dailyLimit": None,  # 제한 없음
        "errorsToday": 0,
    }
    assert body["since"].endswith("+09:00") and body["unmatchedStations"] == 0


async def test_metrics_reflect_counters(client, services):
    services.metrics.cache_hits, services.metrics.cache_misses = 3, 1
    services.metrics.unmatched_station_names.update({"가나", "다라"})
    services.quotas["seoulTimetable"].record_call()
    body = (await client.get("/v1/metrics", headers=AUTH)).json()
    assert body["cache"] == {"hits": 3, "misses": 1, "hitRate": 0.75}
    assert body["upstream"]["seoulTimetable"]["callsToday"] == 1
    assert body["unmatchedStations"] == 2
    assert body["requests"] == {"total": 0, "rateLimited": 0}  # /v1/metrics 자신은 세지 않는다


@pytest.mark.skip(reason="실시간 도착 API와 seoul 픽스처(P2 Task 9) 이후에 켠다")
async def test_metrics_after_miss_and_hit(client, seoul):
    seoul("seoul_realtime_hongdae.json")
    for _ in range(2):
        await client.get("/v1/stations/홍대입구/arrivals")
    body = (await client.get("/v1/metrics", headers=AUTH)).json()
    assert body["cache"] == {"hits": 1, "misses": 1, "hitRate": 0.5}
    assert body["upstream"]["seoulRealtime"]["callsToday"] == 1 and body["requests"]["total"] == 2


async def test_metrics_needs_token(client):
    assert (await client.get("/v1/metrics")).status_code == 404
    wrong = {"X-Metrics-Token": "nope"}
    assert (await client.get("/v1/metrics", headers=wrong)).status_code == 404


async def test_metrics_closed_when_no_token_is_configured(test_settings, clock):
    app = create_app(test_settings.model_copy(update={"metrics_token": ""}), clock=clock)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        r = await c.get("/v1/metrics", headers={"X-Metrics-Token": ""})
    assert r.status_code == 404
