import json
import logging

import pytest

# 미들웨어만 검증하도록 테스트 전용 /v1/ 경로를 쓴다(역 검색 API는 P2 Task 4).
PROBE = "/v1/_probe"


@pytest.fixture(autouse=True)
def probe_routes(app, limited_app):
    for target in (app, limited_app):

        @target.get(PROBE)
        async def probe(q: str = ""):
            return {"q": q}


async def test_rate_limited_response_and_metric(limited_client, limited_services):
    h = {"X-Forwarded-For": "1.1.1.1, 10.0.0.1"}
    codes = [
        (await limited_client.get(PROBE, params={"q": "홍대"}, headers=h)).status_code
        for _ in range(4)
    ]
    assert codes == [200, 200, 200, 429] and limited_services.metrics.rate_limited == 1
    other_ip = {"X-Forwarded-For": "2.2.2.2"}
    r = await limited_client.get(PROBE, params={"q": "홍대"}, headers=other_ip)
    assert r.status_code == 200


async def test_rate_limited_body_uses_spec_format(limited_client):
    for _ in range(3):
        await limited_client.get(PROBE)
    r = await limited_client.get(PROBE)
    assert r.status_code == 429 and r.json()["error"]["code"] == "RATE_LIMITED"


async def test_health_is_never_rate_limited(limited_client):
    codes = [(await limited_client.get("/health")).status_code for _ in range(10)]
    assert codes == [200] * 10


async def test_request_id_and_access_log_without_query(client, caplog):
    caplog.set_level(logging.INFO)
    r = await client.get(PROBE, params={"q": "37.55712"})
    assert len(r.headers["X-Request-ID"]) == 12
    rec = next(x for x in caplog.records if getattr(x, "path", None) == PROBE)
    assert (rec.status, rec.cache) == (200, "none")
    assert "37.55712" not in json.dumps(rec.__dict__, default=str, ensure_ascii=False)


async def test_v1_requests_are_counted_but_not_health_or_metrics(client, services):
    await client.get(PROBE)
    await client.get("/health")
    await client.get("/v1/metrics")
    assert services.metrics.requests_total == 1
