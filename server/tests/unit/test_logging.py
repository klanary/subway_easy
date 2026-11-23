import logging

from app.core.logging import configure_logging


def test_uvicorn_access_log_is_silenced():
    # uvicorn 기본 접근 로그는 쿼리(좌표 전체 자리수)를 그대로 남긴다(스펙 5.4 위반).
    # 쿼리를 뺀 접근 로그는 app.access가 대신 남긴다.
    configure_logging()
    assert not logging.getLogger("uvicorn.access").isEnabledFor(logging.INFO)
