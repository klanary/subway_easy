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
from app.models.schemas import FirstBoarding, Route, RoutesResponse, Step, Train, TransferTrain
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
        now = kst_from_epoch(self._clock())
        first: datetime | None = None
        if depart_at is None:
            boarding.next_trains = await self._next_trains(boarding)
            seconds = (boarding.next_trains or [None])[0]
            if seconds is not None and seconds.remaining_sec is not None:
                first = now + timedelta(seconds=seconds.remaining_sec)
        else:
            found = await self._scheduled(boarding, depart_at)
            if found:
                boarding.next_trains = [timetable_train(t, now) for t in found]
                first = found[0].departs_at
                route.depart_at = first
                route.arrive_at = first + timedelta(
                    seconds=route.total_sec - boarding.sec_to_boarding
                )
        if first is not None:
            await self._attach_transfers(route, first, now)

    async def _attach_transfers(self, route: Route, first: datetime, now: datetime) -> None:
        """환승해서 타는 지하철 단계마다 타고 온 열차의 도착 시각과 그 뒤 출발 열차 2대를 붙인다.

        시각은 첫 열차 출발부터 탑승 시간만 더해 간다.
        환승 걷기는 더하지 않는다(2026-11-13 사용자 요청).
        환승역에서 탈 열차가 정해지면 그 열차 출발부터 다시 더한다.
        """
        clock: datetime | None = None
        for step in (s for leg in route.legs for s in leg.steps):
            if step.mode != "SUBWAY":
                continue  # 걷기는 더하지 않는다: 타고 온 열차의 도착 시각이 기준
            if clock is None:  # 첫 탑승
                clock = first + timedelta(seconds=step.duration_sec)
                continue
            trains = await self._transfer_trains(step, clock, now)
            step.board_after = clock
            step.transfer_trains = trains or None
            boarded = trains[0].departs_at if trains else clock
            clock = boarded + timedelta(seconds=step.duration_sec)

    async def _transfer_trains(
        self, step: Step, ready: datetime, now: datetime
    ) -> list[TransferTrain]:
        """[ready] 이후 그 역·방향 열차 2대.

        실시간(남은 시간으로 계산한 출발이 ready 뒤인 열차)을 먼저 쓰고, 모자라면 시간표로 채운다.
        """
        station_id, line = step.board_station_id, step.line_name
        stops = step.stops or []
        towards = (
            self._index.resolve(stops[1], line) if station_id and line and len(stops) > 1 else None
        )
        if towards is None or station_id is None or line is None:
            return []
        direction = self._index.direction(station_id, line, towards.id)
        found: list[TransferTrain] = []
        if self._arrivals is not None:
            try:
                arrivals, _ = await self._arrivals.get_arrivals(station_id, line=line)
            except AppError:
                arrivals = None
            for group in arrivals.groups if arrivals else []:
                same = group.towards and self._index.same_station(group.towards, towards.id)
                if not (same or (direction and group.direction == direction)):
                    continue
                for train in group.trains:
                    if train.remaining_sec is None:
                        continue
                    at = now + timedelta(seconds=train.remaining_sec)
                    if at >= ready:
                        found.append(
                            TransferTrain(
                                departs_at=at,
                                destination=train.destination,
                                source=group.source if group.source != "none" else "realtime",
                            )
                        )
                break
        if len(found) < TRAINS_PER_BOARDING and self._timetable is not None and direction:
            after = found[-1].departs_at + timedelta(seconds=60) if found else ready
            try:
                more = await self._timetable.trains(
                    station_id, line, direction, after, TRAINS_PER_BOARDING
                )
            except AppError:
                more = []
            found += [
                TransferTrain(
                    departs_at=t.departs_at, destination=t.destination, source="timetable"
                )
                for t in more
            ]
        found.sort(key=lambda t: t.departs_at)
        return found[:TRAINS_PER_BOARDING]

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
