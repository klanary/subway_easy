"""역 실시간 도착(스펙 6.2, 6.5, 6.7): 서울시 응답을 호선·방향별 다음 열차 2대로 정리한다."""

import asyncio
import logging
import re
from collections.abc import Callable
from datetime import datetime

from app.cache.ttl_cache import TtlCache
from app.clients.seoul_subway import SeoulSubwayClient
from app.core.clock import kst_from_epoch
from app.core.errors import NotFound, UpstreamFailure
from app.models.schemas import ArrivalGroup, ArrivalsResponse, TimetableTrain, Train
from app.services.normalize import SUBWAY_ID_TO_LINE, normalize_line
from app.services.remaining_time import (
    compute_remaining_sec,
    is_departed,
    is_express,
    is_last,
    parse_train_line_nm,
)
from app.services.station_index import Station, StationIndex
from app.services.timetable_service import TimetableService

logger = logging.getLogger("app.arrivals")

ARRIVALS_TTL_SEC = 20
TRAINS_PER_GROUP = 2
FILL_BUDGET_SEC = 3.0  # 시간표 대체 전체에 쓰는 시간 상한


def directions(line: str) -> tuple[str, str]:
    """2호선은 내선·외선, 그 밖은 상행·하행(서울시 실시간 updnLine 표기)."""
    return ("내선", "외선") if line == "2호선" else ("상행", "하행")


def readable(name: str | None) -> str | None:
    """글자(한글·영문)가 없는 행선지("-2" 등, 2026-11-13 밤 실제 응답)는 모르는 것으로 본다."""
    if name and re.search(r"[가-힣A-Za-z]", name):
        return name
    return None


def timetable_train(t: TimetableTrain, now: datetime) -> Train:
    return Train(
        destination=t.destination,
        remaining_sec=max(0, int((t.departs_at - now).total_seconds())),
        message=f"{t.departs_at:%H:%M} 출발",
        is_last=False,
        is_express=t.is_express,
        train_no=t.train_no,
    )


class ArrivalService:
    def __init__(
        self,
        client: SeoulSubwayClient,
        index: StationIndex,
        cache: TtlCache[list[dict]],
        clock: Callable[[], float],
        timetable: TimetableService | None = None,
    ):
        self._timetable = timetable
        self._client = client
        self._index = index
        self._cache = cache
        self._clock = clock

    async def get_arrivals(
        self, station_id: str, line: str | None = None, towards: str | None = None
    ) -> tuple[ArrivalsResponse, str]:
        """두 번째 값은 캐시 상태(hit·miss·stale)다."""
        station = self._index.get(station_id) or self._index.resolve(station_id)
        if station is None:
            raise NotFound()
        # 같은 조회 이름을 쓰는 역(양평)은 한 번만 부르도록 조회 이름을 캐시 키로 쓴다.
        cached = await self._cache.get_or_fetch(
            station.api_name, lambda: self._client.fetch_arrivals(station.api_name)
        )
        wanted = normalize_line(line) if line else None
        groups = self._group(cached.value, station)
        if wanted:
            groups = [g for g in groups if g.line == wanted]
        # 호선을 거른 뒤에 채운다: 묻지 않은 호선의 시간표는 부르지 않는다.
        groups += await self._fill_missing(groups, station, wanted)
        if towards:
            groups = [g for g in groups if self._heads_towards(g, station, towards, wanted)]
        response = ArrivalsResponse(
            station_id=station.id,
            updated_at=kst_from_epoch(cached.fetched_at),
            stale=cached.stale,
            groups=groups,
        )
        return response, cached.status

    def _group(self, items: list[dict], station: Station) -> list[ArrivalGroup]:
        now = kst_from_epoch(self._clock())
        own_lines = set(station.lines) if self._index.shares_api_name(station) else None
        grouped: dict[tuple[str, str, str | None], list[Train]] = {}
        for item in items:
            line = SUBWAY_ID_TO_LINE.get(str(item.get("subwayId")))
            if line is None:
                logger.warning("모르는 subwayId라 뺐어요: %s", item.get("subwayId"))
                continue
            if own_lines is not None and line not in own_lines:
                continue
            destination, towards = parse_train_line_nm(item.get("trainLineNm"))
            trains = grouped.setdefault((line, str(item.get("updnLine") or ""), towards), [])
            message = str(item.get("arvlMsg2") or "")
            if is_departed(message):
                continue  # 이미 이 역을 떠난 열차(스펙 6.7 규칙 5)
            trains.append(
                Train(
                    destination=readable(destination) or readable(item.get("bstatnNm")),
                    remaining_sec=compute_remaining_sec(item, now),
                    message=message,
                    is_last=is_last(item),
                    is_express=is_express(item),
                    train_no=str(item.get("btrainNo") or "") or None,
                )
            )
        result = []
        for (line, direction, towards), trains in grouped.items():
            trains.sort(key=lambda t: (t.remaining_sec is None, t.remaining_sec or 0))
            if trains:
                result.append(
                    ArrivalGroup(
                        line=line,
                        direction=direction,
                        towards=towards,
                        trains=trains[:TRAINS_PER_GROUP],
                    )
                )
        return result

    def _heads_towards(
        self, group: ArrivalGroup, station: Station, towards: str, line: str | None
    ) -> bool:
        """실시간 그룹은 방면 이름으로, 시간표 그룹(방면 없음)은 그 방면 쪽 방향으로 맞춘다."""
        if group.towards:
            return self._index.same_station(group.towards, towards)
        if group.source != "timetable":
            return False
        target = self._index.resolve(towards, line or group.line)
        if target is None:
            return False
        return group.direction == self._index.direction(station.id, group.line, target.id)

    async def _fill_missing(
        self, groups: list[ArrivalGroup], station: Station, only_line: str | None = None
    ) -> list[ArrivalGroup]:
        """실시간 그룹이 없는 방향은 시간표로, 비서울 노선은 빈 "none" 그룹으로 채운다.

        시간표는 방향마다 동시에 묻고, 모두 합쳐 FILL_BUDGET_SEC 안에 끝나지 않으면 건너뛴다
        (서울시 API가 느려도 도착 응답이 늦어지지 않게).
        """
        have = {(g.line, g.direction) for g in groups}
        now = kst_from_epoch(self._clock())
        lines = [x for x in station.lines if only_line is None or x == only_line]
        extra = [
            ArrivalGroup(line=x, direction=None, towards=None, trains=[], source="none")
            for x in lines
            if not StationIndex.realtime_supported(x)
        ]
        if self._timetable is None:
            return extra
        wanted = [
            (x, d)
            for x in lines
            if StationIndex.realtime_supported(x)
            for d in directions(x)
            if (x, d) not in have
        ]
        if not wanted:
            return extra
        calls = [self._timetable.trains(station.id, x, d, now, TRAINS_PER_GROUP) for x, d in wanted]
        try:
            results = await asyncio.wait_for(
                asyncio.gather(*calls, return_exceptions=True), FILL_BUDGET_SEC
            )
        except TimeoutError:
            logger.warning(
                "시간표 대체가 %s초 안에 끝나지 않아 건너뛰었어요: %s", FILL_BUDGET_SEC, station.id
            )
            return extra
        for (x, d), found in zip(wanted, results, strict=True):
            if isinstance(found, UpstreamFailure):
                logger.warning("시간표 대체 실패 %s %s: %s", x, d, found.reason)
                continue
            if isinstance(found, BaseException):
                raise found
            if found:
                extra.append(
                    ArrivalGroup(
                        line=x,
                        direction=d,
                        towards=None,
                        trains=[timetable_train(t, now) for t in found],
                        source="timetable",
                    )
                )
        return extra
