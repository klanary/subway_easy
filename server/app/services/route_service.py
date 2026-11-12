"""역에서 역까지 지하철 경로(재설계 스펙 5.3).

카카오 경로를 부르고, 지하철 경로만 빠른 순으로 남긴 뒤 첫 탑승역의 다음 열차를 붙인다.
경유역이 있으면 두 구간을 따로 구해 합친다. 출발 시각을 주면 시간표 열차를 붙인다.
"""

import logging
from collections.abc import Callable
from datetime import datetime, timedelta

from app.cache.ttl_cache import CacheResult, TtlCache
from app.clients.kakao_route import KakaoRouteClient
from app.core.clock import kst_from_epoch
from app.core.errors import AppError, InvalidRequest, NoRoute, NotFound
from app.core.metrics import Metrics
from app.models.schemas import FirstBoarding, Route, RoutesResponse, Train
from app.services.arrival_service import ArrivalService, timetable_train
from app.services.route_convert import combine_via, convert_routes, rank, subway_only
from app.services.station_index import Station, StationIndex
from app.services.timetable_service import TimetableService

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
        timetable: TimetableService | None = None,
    ):
        self._client = client
        self._index = index
        self._cache = cache
        self._metrics = metrics
        self._clock = clock
        self._arrivals = arrivals
        self._timetable = timetable

    def _station(self, station_id: str) -> Station:
        station = self._index.get(station_id)
        if station is None:
            raise NotFound()
        return station

    async def _segment(self, start: Station, end: Station) -> CacheResult[list[Route]]:
        """한 구간의 지하철 경로. 결과가 비어도(버스 경로만) 캐시해 카카오를 다시 부르지 않는다."""

        async def fetch() -> list[Route]:
            raw = await self._client.fetch(
                (start.lat, start.lng), (end.lat, end.lng), start.name, end.name
            )
            routes = convert_routes(raw, self._index, self._metrics, limit=None)
            return rank(subway_only(routes))

        return await self._cache.get_or_fetch(f"{start.id}|-|{end.id}", fetch)

    async def search(
        self,
        from_id: str,
        to_id: str,
        via_id: str | None = None,
        depart_at: datetime | None = None,
    ) -> tuple[RoutesResponse, str]:
        start, end = self._station(from_id), self._station(to_id)
        via = self._station(via_id) if via_id else None
        ids = [start.id, end.id] + ([via.id] if via else [])
        if len(set(ids)) != len(ids):
            raise InvalidRequest("SAME_STATION")
        if via is None:
            result = await self._segment(start, end)
            parts = [result]
            routes = result.value
        else:
            parts = [await self._segment(start, via), await self._segment(via, end)]
            routes = combine_via(parts[0].value, parts[1].value)
        if not routes:
            raise NoRoute("NO_SUBWAY")
        routes = [r.model_copy(deep=True) for r in routes]
        for route in routes:
            await self._attach_trains(route, depart_at)
        response = RoutesResponse(
            updated_at=kst_from_epoch(min(p.fetched_at for p in parts)),
            stale=any(p.stale for p in parts),
            routes=routes,
        )
        status = (
            "stale"
            if response.stale
            else ("hit" if all(p.status == "hit" for p in parts) else "miss")
        )
        return response, status

    async def _attach_trains(self, route: Route, depart_at: datetime | None) -> None:
        boarding = route.first_boarding
        if boarding is None:
            return
        if depart_at is None:
            boarding.next_trains = await self._next_trains(boarding)
            return
        found = await self._scheduled(boarding, depart_at)
        if found:
            now = kst_from_epoch(self._clock())
            boarding.next_trains = [timetable_train(t, now) for t in found]
            route.depart_at = found[0].departs_at
            route.arrive_at = found[0].departs_at + timedelta(
                seconds=route.total_sec - boarding.sec_to_boarding
            )

    def _boarding_direction(self, boarding: FirstBoarding) -> tuple[Station | None, str | None]:
        line = boarding.line_name
        towards = self._index.resolve(boarding.towards, line) if boarding.towards else None
        if towards is None or boarding.station_id is None:
            return towards, None
        return towards, self._index.direction(boarding.station_id, line, towards.id)

    async def _scheduled(self, boarding: FirstBoarding, depart_at: datetime) -> list:
        """출발 시각 + 역까지 걷는 시간 이후 시간표 열차 2대. 방향이나 시간표를 모르면 빈 목록."""
        _, direction = self._boarding_direction(boarding)
        if self._timetable is None or direction is None or boarding.station_id is None:
            return []
        after = depart_at + timedelta(seconds=boarding.sec_to_boarding)
        try:
            return await self._timetable.trains(
                boarding.station_id, boarding.line_name, direction, after, TRAINS_PER_BOARDING
            )
        except AppError as e:
            logger.warning("출발 시각 열차 조회 실패 %s: %s", boarding.station_id, e.code)
            return []

    async def _next_trains(self, boarding: FirstBoarding) -> list[Train] | None:
        """첫 탑승역에서 그 방향 다음 열차 2대(실시간, 없으면 시간표). 모르거나 실패하면 None."""
        if self._arrivals is None or boarding.station_id is None:
            return None
        towards, direction = self._boarding_direction(boarding)
        if towards is None:
            return None
        try:
            arrivals, _ = await self._arrivals.get_arrivals(
                boarding.station_id, line=boarding.line_name
            )
        except AppError as e:
            logger.warning("첫 탑승 열차 조회 실패 %s: %s", boarding.station_id, e.code)
            return None
        for group in arrivals.groups:
            if group.towards and self._index.same_station(group.towards, towards.id):
                return group.trains[:TRAINS_PER_BOARDING] or None
        for group in arrivals.groups:
            if direction and group.direction == direction:
                return group.trains[:TRAINS_PER_BOARDING] or None
        return None
