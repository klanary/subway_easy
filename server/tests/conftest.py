from datetime import datetime

import httpx
import pytest

from app.clients.endpoints import KAKAO_ROUTE_URL, SEOUL_REALTIME_BASE
from app.core.clock import KST
from app.core.config import Settings
from app.main import create_app
from app.services.station_index import StationIndex
from tests.helpers import FIXTURES_DIR, FakeClock, fixture_response


@pytest.fixture
def test_settings() -> Settings:
    return Settings(
        _env_file=None,
        seoul_realtime_api_key="TESTSEOULKEY",
        seoul_openapi_key="TESTOPENAPIKEY",
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


@pytest.fixture
def limited_app(test_settings, clock):
    """IP별 요청 제한이 분당 3회인 앱."""
    return create_app(test_settings.model_copy(update={"rate_limit_per_minute": 3}), clock=clock)


@pytest.fixture
async def limited_client(limited_app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=limited_app), base_url="http://test"
    ) as c:
        yield c


@pytest.fixture
def limited_services(limited_app):
    return limited_app.state.services


@pytest.fixture
def sample_db(tmp_path):
    """합성 역 데이터(master_sample.csv)로 만든 stations.sqlite 경로."""
    from scripts.build_stations import build, read_master_csv, write_sqlite

    rows, _ = read_master_csv(FIXTURES_DIR / "master_sample.csv")
    path = tmp_path / "stations.sqlite"
    separate = {("양평", "경의중앙선"): "양평(경의중앙선)"}
    write_sqlite(build(rows, separate=separate, api_names={}), path)
    return path


@pytest.fixture
def sample_index(sample_db):
    return StationIndex.load(sample_db)


@pytest.fixture
def real_index():
    return StationIndex.load("data/stations.sqlite")


@pytest.fixture
async def sample_client(test_settings, clock, sample_db):
    app = create_app(
        test_settings.model_copy(update={"stations_db_path": str(sample_db)}), clock=clock
    )
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


@pytest.fixture
def seoul(respx_mock):
    """seoul("샘플.json"): 서울시 실시간 요청에 그 샘플로 답하게 하고 route를 돌려준다."""

    def use(name: str):
        return respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
            return_value=fixture_response(name)
        )

    return use


@pytest.fixture
def kakao_route(respx_mock):
    """kakao_route("샘플.json"): 카카오 경로 요청에 그 샘플로 답하게 하고 route를 돌려준다."""

    def use(name: str):
        return respx_mock.get(KAKAO_ROUTE_URL).mock(return_value=fixture_response(name))

    return use
