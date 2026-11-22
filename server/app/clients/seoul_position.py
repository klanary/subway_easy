"""서울시 지하철 실시간 열차 위치 API(설계 2026-11-22 열차 위치 도착 계산).

노선 하나를 부르면 그 노선의 열차 전부가 온다. VM 수집기(`ml/collector.py`)와 같은 주소 형식이다.
"""

from urllib.parse import quote

import httpx

from app.cache.quota_guard import QuotaGuard
from app.clients.endpoints import SEOUL_REALTIME_BASE
from app.clients.http import call_upstream
from app.clients.seoul_subway import CODE_NO_DATA, CODE_OK, CODE_QUOTA_EXCEEDED, response_code
from app.core.config import Settings
from app.core.errors import QuotaExceeded, UpstreamError

PAGE_SIZE = 200  # 한 노선의 열차는 100대를 넘지 않는다(1호선 80대)


class SeoulPositionClient:
    def __init__(self, http: httpx.AsyncClient, guard: QuotaGuard, settings: Settings):
        self._http = http
        self._guard = guard
        self._settings = settings

    async def fetch_positions(self, line: str) -> list[dict]:
        """그 노선 열차 위치 행. 데이터 없음(INFO-200)은 빈 목록, 그 밖의 오류는 예외."""
        url = (
            f"{SEOUL_REALTIME_BASE}/{self._settings.seoul_realtime_api_key}"
            f"/json/realtimePosition/0/{PAGE_SIZE}/{quote(line)}"
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
            return list(body.get("realtimePositionList") or [])
        if code == CODE_NO_DATA:
            return []
        if code == CODE_QUOTA_EXCEEDED:
            self._guard.mark_exhausted()
            raise QuotaExceeded()
        self._guard.record_error()
        raise UpstreamError(reason=code or "NO_CODE")
