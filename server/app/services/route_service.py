"""역에서 역까지 지하철 경로(재설계 스펙 5.3).

카카오 경로를 부르고, 지하철 경로만 빠른 순으로 남긴 뒤 첫 탑승역의 다음 열차를 붙인다.
"""

import logging
from collections.abc import Callable

from app.cache.ttl_cache import TtlCache
from app.clients.kakao_route import KakaoRouteClient
from app.core.clock import kst_from_epoch
from app.core.errors import AppError, InvalidRequest, NoRoute, NotFound
from app.core.metrics import Metrics
from app.models.schemas import FirstBoarding, Route, RoutesResponse, Train
from app.services.arrival_service import ArrivalService
from app.services.route_convert import convert_routes, rank, subway_only
from app.services.station_index import Station, StationIndex

logger = logging.getLogger("app.routes")

ROUTE_TTL_SEC = 600
TRAINS_PER_BOARDING = 2


class RouteService:
    def __init__(
        self,
        client: KakaoRouteClient,
        index: StationIndex,
        cache: TtlCache[list[Route]],
        metrics: Metrics,
        clock: Callable[[], float],
        arrivals: ArrivalService | None = None,
    ):
        self._client = client
        self._index = index
        self._cache = cache
        self._metrics = metrics
        self._clock = clock
        self._arrivals = arrivals

    def _station(self, station_id: str) -> Station:
        station = self._index.get(station_id)
        if station is None:
            raise NotFound()
        return station

    async def _subway_routes(self, start: Station, end: Station) -> list[Route]:
        raw = await self._client.fetch(
            (start.lat, start.lng), (end.lat, end.lng), start.name, end.name
        )
        return rank(subway_only(convert_routes(raw, self._index, self._metrics, limit=None)))

    async def search(self, from_id: str, to_id: str) -> tuple[RoutesResponse, str]:
        start, end = self._station(from_id), self._station(to_id)
        if start.id == end.id:
            raise InvalidRequest("SAME_STATION")
        # 결과가 비어도(버스 경로만) 캐시해 같은 요청에 카카오를 다시 부르지 않는다.
        cached = await self._cache.get_or_fetch(
            f"{start.id}|-|{end.id}", lambda: self._subway_routes(start, end)
        )
        if not cached.value:
            raise NoRoute("NO_SUBWAY")
        routes = [r.model_copy(deep=True) for r in cached.value]
        for route in routes:
            if route.first_boarding is not None:
                route.first_boarding.next_trains = await self._next_trains(route.first_boarding)
        response = RoutesResponse(
            updated_at=kst_from_epoch(cached.fetched_at), stale=cached.stale, routes=routes
        )
        return response, cached.status

    async def _next_trains(self, boarding: FirstBoarding) -> list[Train] | None:
        """첫 탑승역에서 그 방향 다음 열차 2대(실시간, 없으면 시간표). 모르거나 실패하면 None."""
        if self._arrivals is None or boarding.station_id is None:
            return None
        line = boarding.line_name
        towards = self._index.resolve(boarding.towards, line) if boarding.towards else None
        try:
            arrivals, _ = await self._arrivals.get_arrivals(boarding.station_id, line=line)
        except AppError as e:
            logger.warning("첫 탑승 열차 조회 실패 %s: %s", boarding.station_id, e.code)
            return None
        if towards is not None:
            for group in arrivals.groups:
                if group.towards and self._index.same_station(group.towards, towards.id):
                    return group.trains[:TRAINS_PER_BOARDING] or None
            direction = self._index.direction(boarding.station_id, line, towards.id)
            for group in arrivals.groups:
                if direction and group.direction == direction:
                    return group.trains[:TRAINS_PER_BOARDING] or None
        return None
