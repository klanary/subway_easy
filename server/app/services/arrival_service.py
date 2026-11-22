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
from app.services.measured_store import MeasuredStore
from app.services.normalize import SUBWAY_ID_TO_LINE, normalize_line
from app.services.position_eta import estimate_arrival
from app.services.position_service import PositionService
from app.services.remaining_time import (
    compute_remaining_sec,
    has_exact_seconds,
    is_departed,
    is_express,
    is_last,
    is_stale,
    parse_train_line_nm,
)
from app.services.station_index import Station, StationIndex
from app.services.timetable_service import TimetableService

logger = logging.getLogger("app.arrivals")

ARRIVALS_TTL_SEC = 20
TRAINS_PER_GROUP = 2
# (역, 호선) → 그 역을 지나지 않는 행선지. 서울시 관리자 안내: 경의중앙선 서울역·신촌 지선에
# 덕소·팔당·용문·지평·청량리·용산행 본선 열차가 섞여 온다(2026-11-22 실제 응답으로도 확인).
_GYEONGUI_MAINLINE = frozenset({"덕소", "팔당", "용문", "지평", "청량리", "용산"})
BRANCH_SKIP: dict[tuple[str, str], frozenset[str]] = {
    ("서울", "경의중앙선"): _GYEONGUI_MAINLINE,
    ("신촌", "경의중앙선"): _GYEONGUI_MAINLINE,
}
FILL_BUDGET_SEC = 3.0  # 시간표 대체 전체에 쓰는 시간 상한


def directions(line: str) -> tuple[str, str]:
    """2호선은 내선·외선, 그 밖은 상행·하행(서울시 실시간 updnLine 표기)."""
    return ("내선", "외선") if line == "2호선" else ("상행", "하행")


def readable(name: str | None) -> str | None:
    """글자(한글·영문)가 없는 행선지("-2" 등, 2026-11-13 밤 실제 응답)는 모르는 것으로 본다."""
    if name and re.search(r"[가-힣A-Za-z]", name):
        return name
    return None


def timetable_train(t: TimetableTrain, now: datetime, basis: str = "timetable") -> Train:
    return Train(
        basis=basis,
        destination=t.destination,
        remaining_sec=max(0, int((t.departs_at - now).total_seconds())),
        message=f"{t.departs_at:%H:%M} 출발",
        is_last=False,
        is_express=t.is_express,
        train_no=t.train_no,
    )


def _bare_no(train_no: str | None) -> str:
    """도착 API와 위치 API의 열차 번호를 앞 0을 떼고 견준다("0145" = "145")."""
    return (train_no or "").lstrip("0")


class ArrivalService:
    def __init__(
        self,
        client: SeoulSubwayClient,
        index: StationIndex,
        cache: TtlCache[list[dict]],
        clock: Callable[[], float],
        timetable: TimetableService | None = None,
        positions: PositionService | None = None,
        measured: MeasuredStore | None = None,
        now_lines: frozenset[str] = frozenset(),
    ):
        self._timetable = timetable
        # 열차 위치 계산(설계 2026-11-22): now_lines 노선의 barvlDt 0 열차만 위치로 바꾼다.
        self._positions = positions
        self._measured = measured
        self._now_lines = now_lines
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
        queries = self._index.realtime_queries(station)
        base_name = queries[0][0]
        cached = await self._cache.get_or_fetch(
            base_name, lambda: self._client.fetch_arrivals(base_name)
        )
        answers = [(base_name, queries[0][1], cached.value)]
        for name, lines in queries[1:]:
            # 노선 전용 이름(서울역 GTX-A 등)은 실패해도 나머지 노선은 보인다.
            try:
                extra = await self._cache.get_or_fetch(
                    name, lambda n=name: self._client.fetch_arrivals(n)
                )
            except UpstreamFailure as e:
                logger.warning("노선 전용 조회 실패 %s: %s", name, e)
                continue
            answers.append((name, lines, extra.value))
        wanted = normalize_line(line) if line else None
        groups = self._group(answers, station)
        groups = await self._apply_positions(groups, station)
        if wanted:
            groups = [g for g in groups if g.line == wanted]
        # 호선을 거른 뒤에 채운다: 묻지 않은 호선의 시간표는 부르지 않는다.
        groups += await self._fill_missing(groups, station, wanted)
        if towards:
            groups = [g for g in groups if self._heads_towards(g, station, towards, wanted)]
        groups = [self._with_neighbors(g, station) for g in groups]
        response = ArrivalsResponse(
            station_id=station.id,
            updated_at=kst_from_epoch(cached.fetched_at),
            stale=cached.stale,
            groups=groups,
        )
        return response, cached.status

    def _group(
        self, answers: list[tuple[str, frozenset[str] | None, list[dict]]], station: Station
    ) -> list[ArrivalGroup]:
        now = kst_from_epoch(self._clock())
        own_lines = set(station.lines) if self._index.shares_api_name(station) else None
        grouped: dict[tuple[str, str, str | None], list[Train]] = {}
        # 같은 열차가 두 행(도착·출발 등)으로 오면 가장 최근에 만든 행 하나만 둔다.
        latest: dict[tuple[str, str, str], tuple[str, list[Train], int]] = {}
        for name, lines, items in answers:
            for item in items:
                line = SUBWAY_ID_TO_LINE.get(str(item.get("subwayId")))
                if line is None:
                    logger.warning("모르는 subwayId라 뺐어요: %s", item.get("subwayId"))
                    continue
                if own_lines is not None and line not in own_lines:
                    continue
                if lines is not None and line not in lines:
                    continue  # 노선 전용 이름으로 받을 호선은 그 이름의 응답에서만 받는다
                statn = item.get("statnNm")
                if statn and statn != name:
                    continue  # 조회한 역이 아닌 역의 행(인천 예술회관에서 김포공항·신창 열차)
                destination, towards = parse_train_line_nm(item.get("trainLineNm"))
                dest = readable(destination) or readable(item.get("bstatnNm"))
                if dest and self._index.same_station(dest, station.id):
                    continue  # 이 역에서 끝나는 열차는 탈 수 없다(서울역 공항철도 "서울행")
                if dest in BRANCH_SKIP.get((station.id, line), frozenset()):
                    continue  # 이 역을 지나지 않는 본선 열차(관리자 안내)
                key = (line, str(item.get("updnLine") or ""), towards)
                trains = grouped.setdefault(key, [])
                message = str(item.get("arvlMsg2") or "")
                if is_departed(message):
                    continue  # 이미 이 역을 떠난 열차(스펙 6.7 규칙 5)
                if is_stale(item, now):
                    continue  # 너무 오래전에 만든 정보: 이미 지나간 열차일 수 있다
                train = Train(
                    destination=dest,
                    remaining_sec=compute_remaining_sec(item, now),
                    message=message,
                    is_last=is_last(item),
                    is_express=is_express(item),
                    train_no=str(item.get("btrainNo") or "") or None,
                    current_station=readable(item.get("arvlMsg3")),
                    exact=has_exact_seconds(item),
                )
                made = str(item.get("recptnDt") or "")
                if train.train_no:
                    seen_key = (key[0], key[1], train.train_no)
                    seen = latest.get(seen_key)
                    if seen is not None:
                        if seen[0] < made:  # 더 최근 행으로 바꾼다
                            seen[1][seen[2]] = train
                            latest[seen_key] = (made, seen[1], seen[2])
                        continue
                    latest[seen_key] = (made, trains, len(trains))
                trains.append(train)
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

    async def _apply_positions(
        self, groups: list[ArrivalGroup], station: Station
    ) -> list[ArrivalGroup]:
        """켠 노선에서 초 단위 값이 없는 열차의 남은 시간을 열차 위치로 계산한다.

        위치를 못 받거나 계산하지 못하면 그 열차는 그대로(문구 어림, realtime)다.
        """
        if self._positions is None or self._measured is None:
            return groups
        lines = {
            g.line
            for g in groups
            if g.line in self._now_lines and any(not t.exact and t.train_no for t in g.trains)
        }
        data, trips = self._measured.current(), self._measured.current_trips()
        if not lines or data is None or trips is None:
            return groups
        now = kst_from_epoch(self._clock())
        out = []
        for group in groups:
            if group.line not in lines:
                out.append(group)
                continue
            by_no = {_bare_no(p.train_no): p for p in await self._positions.positions(group.line)}
            trains = []
            for train in group.trains:
                pos = None if train.exact else by_no.get(_bare_no(train.train_no))
                eta = (
                    estimate_arrival(pos, station.id, trips=trips, measured=data, now=now)
                    if pos is not None
                    else None
                )
                if eta is not None:
                    remaining = int((eta - now).total_seconds())
                    train = train.model_copy(
                        update={"remaining_sec": remaining, "basis": "position"}
                    )
                trains.append(train)
            trains.sort(key=lambda t: (t.remaining_sec is None, t.remaining_sec or 0))
            out.append(group.model_copy(update={"trains": trains}))
        return out

    def _with_neighbors(self, group: ArrivalGroup, station: Station) -> ArrivalGroup:
        """열차가 오는 쪽 역(반대 방향의 다음 역)과 가는 쪽 역을 붙인다. 모르면 비워 둔다."""
        if group.direction is None:
            return group
        pair = directions(group.line)
        if group.direction not in pair:
            return group
        opposite = pair[1] if group.direction == pair[0] else pair[0]

        def name(station_id: str | None) -> str | None:
            found = self._index.get(station_id) if station_id else None
            # 세 역을 한 줄에 놓으므로 괄호 속 부기명(충정로(경기대입구))은 뺀다
            return re.sub(r"\(.*\)$", "", found.name) if found else station_id

        return group.model_copy(
            update={
                "prev_station": name(self._index.neighbor(station.id, group.line, opposite)),
                "next_station": name(self._index.neighbor(station.id, group.line, group.direction)),
            }
        )

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
                        trains=[
                            timetable_train(
                                t, now, "measured" if found[0].source == "measured" else "timetable"
                            )
                            for t in found
                        ],
                        source="measured" if found[0].source == "measured" else "timetable",
                    )
                )
        return extra
