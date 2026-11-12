"""서울시 지하철 역별 시간표 API(재설계 스펙 5.2). 일반 인증키(SEOUL_OPENAPI_KEY)를 쓴다."""

import httpx

from app.cache.quota_guard import QuotaGuard
from app.clients.endpoints import SEOUL_OPENAPI_BASE
from app.clients.http import call_upstream
from app.core.config import Settings
from app.core.errors import QuotaExceeded, UpstreamError

SERVICE = "SearchSTNTimeTableByIDService"
CODE_OK = "INFO-000"
CODE_NO_DATA = "INFO-200"
CODE_QUOTA_EXCEEDED = "ERROR-337"  # 데이터셋 안내 기준. 실제 응답으로는 아직 확인하지 못했다.
MAX_ROWS = 1000  # 한 역·요일·방향은 하루 300행을 넘지 않는다(2026-11-12 5호선 198행)


def response_code(body: object) -> str | None:
    """정상은 서비스 이름 아래 RESULT에, 데이터 없음·오류는 본문 바로 아래 RESULT에 온다."""
    if not isinstance(body, dict):
        return None
    holder = body[SERVICE] if isinstance(body.get(SERVICE), dict) else body
    result = holder.get("RESULT")
    return result.get("CODE") if isinstance(result, dict) else None


class SeoulTimetableClient:
    def __init__(self, http: httpx.AsyncClient, guard: QuotaGuard, settings: Settings):
        self._http = http
        self._guard = guard
        self._settings = settings

    async def fetch(self, station_cd: str, week_tag: int, inout_tag: int) -> list[dict]:
        url = (
            f"{SEOUL_OPENAPI_BASE}/{self._settings.seoul_openapi_key}/json/{SERVICE}"
            f"/1/{MAX_ROWS}/{station_cd}/{week_tag}/{inout_tag}/"
        )
        response = await call_upstream(
            self._http,
            self._guard,
            httpx.Request("GET", url),
            timeout_sec=self._settings.upstream_timeout_sec,
            retry_delay_sec=self._settings.upstream_retry_delay_sec,
        )
        try:
            body = response.json()
        except ValueError as e:
            raise UpstreamError(reason="BAD_BODY") from e
        code = response_code(body)
        if code == CODE_OK:
            return list(body[SERVICE].get("row") or [])
        if code == CODE_NO_DATA:
            return []
        if code == CODE_QUOTA_EXCEEDED:
            self._guard.mark_exhausted()
            raise QuotaExceeded()
        self._guard.record_error()
        raise UpstreamError(reason=code or "NO_CODE")
