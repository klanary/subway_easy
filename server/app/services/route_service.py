"""대중교통 경로 검색 (스펙 6.2, 6.6). 경유지(⑧)와 실시간 결합(⑩)은 P4 Task 10·13에서 더한다."""

from collections.abc import Callable

from app.cache.ttl_cache import TtlCache
from app.clients.kakao_route import KakaoRouteClient
from app.core.clock import kst_from_epoch
from app.core.errors import InvalidRequest
from app.core.metrics import Metrics
from app.models.schemas import Route, RoutesResponse
from app.services.route_convert import convert_routes
from app.services.station_index import StationIndex

ROUTE_TTL_SEC = 600


def _key(point: tuple[float, float]) -> str:
    return f"{point[0]:.4f},{point[1]:.4f}"


class RouteService:
    def __init__(
        self,
        client: KakaoRouteClient,
        index: StationIndex,
        cache: TtlCache[list[Route]],
        metrics: Metrics,
        clock: Callable[[], float],
    ):
        self._client = client
        self._index = index
        self._cache = cache
        self._metrics = metrics
        self._clock = clock

    async def search(
        self,
        origin: tuple[float, float],
        dest: tuple[float, float],
        origin_name: str | None = None,
        dest_name: str | None = None,
    ) -> tuple[RoutesResponse, str]:
        # 소수점 4자리(약 10m)까지 같으면 카카오를 부르지 않는다.
        if _key(origin) == _key(dest):
            raise InvalidRequest("EQUAL_POINTS")

        async def fetch() -> list[Route]:
            raw = await self._client.fetch(origin, dest, origin_name, dest_name)
            return convert_routes(raw, self._index, self._metrics)

        # 가운데 "-"는 경유지 자리(P4 Task 10).
        cached = await self._cache.get_or_fetch(f"{_key(origin)}|-|{_key(dest)}", fetch)
        response = RoutesResponse(
            updated_at=kst_from_epoch(cached.fetched_at),
            stale=cached.stale,
            routes=cached.value,
        )
        return response, cached.status
