import httpx
import pytest

from app.core.config import Settings
from app.main import create_app


@pytest.fixture
def test_settings() -> Settings:
    return Settings(
        _env_file=None,
        seoul_realtime_api_key="TESTSEOULKEY",
        kakao_rest_api_key="TESTKAKAOKEY",
        upstream_retry_delay_sec=0,
    )


@pytest.fixture
async def client(test_settings):
    app = create_app(test_settings)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c
