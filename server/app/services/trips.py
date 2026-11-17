"""서버 운행표 색인(경로 설계 2026-11-18 §5.2). VM `ml/trips.py`가 만든 `trips-server`를 읽는다.

- 패턴 = 같은 정차 순서를 가진 운행 묶음. 운행은 첫 출발 순이고 서로 앞지르지 않으므로,
  어느 정차에서든 출발 시각이 운행 순서대로 커진다(이분 탐색이 된다).
- 시각은 패턴마다 `array('i')` 하나(운행 × 정차 × [도착, 출발]). 객체를 운행·정차마다 만들지 않는다
  (작업 1 최종 리뷰: 무료 서버 512MB).
- 시각은 운행일 0시부터 초(24시 넘으면 86400 이상).
"""

from array import array
from bisect import bisect_left
from collections import defaultdict
from dataclasses import dataclass
from datetime import date

KIND, VERSION = "trips-server", 1
_SOURCES = ("official", "measured")


@dataclass(frozen=True, slots=True)
class TripMeta:
    train_no: str | None
    express: bool
    dest: str | None
    source: str  # "official" | "measured"
    estimated: bool


class Pattern:
    __slots__ = ("line", "direction", "stops", "n_trips", "times", "_meta", "_deps")

    def __init__(self, line: str, direction: str, stops: tuple[int, ...], rows: list[list]):
        self.line = line
        self.direction = direction
        self.stops = stops
        self.n_trips = len(rows)
        self.times = array("i")
        self._meta: list[TripMeta] = []
        for train_no, express, dest, source, estimated, times in rows:
            if len(times) != 2 * len(stops):
                raise ValueError("운행 시각 수가 정차 수와 맞지 않습니다")
            self.times.extend(times)
            self._meta.append(
                TripMeta(train_no, bool(express), dest, _SOURCES[source], bool(estimated))
            )
        self._deps: dict[int, array] = {}

    def arr(self, trip: int, pos: int) -> int:
        return self.times[(trip * len(self.stops) + pos) * 2]

    def dep(self, trip: int, pos: int) -> int:
        return self.times[(trip * len(self.stops) + pos) * 2 + 1]

    def trip_meta(self, trip: int) -> TripMeta:
        return self._meta[trip]

    def earliest_trip(self, pos: int, after: int) -> int | None:
        """`pos` 정차를 `after` 이후(같은 초 포함)에 떠나는 첫 운행. 없으면 None."""
        deps = self._deps.get(pos)
        if deps is None:
            deps = array("i", (self.dep(t, pos) for t in range(self.n_trips)))
            self._deps[pos] = deps
        found = bisect_left(deps, after)
        return found if found < self.n_trips else None


class DayTrips:
    def __init__(self, stations: list[str], patterns: list[Pattern]):
        self._stations = stations
        self._index = {station: i for i, station in enumerate(stations)}
        self.patterns = patterns
        by_stop: dict[int, list[tuple[int, int]]] = defaultdict(list)
        for p, pattern in enumerate(patterns):
            for pos, stop in enumerate(pattern.stops):
                by_stop[stop].append((p, pos))
        self._by_stop = dict(by_stop)

    def patterns_at(self, stop: int) -> list[tuple[int, int]]:
        """그 정차를 지나는 (패턴 번호, 패턴 안 정차 위치)."""
        return self._by_stop.get(stop, [])

    def stop_index(self, station_id: str) -> int | None:
        return self._index.get(station_id)

    def station_id(self, stop: int) -> str:
        return self._stations[stop]


class TripsData:
    def __init__(self, built_on: date, days: dict[int, DayTrips], stations: list[str]):
        self.built_on = built_on
        self._days = days
        self._empty = DayTrips(stations, [])

    @classmethod
    def from_artifact(cls, data: dict) -> "TripsData":
        if data.get("kind") != KIND or data.get("version") != VERSION:
            raise ValueError("운행표 형식이 다릅니다")
        stations = list(data["stations"])
        days = {
            int(day_type): DayTrips(
                stations,
                [
                    Pattern(p["line"], p["direction"], tuple(p["stops"]), p["trips"])
                    for p in patterns
                ],
            )
            for day_type, patterns in (data.get("day_types") or {}).items()
        }
        return cls(date.fromisoformat(data["built_on"]), days, stations)

    def day(self, day_type: int) -> DayTrips:
        return self._days.get(day_type, self._empty)
