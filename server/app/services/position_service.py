"""실시간 열차 위치를 노선별로 받아 우리 역 id로 바꾼다(설계 2026-11-22 열차 위치 도착 계산).

- 노선별 20초 캐시(도착 API와 같은 값).
- 받기 실패·빈 응답은 빈 목록이다. 위치가 없으면 부르는 쪽은 지금처럼(도착 API·시간표) 답한다.
- 위치의 `statnTnm`은 종착역이다(도착 API와 뜻이 다르다).
"""

import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from app.cache.ttl_cache import TtlCache
from app.clients.seoul_position import SeoulPositionClient
from app.core.clock import KST, kst_from_epoch
from app.core.errors import UpstreamFailure
from app.services.measured import direction_name
from app.services.station_index import StationIndex

logger = logging.getLogger("app.positions")

POSITIONS_TTL_SEC = 20
STALE_SEC = 600  # 이보다 오래전에 만든 위치는 쓰지 않는다
_STATUSES = frozenset({0, 1, 2, 3})  # 진입, 도착, 출발, 전역출발


@dataclass(frozen=True)
class TrainPosition:
    line: str
    direction: str  # 상행·하행·내선·외선
    train_no: str
    station_id: str  # 지금 있는(다가가는) 우리 역 id
    status: int  # 0 진입, 1 도착, 2 출발, 3 전역출발
    seen_at: datetime
    dest_name: str | None  # 종착역
    express: bool  # directAt이 0이 아니면(급행·특급·ITX)


class PositionService:
    def __init__(
        self,
        client: SeoulPositionClient,
        index: StationIndex,
        cache: TtlCache[list[dict]],
        clock: Callable[[], float],
    ):
        self._client = client
        self._index = index
        self._cache = cache
        self._clock = clock

    async def positions(self, line: str) -> list[TrainPosition]:
        try:
            cached = await self._cache.get_or_fetch(
                line, lambda: self._client.fetch_positions(line)
            )
        except UpstreamFailure as e:
            logger.warning("위치 받기 실패 %s: %s", line, type(e).__name__)
            return []
        now = kst_from_epoch(self._clock())
        out = []
        for row in cached.value:
            position = self._parse(row, line, now)
            if position is not None:
                out.append(position)
        return out

    def _parse(self, row: dict, line: str, now: datetime) -> TrainPosition | None:
        try:
            status = int(str(row.get("trainSttus")))
            code = int(str(row.get("updnLine")))
            seen = datetime.strptime(str(row.get("recptnDt"))[:19], "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return None
        train_no = str(row.get("trainNo") or "")
        if status not in _STATUSES or code not in (0, 1) or not train_no:
            return None
        seen = min(seen.replace(tzinfo=KST), now)  # 미래 시각이면 지금으로
        if (now - seen).total_seconds() > STALE_SEC:
            return None
        station = self._index.resolve(str(row.get("statnNm") or ""), line)
        if station is None:
            return None
        return TrainPosition(
            line=line,
            direction=direction_name(line, code),
            train_no=train_no,
            station_id=station.id,
            status=status,
            seen_at=seen,
            dest_name=str(row.get("statnTnm") or "") or None,
            express=str(row.get("directAt") or "0") != "0",
        )
