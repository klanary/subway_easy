"""서울시 지하철 실시간 도착정보 API (스펙 6.1, api-verification.md §1)."""

from urllib.parse import quote

import httpx

from app.cache.quota_guard import QuotaGuard
from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.clients.http import call_upstream
from app.core.config import Settings
from app.core.errors import QuotaExceeded, UpstreamError

CODE_OK = "INFO-000"
CODE_NO_DATA = "INFO-200"  # 없는 역 이름, 운행이 끝난 시간
CODE_QUOTA_EXCEEDED = "ERROR-337"  # 데이터셋 안내 기준. 실제 응답으로는 아직 확인하지 못했다.


def response_code(body: object) -> str | None:
    """정상은 errorMessage 안에, 오류는 본문 바로 아래에 code가 온다. 둘 다 HTTP 200이다."""
    if not isinstance(body, dict):
        return None
    wrapped = body.get("errorMessage")
    if isinstance(wrapped, dict):
        return wrapped.get("code")
    return body.get("code")


class SeoulSubwayClient:
    def __init__(self, http: httpx.AsyncClient, guard: QuotaGuard, settings: Settings):
        self._http = http
        self._guard = guard
        self._settings = settings

    async def fetch_arrivals(self, api_name: str) -> list[dict]:
        url = (
            f"{SEOUL_REALTIME_BASE}/{self._settings.seoul_realtime_api_key}"
            f"/json/realtimeStationArrival/0/50/{quote(api_name)}"
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
            return list(body.get("realtimeArrivalList") or [])
        if code == CODE_NO_DATA:
            return []
        if code == CODE_QUOTA_EXCEEDED:
            self._guard.mark_exhausted()
            raise QuotaExceeded()
        self._guard.record_error()
        raise UpstreamError(reason=code or "NO_CODE")
