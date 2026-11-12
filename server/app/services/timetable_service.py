"""역·호선·방향의 시간표 열차(재설계 스펙 5.2). 응답은 역 코드·요일·방향별로 하루 캐시한다."""

import asyncio
import time
from collections.abc import Callable
from datetime import datetime

from app.cache.ttl_cache import TtlCache
from app.clients.seoul_timetable import SeoulTimetableClient
from app.core.errors import InvalidRequest, NotFound, UpstreamError, UpstreamFailure
from app.models.schemas import TimetableTrain
from app.services.normalize import normalize_line
from app.services.station_index import StationIndex
from app.services.timetable import departures, service_days

TIMETABLE_TTL_SEC = 86_400
FAILURE_MEMORY_SEC = 60  # 실패한 조회는 이 동안 다시 부르지 않는다(한도와 응답 시간을 아낀다)
INOUT_TAGS = {"상행": 1, "내선": 1, "하행": 2, "외선": 2}


def inout_tag(direction: str) -> int:
    try:
        return INOUT_TAGS[direction]
    except KeyError as e:
        raise InvalidRequest("PARAMS") from e


class TimetableService:
    def __init__(
        self,
        client: SeoulTimetableClient,
        index: StationIndex,
        cache: TtlCache[list[dict]],
        clock: Callable[[], float] = time.time,
    ):
        self._client = client
        self._index = index
        self._cache = cache
        self._clock = clock
        self._failed_until: dict[str, float] = {}

    async def _rows(self, code: str, week: int, tag: int) -> list[dict]:
        key = f"{code}|{week}|{tag}"
        if self._failed_until.get(key, 0) > self._clock():
            raise UpstreamError(reason="RECENT_FAILURE")
        try:
            cached = await self._cache.get_or_fetch(
                key, lambda: self._client.fetch(code, week, tag)
            )
        except UpstreamFailure:
            self._failed_until[key] = self._clock() + FAILURE_MEMORY_SEC
            raise
        return cached.value

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
        days = service_days(after)
        tables = await asyncio.gather(*(self._rows(code, week, tag) for _, week in days))
        found: list[TimetableTrain] = []
        for (day, _), rows in zip(days, tables, strict=True):
            found.extend(departures(rows, code, day, after, count))
        found.sort(key=lambda t: t.departs_at)
        return found[:count]
