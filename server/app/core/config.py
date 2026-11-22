from functools import lru_cache

from pydantic import NonNegativeInt
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """서버 설정. 필드 이름과 같은 환경 변수(대소문자 무시)나 `.env`에서 읽는다."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    seoul_realtime_api_key: str = ""
    seoul_openapi_key: str = ""  # 일반 인증키(시간표)

    # 0 = 제한 없음. 서울시 키는 2026-11-15부터 한도가 없다(호출 수는 그래도 센다).
    # 음수는 오타로 보고 받지 않는다(서버가 뜨지 않음).
    seoul_realtime_daily_limit: NonNegativeInt = 0
    seoul_timetable_daily_limit: NonNegativeInt = 0

    # VM 실측 결과물(GitHub 릴리스)을 받는 주소. 비우면 실측을 쓰지 않는다(설계 2026-11-17).
    measured_base_url: str = (
        "https://github.com/klanary/subway_easy/releases/download/measured-timetable"
    )

    # /v1/metrics를 볼 때 X-Metrics-Token 헤더로 보내는 값. 비어 있으면 지표 주소를 닫는다(404).
    metrics_token: str = ""

    rate_limit_per_minute: int = 60
    mock_upstream: bool = False
    stations_db_path: str = "data/stations.sqlite"
    # 서울시가 빈 응답을 줄 때 쓰는 공식 시간표 사본(1호선, 2026-11-22)
    official_snapshot_path: str = "data/official_snapshot.json.gz"
    # 열차 위치로 도착을 계산하는 노선(설계 2026-11-22). 되돌려 보기에서 이긴 노선만 넣는다.
    # [지금]: barvlDt가 0인 열차를 위치로 계산. 2026-11-22 되돌려 보기(시험 3일)의 제안 그대로
    # (docs/ml/2026-11-22-position-eval.md).
    # 서울교통공사 노선은 초 단위 값이 와서 거의 바뀌지 않는다.
    position_now_lines: list[str] = [
        "1호선", "3호선", "4호선", "5호선", "6호선", "7호선", "8호선", "GTX-A", "경강선",
        "경의중앙선", "경춘선", "공항철도", "서해선", "수인분당선", "신림선", "신분당선",
        "우이신설선",
    ]  # fmt: skip
    # [시각 선택] 60분 안: 2026-11-22 평가에서 모든 시점에 시간표를 이긴 노선이 없어
    # 비워 둔다.
    position_at_lines: list[str] = []
    fixtures_dir: str = "tests/fixtures"
    upstream_timeout_sec: float = 5.0
    upstream_retry_delay_sec: float = 0.5


@lru_cache
def get_settings() -> Settings:
    return Settings()
