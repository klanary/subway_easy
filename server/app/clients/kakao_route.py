"""카카오 대중교통 경로 조회 (스펙 6.1, api-verification.md §3).

실패도 HTTP 200으로 오고 본문 status가 이유를 알려 준다(2026-11-12 실제 샘플).
"""

import httpx

from app.cache.quota_guard import QuotaGuard
from app.clients.endpoints import KAKAO_ROUTE_URL
from app.clients.http import call_upstream
from app.core.config import Settings
from app.core.errors import AppError, InvalidRequest, NoRoute, UpstreamError

_STATUS_ERRORS: dict[str, AppError] = {
    "EQUAL_POINTS": InvalidRequest("EQUAL_POINTS"),
    "STARTNODES_NULL": NoRoute("NO_START_STOP"),
    "ENDNODES_NULL": NoRoute("NO_END_STOP"),
    "NO_RESULTS": NoRoute("NO_RESULTS"),
}


class KakaoRouteClient:
    def __init__(self, http: httpx.AsyncClient, guard: QuotaGuard, settings: Settings):
        self._http = http
        self._guard = guard
        self._settings = settings

    async def fetch(
        self,
        origin: tuple[float, float],
        dest: tuple[float, float],
        origin_name: str | None = None,
        dest_name: str | None = None,
    ) -> dict:
        params: dict = {
            "start_x": origin[1],
            "start_y": origin[0],
            "end_x": dest[1],
            "end_y": dest[0],
        }
        if origin_name:
            params["s_name"] = origin_name
        if dest_name:
            params["e_name"] = dest_name
        request = httpx.Request(
            "GET",
            KAKAO_ROUTE_URL,
            params=params,
            headers={"Authorization": f"KakaoAK {self._settings.kakao_rest_api_key}"},
        )
        response = await call_upstream(
            self._http,
            self._guard,
            request,
            timeout_sec=self._settings.upstream_timeout_sec,
            retry_delay_sec=self._settings.upstream_retry_delay_sec,
        )
        if not 200 <= response.status_code < 300:
            raise UpstreamError(reason=f"HTTP_{response.status_code}")
        try:
            body = response.json()
        except ValueError as e:
            raise UpstreamError(reason="BAD_BODY") from e
        status = body.get("status")
        if status == "OK":
            return body
        known = _STATUS_ERRORS.get(status)
        if known is not None:
            raise type(known)(known.reason)
        raise UpstreamError(reason=status or "NO_STATUS")
