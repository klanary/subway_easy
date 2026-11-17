from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """서버 설정. 필드 이름과 같은 환경 변수(대소문자 무시)나 `.env`에서 읽는다."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    seoul_realtime_api_key: str = ""
    seoul_openapi_key: str = ""  # 일반 인증키(시간표)
    kakao_rest_api_key: str = ""

    # 0 = 제한 없음. 서울시 키는 2026-11-15부터 한도가 없다(호출 수는 그래도 센다).
    seoul_realtime_daily_limit: int = 0
    seoul_timetable_daily_limit: int = 0
    kakao_route_daily_limit: int = 1000

    rate_limit_per_minute: int = 60
    mock_upstream: bool = False
    stations_db_path: str = "data/stations.sqlite"
    fixtures_dir: str = "tests/fixtures"
    upstream_timeout_sec: float = 5.0
    upstream_retry_delay_sec: float = 0.5


@lru_cache
def get_settings() -> Settings:
    return Settings()
