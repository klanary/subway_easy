import pytest


async def test_metrics_on_fresh_server(client):
    r = await client.get("/v1/metrics")
    body = r.json()
    assert r.status_code == 200 and body["cache"] == {"hits": 0, "misses": 0, "hitRate": 0.0}
    assert list(body["upstream"]) == [
        "seoulRealtime",
        "kakaoRoute",
        "kakaoKeyword",
        "kakaoCoord2Address",
    ]
    assert body["upstream"]["seoulRealtime"] == {
        "callsToday": 0,
        "dailyLimit": 1000,
        "errorsToday": 0,
    }
    assert body["since"].endswith("+09:00") and body["unmatchedStations"] == 0


async def test_metrics_reflect_counters(client, services):
    services.metrics.cache_hits, services.metrics.cache_misses = 3, 1
    services.metrics.unmatched_station_names.update({"가나", "다라"})
    services.quotas["kakaoRoute"].record_call()
    body = (await client.get("/v1/metrics")).json()
    assert body["cache"] == {"hits": 3, "misses": 1, "hitRate": 0.75}
    assert body["upstream"]["kakaoRoute"]["callsToday"] == 1
    assert body["unmatchedStations"] == 2
    assert body["requests"] == {"total": 0, "rateLimited": 0}  # /v1/metrics 자신은 세지 않는다


@pytest.mark.skip(reason="실시간 도착 API와 seoul 픽스처(P2 Task 9) 이후에 켠다")
async def test_metrics_after_miss_and_hit(client, seoul):
    seoul("seoul_realtime_hongdae.json")
    for _ in range(2):
        await client.get("/v1/stations/홍대입구/arrivals")
    body = (await client.get("/v1/metrics")).json()
    assert body["cache"] == {"hits": 1, "misses": 1, "hitRate": 0.5}
    assert body["upstream"]["seoulRealtime"]["callsToday"] == 1 and body["requests"]["total"] == 2
