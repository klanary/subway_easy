"""역에서 역까지 지하철 경로(경로 설계 2026-11-18).

VM이 만든 열차 운행표로 RAPTOR 검색을 해 구간마다 실제로 탈 열차를 정한다. 카카오도 서울시도
부르지 않는다(지금 출발이면 첫 탑승역의 실시간 다음 열차만 표시용으로 붙인다).
요금은 보이지 않는다(`fare`는 늘 null). 도보 단계도 없다.
"""

import asyncio
import hashlib
import json
import logging
from collections.abc import Callable
from datetime import datetime

from app.core.clock import kst_from_epoch
from app.core.errors import AppError, InvalidRequest, NoRoute, NotFound, RoutesNotReady
from app.models.schemas import FirstBoarding, Leg, Route, RoutesResponse, Step, Train
from app.services.arrival_service import ArrivalService
from app.services.geo import haversine_m
from app.services.measured_store import MeasuredStore
from app.services.raptor import Journey, Ride, pareto, search
from app.services.station_index import Station, StationIndex

logger = logging.getLogger("app.routes")

VIA_TOP = 3  # 경유 구간마다 조합에 쓰는 경로 수(3 × 3)
TRAINS_PER_BOARDING = 2


class RouteService:
    def __init__(
        self,
        index: StationIndex,
        measured: MeasuredStore,
        clock: Callable[[], float],
        arrivals: ArrivalService | None = None,
    ):
        self._index = index
        self._measured = measured
        self._clock = clock
        self._arrivals = arrivals

    def _station(self, station_id: str) -> Station:
        station = self._index.get(station_id)
        if station is None:
            raise NotFound()
        return station

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
        for station in (start, end, *([via] if via else [])):
            if not any(StationIndex.realtime_supported(line) for line in station.lines):
                raise NoRoute("NO_SUBWAY")  # 비서울 노선만 있는 역(운행표에 없음)
        trips = self._measured.current_trips()
        if trips is None:
            raise RoutesNotReady()
        now = kst_from_epoch(self._clock())
        depart = depart_at or now
        if via is None:
            journeys = [(j, None) for j in search(trips, start.id, end.id, depart)]
        else:
            journeys = self._via(trips, start.id, via.id, end.id, depart)
        if not journeys:
            raise NoRoute("NO_SUBWAY")
        routes = [self._route(j, split) for j, split in journeys]
        if depart_at is None and self._arrivals is not None:
            await asyncio.gather(
                *(
                    self._attach_next_trains(r, j)
                    for r, (j, _) in zip(routes, journeys, strict=True)
                )
            )
        return RoutesResponse(updated_at=now, stale=False, routes=routes), "none"

    def _via(
        self, trips, start: str, via: str, end: str, depart: datetime
    ) -> list[tuple[Journey, int]]:
        combined: dict[int, tuple[Journey, int]] = {}
        for first in search(trips, start, via, depart)[:VIA_TOP]:
            for second in search(trips, via, end, first.arrives_at)[:VIA_TOP]:
                journey = Journey([*first.rides, *second.rides])
                combined[id(journey)] = (journey, len(first.rides))
        kept = {id(j) for j in pareto([j for j, _ in combined.values()])}
        return [combined[k] for k in combined if k in kept]

    def _name(self, station_id: str) -> str:
        station = self._index.get(station_id)
        return station.name if station else station_id

    def _step(self, ride: Ride) -> Step:
        coords = [
            (s.lat, s.lng) for s in (self._index.get(sid) for sid in ride.stops) if s is not None
        ]
        distance = sum(
            haversine_m(a[0], a[1], b[0], b[1]) for a, b in zip(coords, coords[1:], strict=False)
        )
        names = [self._name(sid) for sid in ride.stops]
        return Step(
            mode="SUBWAY",
            duration_sec=int((ride.arrives_at - ride.departs_at).total_seconds()),
            distance_m=round(distance),
            guidance=f"{ride.line} ({names[0]} > {names[-1]})",
            path=coords,
            line_name=ride.line,
            stops=names,
            board_station_id=ride.board_station,
            alight_station_id=ride.alight_station,
            departs_at=ride.departs_at,
            arrives_at=ride.arrives_at,
            train_no=ride.train_no,
            is_express=ride.express,
            destination=ride.dest,
            source=ride.source,
            estimated=ride.estimated,
        )

    def _route(self, journey: Journey, split: int | None) -> Route:
        steps = [self._step(r) for r in journey.rides]
        legs = (
            [Leg(steps=steps)]
            if split is None
            else [Leg(steps=steps[:split]), Leg(steps=steps[split:])]
        )
        first = journey.rides[0]
        key = [
            [r.line, r.train_no, r.board_station, r.alight_station, r.departs_at.isoformat()]
            for r in journey.rides
        ]
        return Route(
            id="r_" + hashlib.sha1(json.dumps(key, ensure_ascii=False).encode()).hexdigest()[:8],
            type="SUBWAY",
            total_sec=int((journey.arrives_at - journey.departs_at).total_seconds()),
            distance_m=sum(s.distance_m for s in steps),
            transfers=journey.transfers,
            fare=None,
            legs=legs,
            first_boarding=FirstBoarding(
                station_id=first.board_station,
                line_name=first.line,
                towards=self._name(first.stops[1]) if len(first.stops) > 1 else None,
                sec_to_boarding=0,
                all_walk_before_boarding=True,
            ),
            depart_at=journey.departs_at,
            arrive_at=journey.arrives_at,
        )

    async def _attach_next_trains(self, route: Route, journey: Journey) -> None:
        """지금 출발이면 첫 탑승역의 실시간 다음 열차(그 방향 2대)를 붙인다. 실패하면 없이 간다."""
        ride = journey.rides[0]
        boarding = route.first_boarding
        if boarding is None or self._arrivals is None:
            return
        try:
            arrivals, _ = await self._arrivals.get_arrivals(ride.board_station, line=ride.line)
        except AppError as e:
            logger.warning("첫 탑승 열차 조회 실패 %s: %s", ride.board_station, e.code)
            return
        towards = ride.stops[1] if len(ride.stops) > 1 else None
        for group in arrivals.groups:
            same_way = (
                group.towards and towards and self._index.same_station(group.towards, towards)
            )
            if same_way or group.direction == ride.direction:
                trains: list[Train] = group.trains[:TRAINS_PER_BOARDING]
                boarding.next_trains = trains or None
                return
