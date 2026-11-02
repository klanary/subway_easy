from datetime import datetime

import httpx
import pytest

from app.core.clock import KST
from app.core.config import Settings
from app.main import create_app
from tests.helpers import FakeClock


@pytest.fixture
def test_settings() -> Settings:
    return Settings(
        _env_file=None,
        seoul_realtime_api_key="TESTSEOULKEY",
        kakao_rest_api_key="TESTKAKAOKEY",
        upstream_retry_delay_sec=0,
    )


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock(datetime(2026, 11, 3, 8, 0, 0, tzinfo=KST).timestamp())


@pytest.fixture
def app(test_settings, clock):
    return create_app(test_settings, clock=clock)


@pytest.fixture
async def client(app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


@pytest.fixture
def services(app):
    return app.state.services


@pytest.fixture
def metrics(services):
    return services.metrics
