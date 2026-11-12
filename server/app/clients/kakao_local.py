"""카카오 로컬 API: 키워드로 장소 검색, 좌표로 주소 변환 (스펙 6.1, api-verification.md §3)."""

import httpx

from app.cache.quota_guard import QuotaGuard
from app.clients.endpoints import KAKAO_LOCAL_BASE
from app.clients.http import call_upstream
from app.core.config import Settings
from app.core.errors import UpstreamError

PLACE_PAGE_SIZE = 15


class KakaoLocalClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        keyword_guard: QuotaGuard,
        coord_guard: QuotaGuard,
        settings: Settings,
    ):
        self._http = http
        self._keyword_guard = keyword_guard
        self._coord_guard = coord_guard
        self._settings = settings

    async def _get(self, guard: QuotaGuard, path: str, params: dict) -> dict:
        request = httpx.Request(
            "GET",
            f"{KAKAO_LOCAL_BASE}/{path}",
            params=params,
            headers={"Authorization": f"KakaoAK {self._settings.kakao_rest_api_key}"},
        )
        response = await call_upstream(
            self._http,
            guard,
            request,
            timeout_sec=self._settings.upstream_timeout_sec,
            retry_delay_sec=self._settings.upstream_retry_delay_sec,
        )
        if not 200 <= response.status_code < 300:
            raise UpstreamError(reason=f"HTTP_{response.status_code}")
        try:
            return response.json()
        except ValueError as e:
            raise UpstreamError(reason="BAD_BODY") from e

    async def search_keyword(self, query: str, lat: float | None, lng: float | None) -> dict:
        params: dict = {"query": query, "size": PLACE_PAGE_SIZE}
        if lat is not None and lng is not None:
            # 좌표는 가까운 지점을 앞에 두는 데만 쓴다. 거리순(sort=distance)은 이름이 맞지 않는
            # 근처 장소를 앞에 세워서 정확도순을 쓴다(2026-11-12 실제 비교, api-verification.md §3).
            params.update(x=lng, y=lat, sort="accuracy")
        return await self._get(self._keyword_guard, "search/keyword.json", params)

    async def coord_to_address(self, lat: float, lng: float) -> dict:
        return await self._get(self._coord_guard, "geo/coord2address.json", {"x": lng, "y": lat})
