"""역·호선·방향의 시간표 열차(재설계 스펙 5.2). 응답은 역 코드·요일·방향별로 하루 캐시한다."""

import asyncio
import time
from collections.abc import Callable
from datetime import datetime, timedelta

from app.cache.ttl_cache import TtlCache
from app.clients.seoul_timetable import SeoulTimetableClient
from app.core.errors import InvalidRequest, NotFound, UpstreamError, UpstreamFailure
from app.models.schemas import TimetableTrain
from app.services.normalize import normalize_line
from app.services.station_index import StationIndex
from app.services.timetable import departures, service_days, week_tag

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

    async def _rows(self, code: str, week: int, tag: int) -> tuple[list[dict], str]:
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
        return cached.value, cached.status

    async def trains(
        self, station_id: str, line: str, direction: str, after: datetime, count: int
    ) -> list[TimetableTrain]:
        """`after` 이후 열차 `count`대. 비서울 노선이나 역 코드가 없으면 빈 목록."""
        found, _ = await self.trains_with_status(station_id, line, direction, after, count)
        return found

    async def trains_with_status(
        self, station_id: str, line: str, direction: str, after: datetime, count: int
    ) -> tuple[list[TimetableTrain], str]:
        """[trains]와 같고, 두 번째 값은 캐시 상태(모두 캐시면 hit, 하나라도 새로 받았으면 miss)."""
        station = self._index.get(station_id)
        if station is None:
            raise NotFound()
        tag = inout_tag(direction)
        line = normalize_line(line)
        code = self._index.station_code(station.id, line)
        if code is None or not StationIndex.realtime_supported(line):
            return [], "none"
        days = service_days(after)
        results = await asyncio.gather(
            *(self._rows(code, week, tag) for _, week in days), return_exceptions=True
        )
        found: list[TimetableTrain] = []
        statuses: list[str] = []
        failures: list[UpstreamFailure] = []
        for (day, _), result in zip(days, results, strict=True):
            if isinstance(result, UpstreamFailure):
                # 새벽에는 전날·오늘 시간표를 함께 본다. 하나가 실패해도 나머지로 답한다.
                failures.append(result)
                continue
            if isinstance(result, BaseException):
                raise result
            rows, status = result
            statuses.append(status)
            found.extend(departures(rows, code, day, after, count))
        if not statuses:
            raise failures[0]
        if len(found) < count:
            # 밤늦게는 그날 막차 뒤에 다음 날 첫차가 온다(+1시간, 시각 선택, 루틴 미리보기).
            next_day = days[-1][0] + timedelta(days=1)
            try:
                rows, status = await self._rows(code, week_tag(next_day), tag)
            except UpstreamFailure:
                pass
            else:
                statuses.append(status)
                found.extend(departures(rows, code, next_day, after, count))
        found.sort(key=lambda t: t.departs_at)
        status = "hit" if all(s == "hit" for s in statuses) else "miss"
        return found[:count], status
