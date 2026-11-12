"""역 기준 데이터(stations.sqlite)를 메모리에 올려 찾기·검색·가까운 역을 제공한다 (스펙 6.5)."""

import re
import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from app.services.geo import haversine_m
from app.services.normalize import is_chosung_query, normalize_station_name

_NUMBERED_LINE = re.compile(r"^(\d+)호선$")

# 서울시 실시간 도착·시간표가 다루지 않는 노선(재설계 스펙 D4). 노선도에는 그리되 열차 정보는 없다.
NON_SEOUL_LINES: frozenset[str] = frozenset(
    {"인천1호선", "인천2호선", "에버라인선", "의정부선", "김포골드라인"}
)


@dataclass(frozen=True)
class Station:
    id: str
    name: str  # 화면에 보이는 이름(display_name)
    lines: tuple[str, ...]
    lat: float
    lng: float
    chosung: str
    api_name: str


def _line_order(line: str) -> tuple[int, int, str]:
    """숫자 호선을 번호순으로 먼저, 나머지는 이름순."""
    match = _NUMBERED_LINE.match(line)
    return (0, int(match.group(1)), "") if match else (1, 0, line)


def _rank(key: str, q: str) -> int | None:
    """0 완전 일치, 1 앞부분 일치, 2 포함. 맞지 않으면 None."""
    if key == q:
        return 0
    if key.startswith(q):
        return 1
    return 2 if q in key else None


class StationIndex:
    def __init__(
        self,
        stations: list[Station],
        aliases: dict[str, str],
        codes: dict[tuple[str, str], str] | None = None,
    ):
        self._by_id = {s.id: s for s in stations}
        self._codes = codes or {}
        self._aliases = aliases
        # 정규화한 화면 이름 → 그 이름을 쓰는 역들(나뉜 역 포함, id순)
        self._by_name: dict[str, list[Station]] = defaultdict(list)
        # 역 id → 검색 키(정규화 이름과 별칭들)
        self._search_keys: dict[str, list[str]] = defaultdict(list)
        api_name_counts: dict[str, int] = defaultdict(int)
        for station in sorted(stations, key=lambda s: s.id):
            base = normalize_station_name(station.name).id
            self._by_name[base].append(station)
            self._search_keys[station.id].append(base)
            api_name_counts[station.api_name] += 1
        for alias, station_id in aliases.items():
            self._search_keys[station_id].append(alias)
        self._shared_api_names = {name for name, n in api_name_counts.items() if n > 1}

    @classmethod
    def load(cls, db_path: str | Path) -> "StationIndex":
        con = sqlite3.connect(f"file:{Path(db_path).as_posix()}?mode=ro", uri=True)
        try:
            lines: dict[str, set[str]] = defaultdict(set)
            for station_id, line_name in con.execute(
                "SELECT station_id, line_name FROM station_lines"
            ):
                lines[station_id].add(line_name)
            stations = [
                Station(
                    id=id_,
                    name=name,
                    lines=tuple(sorted(lines[id_], key=_line_order)),
                    lat=lat,
                    lng=lng,
                    chosung=chosung,
                    api_name=api_name,
                )
                for id_, name, lat, lng, chosung, api_name in con.execute(
                    "SELECT id, display_name, lat, lng, chosung, api_name FROM stations"
                )
            ]
            aliases = dict(con.execute("SELECT alias, station_id FROM aliases"))
            codes = {
                (station_id, line): code
                for station_id, line, code in con.execute(
                    "SELECT station_id, line_name, station_cd FROM station_codes"
                )
            }
        finally:
            con.close()
        return cls(stations, aliases, codes)

    def get(self, station_id: str) -> Station | None:
        return self._by_id.get(station_id)

    def all(self) -> list[Station]:
        return list(self._by_id.values())

    def station_code(self, station_id: str, line: str) -> str | None:
        """서울시 시간표 API의 역 코드(STATION_CD). 없으면 None."""
        return self._codes.get((station_id, line))

    @staticmethod
    def realtime_supported(line: str) -> bool:
        return line not in NON_SEOUL_LINES

    def resolve(self, raw_name: str, line: str | None = None) -> Station | None:
        """다른 출처(경로 응답, 실시간 응답)의 역 이름을 역으로 바꾼다. 못 찾으면 None."""
        normalized = normalize_station_name(raw_name)
        for key in (normalized.id, *normalized.aliases):
            candidates = list(self._by_name.get(key, ()))
            if not candidates and key in self._aliases:
                candidates = [self._by_id[self._aliases[key]]]
            if not candidates:
                continue
            if line is not None:
                for station in candidates:
                    if line in station.lines:
                        return station
            return candidates[0]
        return None

    def same_station(self, a: str, b: str) -> bool:
        first, second = self.resolve(a), self.resolve(b)
        if first is not None and second is not None:
            return first.id == second.id
        return normalize_station_name(a).id == normalize_station_name(b).id

    def search(self, q: str, limit: int) -> list[Station]:
        if is_chosung_query(q):
            query = "".join(q.split())
            keys_of = lambda s: [s.chosung]  # noqa: E731
        else:
            query = normalize_station_name(q).id
            keys_of = lambda s: self._search_keys[s.id]  # noqa: E731
        ranked: list[tuple[int, int, str, Station]] = []
        for station in self._by_id.values():
            ranks = [r for key in keys_of(station) if (r := _rank(key, query)) is not None]
            if ranks:
                ranked.append((min(ranks), -len(station.lines), station.name, station))
        ranked.sort(key=lambda item: item[:3])
        return [item[3] for item in ranked[:limit]]

    def nearest(self, lat: float, lng: float, max_m: float = 2000) -> tuple[Station, int] | None:
        best: tuple[Station, float] | None = None
        for station in self._by_id.values():
            distance = haversine_m(lat, lng, station.lat, station.lng)
            if distance <= max_m and (best is None or distance < best[1]):
                best = (station, distance)
        return (best[0], round(best[1])) if best else None

    def shares_api_name(self, station: Station) -> bool:
        """같은 조회 이름을 쓰는 역이 또 있는지(양평). 실시간 응답에서 호선으로 걸러야 한다."""
        return station.api_name in self._shared_api_names
