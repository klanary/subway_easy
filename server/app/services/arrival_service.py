"""역 실시간 도착(스펙 6.2, 6.5, 6.7): 서울시 응답을 호선·방향별 다음 열차 2대로 정리한다."""

import logging
from collections.abc import Callable

from app.cache.ttl_cache import TtlCache
from app.clients.seoul_subway import SeoulSubwayClient
from app.core.clock import kst_from_epoch
from app.core.errors import NotFound
from app.models.schemas import ArrivalGroup, ArrivalsResponse, Train
from app.services.normalize import SUBWAY_ID_TO_LINE, normalize_line
from app.services.remaining_time import (
    compute_remaining_sec,
    is_departed,
    is_express,
    is_last,
    parse_train_line_nm,
)
from app.services.station_index import Station, StationIndex

logger = logging.getLogger("app.arrivals")

ARRIVALS_TTL_SEC = 20
TRAINS_PER_GROUP = 2


class ArrivalService:
    def __init__(
        self,
        client: SeoulSubwayClient,
        index: StationIndex,
        cache: TtlCache[list[dict]],
        clock: Callable[[], float],
    ):
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
        groups = self._group(cached.value, station)
        if line:
            wanted = normalize_line(line)
            groups = [g for g in groups if g.line == wanted]
        if towards:
            groups = [
                g for g in groups if g.towards and self._index.same_station(g.towards, towards)
            ]
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
                    destination=destination or item.get("bstatnNm") or None,
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
