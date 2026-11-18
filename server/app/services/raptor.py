"""운행표 RAPTOR 경로 검색(경로 설계 2026-11-18 §5.2). 외부 호출 없는 순수 모듈.

- 정류장 = 우리 역 id(호선 무관). 같은 역에서 내린 시각 이후(같은 초 포함) 떠나는 열차로 갈아탄다
  (환승 걷는 시간 0, 사용자 결정 2026-11-17).
- 라운드 k = 열차 k대를 탄 것. 최대 5라운드(환승 4번).
- 결과는 환승 횟수별 가장 이른 도착 중 파레토(환승을 늘려도 더 빨라지지 않으면 뺌), 최대 5개.
- 운행일: 05시 전이면 전날 운행일도 본다. 그날 못 닿으면 다음 운행일 첫차부터 한 번 더.
"""

from dataclasses import dataclass, replace
from datetime import date, datetime, time, timedelta

from app.core.clock import KST
from app.services.timetable import service_days, week_tag
from app.services.trips import DayTrips, TripsData

MAX_ROUNDS = 5
MAX_RESULTS = 5
CONTINUE_SEC = 300  # 같은 노선·방향으로 이만큼 안에 이어지면 열차 번호만 바뀐 것(2호선 성수)
_INF = 10**9


@dataclass(frozen=True)
class Departure:
    """같은 패턴에서 탈 역을 떠나는 열차: 고른 열차와 그다음 열차.

    앱의 "다음 열차"와 루틴 미리 보기가 쓴다.
    """

    departs_at: datetime
    train_no: str | None
    dest: str | None
    express: bool


@dataclass(frozen=True)
class Ride:
    line: str
    direction: str
    train_no: str | None
    express: bool
    dest: str | None
    source: str
    estimated: bool
    board_station: str
    alight_station: str
    departs_at: datetime
    arrives_at: datetime
    stops: list[str]
    departures: tuple[Departure, ...] = ()


@dataclass(frozen=True)
class Journey:
    rides: list[Ride]

    @property
    def departs_at(self) -> datetime:
        return self.rides[0].departs_at

    @property
    def arrives_at(self) -> datetime:
        return self.rides[-1].arrives_at

    @property
    def transfers(self) -> int:
        return len(self.rides) - 1


# 라운드 k에 어느 정차에 닿게 한 승차: (기록한 라운드, 패턴, 운행, 탄 위치, 내린 위치)
_Parent = tuple[int, int, int, int, int]


def _raptor(
    day: DayTrips, origin: int, dest: int, t0: int, max_rounds: int
) -> list[tuple[int, list[tuple[int, int, int, int]]]]:
    """(도착 초, [(패턴, 운행, 탄 위치, 내린 위치), ...]) 목록. 라운드(열차 수)가 늘어나는 순."""
    best: dict[int, int] = {origin: t0}
    arrivals: dict[int, int] = {origin: t0}  # 직전 라운드까지의 도착
    parents: dict[int, _Parent] = {}
    by_round: list[dict[int, _Parent]] = [{}]  # 라운드마다 그때까지의 승차 기록(되짚기용)
    marked = {origin}
    found: list[tuple[int, list[tuple[int, int, int, int]]]] = []
    for k in range(1, max_rounds + 1):
        queue: dict[int, int] = {}
        for stop in marked:
            for p, pos in day.patterns_at(stop):
                if pos < queue.get(p, _INF):
                    queue[p] = pos
        current = dict(arrivals)
        new_parents = dict(parents)
        marked = set()
        for p, start in queue.items():
            pattern = day.patterns[p]
            trip: int | None = None
            board = -1
            for pos in range(start, len(pattern.stops)):
                stop = pattern.stops[pos]
                if trip is not None:
                    a = pattern.arr(trip, pos)
                    if a < best.get(stop, _INF) and a < best.get(dest, _INF):
                        best[stop] = a
                        current[stop] = a
                        new_parents[stop] = (k, p, trip, board, pos)
                        marked.add(stop)
                prev = arrivals.get(stop)
                if prev is not None and (trip is None or prev <= pattern.dep(trip, pos)):
                    earlier = pattern.earliest_trip(pos, prev)
                    if earlier is not None and (trip is None or earlier < trip):
                        trip, board = earlier, pos
        arrivals, parents = current, new_parents
        by_round.append(new_parents)
        if dest in marked:
            found.append((arrivals[dest], _backtrack(day, by_round, dest, k)))
        if not marked:
            break
    return found


def _backtrack(
    day: DayTrips, by_round: list[dict[int, _Parent]], dest: int, k: int
) -> list[tuple[int, int, int, int]]:
    """라운드 k의 도착에서 거꾸로. 탄 역은 그 승차가 쓴 직전 라운드의 기록으로 되짚는다
    (같은 라운드에 그 역의 도착이 더 빨라져 기록이 바뀌었을 수 있다)."""
    rides = []
    stop = dest
    while k > 0 and stop in by_round[k]:
        rec_round, p, trip, board, alight = by_round[k][stop]
        rides.append((p, trip, board, alight))
        stop = day.patterns[p].stops[board]
        k = rec_round - 1
    rides.reverse()
    return rides


FOLLOWING = 2  # 고른 열차를 포함해 보여 줄 출발 수


def _latest_boarding(
    day: DayTrips, legs: list[tuple[int, int, int, int]]
) -> list[tuple[int, int, int, int]]:
    """도착은 그대로 두고, 앞 구간들을 다음 구간에 대어 가는 가장 늦은 열차로 다시 고른다.

    RAPTOR는 탈 수 있는 가장 이른 열차를 고르므로, 그대로 두면 필요보다 일찍 나가 환승역에서
    기다리라고 안내한다(최종 리뷰). 한 패턴의 운행은 앞지르지 않으므로 뒤 운행일수록 늦게 닿는다.
    """
    legs = list(legs)
    for i in range(len(legs) - 2, -1, -1):
        p, trip, board, alight = legs[i]
        np_, ntrip, nboard, _ = legs[i + 1]
        catch = day.patterns[np_].dep(ntrip, nboard)
        pattern = day.patterns[p]
        while trip + 1 < pattern.n_trips and pattern.arr(trip + 1, alight) <= catch:
            trip += 1
        legs[i] = (p, trip, board, alight)
    return legs


def _to_journey(day: DayTrips, service_day: date, legs: list[tuple[int, int, int, int]]) -> Journey:
    start = datetime.combine(service_day, time(0), tzinfo=KST)
    rides = []
    for p, trip, board, alight in _latest_boarding(day, legs):
        pattern = day.patterns[p]
        meta = pattern.trip_meta(trip)
        rides.append(
            Ride(
                line=pattern.line,
                direction=pattern.direction,
                train_no=meta.train_no,
                express=meta.express,
                dest=meta.dest,
                source=meta.source,
                estimated=meta.estimated,
                board_station=day.station_id(pattern.stops[board]),
                alight_station=day.station_id(pattern.stops[alight]),
                departs_at=start + timedelta(seconds=pattern.dep(trip, board)),
                arrives_at=start + timedelta(seconds=pattern.arr(trip, alight)),
                stops=[day.station_id(s) for s in pattern.stops[board : alight + 1]],
                departures=tuple(
                    Departure(
                        departs_at=start + timedelta(seconds=pattern.dep(t, board)),
                        train_no=pattern.trip_meta(t).train_no,
                        dest=pattern.trip_meta(t).dest,
                        express=pattern.trip_meta(t).express,
                    )
                    for t in range(trip, min(trip + FOLLOWING, pattern.n_trips))
                ),
            )
        )
    return Journey(rides)


def _merge_continuations(journey: Journey) -> Journey:
    """같은 노선·방향으로 내린 역에서 곧바로 이어 타는 것은 한 번 탄 것으로 합친다.

    2호선 내선은 성수에서 열차 번호가 바뀌어 운행표에서는 두 운행이지만, 같은 차량이 계속 달린다.
    """
    rides: list[Ride] = []
    for ride in journey.rides:
        prev = rides[-1] if rides else None
        if (
            prev is not None
            and prev.line == ride.line
            and prev.direction == ride.direction
            and prev.alight_station == ride.board_station
            and 0 <= (ride.departs_at - prev.arrives_at).total_seconds() <= CONTINUE_SEC
        ):
            rides[-1] = replace(
                prev,
                alight_station=ride.alight_station,
                arrives_at=ride.arrives_at,
                stops=prev.stops + ride.stops[1:],
                estimated=prev.estimated or ride.estimated,
            )
        else:
            rides.append(ride)
    return Journey(rides)


def _run_day(
    trips: TripsData, service_day: date, origin: str, dest: str, t0: int, max_rounds: int
) -> list[Journey]:
    day = trips.day(week_tag(service_day))
    o, d = day.stop_index(origin), day.stop_index(dest)
    if o is None or d is None:
        return []
    return [
        _merge_continuations(_to_journey(day, service_day, legs))
        for _, legs in _raptor(day, o, d, t0, max_rounds)
    ]


def pareto(journeys: list[Journey]) -> list[Journey]:
    """환승 횟수별 가장 이른 도착 중, 환승을 늘려도 더 빨라지지 않는 것은 뺀다. 최대 5개."""
    by_transfers: dict[int, Journey] = {}
    for j in journeys:
        kept = by_transfers.get(j.transfers)
        if kept is None or (j.arrives_at, j.departs_at) < (kept.arrives_at, kept.departs_at):
            by_transfers[j.transfers] = j
    out: list[Journey] = []
    for transfers in sorted(by_transfers):
        j = by_transfers[transfers]
        if not out or j.arrives_at < out[-1].arrives_at:
            out.append(j)
    return out[:MAX_RESULTS]


def search(
    trips: TripsData, origin: str, dest: str, depart: datetime, *, max_rounds: int = MAX_ROUNDS
) -> list[Journey]:
    """`depart` 이후 출발해 `dest`에 닿는 경로들(환승 적은 순). 못 찾으면 빈 목록."""
    if origin == dest:
        return []
    local = depart.astimezone(KST)
    days = [day for day, _ in service_days(local)]
    found: list[Journey] = []
    for day in days:
        start = datetime.combine(day, time(0), tzinfo=KST)
        t0 = int((local - start).total_seconds())
        found += _run_day(trips, day, origin, dest, t0, max_rounds)
    if not found:
        found = _run_day(trips, days[-1] + timedelta(days=1), origin, dest, 0, max_rounds)
    return pareto(found)
