from datetime import datetime

import httpx
import pytest

from app.core.clock import KST, kst_from_epoch
from app.core.errors import NoRoute, NotFound, QuotaExceeded
from app.main import create_app


@pytest.fixture
async def probe_client(test_settings, clock):
    app = create_app(test_settings, clock=clock)

    @app.get("/_probe")
    async def probe(n: int, kind: str = "ok"):
        errors = {
            "notfound": NotFound(),
            "noroute": NoRoute("NO_START_STOP"),
            "quota": QuotaExceeded(),
        }
        if kind in errors:
            raise errors[kind]
        return {"n": n}

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


async def test_missing_param_is_400_invalid_request(probe_client):
    r = await probe_client.get("/_probe")
    assert r.status_code == 400
    assert r.json() == {
        "error": {
            "code": "INVALID_REQUEST",
            "reason": "PARAMS",
            "message": "요청 형식이 올바르지 않아요.",
        }
    }


async def test_app_error_uses_spec_format(probe_client):
    r = await probe_client.get("/_probe", params={"n": 1, "kind": "noroute"})
    assert r.status_code == 404
    assert r.json()["error"] == {
        "code": "NO_ROUTE",
        "reason": "NO_START_STOP",
        "message": "출발지 근처에서 탈 수 있는 대중교통이 없어요.",
    }


async def test_reason_omitted_when_absent(probe_client):
    r = await probe_client.get("/_probe", params={"n": 1, "kind": "quota"})
    assert r.status_code == 503 and "reason" not in r.json()["error"]


def test_kst_from_epoch_truncates_and_uses_plus_nine():
    t = kst_from_epoch(datetime(2026, 11, 3, 8, 0, 1, 750000, tzinfo=KST).timestamp())
    assert t.isoformat() == "2026-11-03T08:00:01+09:00"
