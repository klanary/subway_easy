"""역에서 역까지 지하철 경로(경로 설계 2026-11-18).

VM이 만든 열차 운행표로 RAPTOR 검색을 해 구간마다 실제로 탈 열차를 정한다. 카카오도 서울시도
부르지 않는다. 첫 탑승역의 "다음 열차"도 운행표의 고른 열차와 그다음 열차다(최종 리뷰 #2).
요금은 보이지 않는다(`fare`는 늘 null). 도보 단계도 없다.
"""

import asyncio
import hashlib
import json
import logging
from collections.abc import Callable
from datetime import datetime

from app.core.clock import kst_from_epoch
from app.core.errors import InvalidRequest, NoRoute, NotFound, RoutesNotReady
from app.models.schemas import Candidate, FirstBoarding, Leg, Route, RoutesResponse, Step, Train
from app.services.candidates import candidates
from app.services.geo import haversine_m
from app.services.measured_store import MeasuredStore
from app.services.raptor import Departure, Journey, Ride, pareto, search
from app.services.station_index import Station, StationIndex

logger = logging.getLogger("app.routes")

VIA_TOP = 3  # 경유 구간마다 조합에 쓰는 경로 수(3 × 3)


class RouteService:
    def __init__(
        self,
        index: StationIndex,
        measured: MeasuredStore,
        clock: Callable[[], float],
    ):
        self._index = index
        self._measured = measured
        self._clock = clock

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
        # 검색과 후보 모으기는 다른 스레드에서(경유면 여러 번 돈다. 요청 처리를 막지 않게)
        journeys = await asyncio.to_thread(
            self._search, trips, start.id, via.id if via else None, end.id, depart
        )
        if not journeys:
            raise NoRoute("NO_SUBWAY")
        routes = [self._route(j, split, cands, now) for j, split, cands in journeys]
        return RoutesResponse(updated_at=now, stale=False, routes=routes), "none"

    def _search(
        self, trips, start: str, via: str | None, end: str, depart: datetime
    ) -> list[tuple[Journey, int | None, list[list[Departure]]]]:
        found = (
            [(j, None) for j in search(trips, start, end, depart)]
            if via is None
            else self._via(trips, start, via, end, depart)
        )
        return [(j, split, [candidates(trips, r) for r in j.rides]) for j, split in found]

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

    def _step(self, ride: Ride, cands: list[Departure]) -> Step:
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
            candidates=[
                Candidate(
                    train_no=c.train_no,
                    departs_at=c.departs_at,
                    arrives_at=c.arrives_at or c.departs_at,
                    is_express=c.express,
                    destination=c.dest,
                    source=c.source,
                    estimated=c.estimated,
                )
                for c in cands
            ],
        )

    def _route(
        self, journey: Journey, split: int | None, cands: list[list[Departure]], now: datetime
    ) -> Route:
        steps = [self._step(r, c) for r, c in zip(journey.rides, cands, strict=True)]
        legs = (
            [Leg(steps=steps)]
            if split is None
            else [Leg(steps=steps[:split]), Leg(steps=steps[split:])]
        )
        first = journey.rides[0]
        # 같은 길이면 출발 시각이 달라도 같은 id(루틴 편집이 id로 경로를 다시 찾는다)
        key = [[r.line, r.direction, r.board_station, r.alight_station] for r in journey.rides]
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
                next_trains=[
                    Train(
                        destination=d.dest,
                        remaining_sec=max(0, int((d.departs_at - now).total_seconds())),
                        message=f"{d.departs_at:%H:%M} 출발",
                        is_last=False,
                        is_express=d.express,
                        train_no=d.train_no,
                    )
                    for d in first.departures
                ]
                or None,
            ),
            depart_at=journey.departs_at,
            arrive_at=journey.arrives_at,
        )
