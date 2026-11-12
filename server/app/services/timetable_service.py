"""역·호선·방향의 시간표 열차(재설계 스펙 5.2). 응답은 역 코드·요일·방향별로 하루 캐시한다."""

from datetime import datetime

from app.cache.ttl_cache import TtlCache
from app.clients.seoul_timetable import SeoulTimetableClient
from app.core.errors import InvalidRequest, NotFound
from app.models.schemas import TimetableTrain
from app.services.normalize import normalize_line
from app.services.station_index import StationIndex
from app.services.timetable import departures, service_days

TIMETABLE_TTL_SEC = 86_400
INOUT_TAGS = {"상행": 1, "내선": 1, "하행": 2, "외선": 2}


def inout_tag(direction: str) -> int:
    try:
        return INOUT_TAGS[direction]
    except KeyError as e:
        raise InvalidRequest("PARAMS") from e


class TimetableService:
    def __init__(
        self, client: SeoulTimetableClient, index: StationIndex, cache: TtlCache[list[dict]]
    ):
        self._client = client
        self._index = index
        self._cache = cache

    async def trains(
        self, station_id: str, line: str, direction: str, after: datetime, count: int
    ) -> list[TimetableTrain]:
        """`after` 이후 열차 `count`대. 비서울 노선이나 역 코드가 없으면 빈 목록."""
        station = self._index.get(station_id)
        if station is None:
            raise NotFound()
        tag = inout_tag(direction)
        line = normalize_line(line)
        code = self._index.station_code(station.id, line)
        if code is None or not StationIndex.realtime_supported(line):
            return []
        found: list[TimetableTrain] = []
        for day, week in service_days(after):
            cached = await self._cache.get_or_fetch(
                f"{code}|{week}|{tag}", lambda w=week: self._client.fetch(code, w, tag)
            )
            found.extend(departures(cached.value, code, day, after, count))
        found.sort(key=lambda t: t.departs_at)
        return found[:count]
