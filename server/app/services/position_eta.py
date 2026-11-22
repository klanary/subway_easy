"""열차 위치 + 실측 구간 시간으로 이 역 도착 시각을 계산한다(설계 2026-11-22 §6, 방식 A).

외부 호출이 없는 순수 함수다.
서버(`ArrivalService`)와 되돌려 보기(`ml/position_eval.py`)가 같이 쓴다.

- 경로는 실측 운행 패턴(`TripsData`)으로 찾는다: 같은 호선·방향이고 지금 역 뒤에 목적역이 오며,
  그 패턴의 운행 중 급행 여부·행선지가 이 열차와 같은 것이 있는 패턴만 본다. 경로가 둘 이상 갈리면
  (갈래) 계산하지 않는다.
- 구간 시간은 `MeasuredData.hop_seconds`(도착 → 다음 도착, 정차 포함).
  하나라도 없으면 계산하지 않는다.
"""

from datetime import datetime, timedelta
from functools import lru_cache

from app.core.clock import KST
from app.services.measured import MeasuredData
from app.services.normalize import normalize_station_name
from app.services.position_service import TrainPosition
from app.services.timetable import week_tag
from app.services.trips import DayTrips, TripsData

# 위치 행의 지금 역에 도착하는(했던) 시각 = seen_at + LEAD_SEC[상태].
# 출발(2)은 0이다: 위치 API가 이동 중인 열차를 다음 역 "출발"로 먼저 잡는 순서 뒤틀림이 있어
# (11-20 2호선 1.4%), 정차분을 빼면 시각이 앞당겨졌다가 다음 갱신에 뒤로 튄다.
LEAD_SEC: dict[int, int] = {0: 20, 1: 0, 2: 0, 3: 90}
_SERVICE_DAY_START_HOUR = 1  # 01시 전은 전날 운행


def _same_name(a: str | None, b: str | None) -> bool:
    if not a or not b:
        return False
    return normalize_station_name(a).id == normalize_station_name(b).id


def day_type_at(now: datetime) -> int:
    local = now.astimezone(KST)
    day = local.date()
    if local.hour < _SERVICE_DAY_START_HOUR:
        day -= timedelta(days=1)
    return week_tag(day)


def _find_path(
    day: DayTrips,
    line: str,
    direction: str,
    from_id: str,
    to_id: str,
    *,
    dest_name: str | None,
    express: bool,
) -> list[str] | None:
    """`from_id` → `to_id` 정차 목록(양 끝 포함). 없거나 갈래로 갈리면 None."""
    start, end = day.stop_index(from_id), day.stop_index(to_id)
    if start is None or end is None:
        return None
    found: set[tuple[int, ...]] = set()
    for p, pos in day.patterns_at(start):
        pattern = day.patterns[p]
        if pattern.line != line or pattern.direction != direction:
            continue
        try:
            later = pattern.stops.index(end, pos + 1)
        except ValueError:
            continue
        if not any(
            pattern.trip_meta(t).express == express
            and (dest_name is None or _same_name(pattern.trip_meta(t).dest, dest_name))
            for t in range(pattern.n_trips)
        ):
            continue
        found.add(pattern.stops[pos : later + 1])
    if len(found) != 1:
        return None
    (stops,) = found
    return [day.station_id(s) for s in stops]


@lru_cache(maxsize=8192)
def _cached_path(
    day: DayTrips,
    line: str,
    direction: str,
    from_id: str,
    to_id: str,
    dest_name: str | None,
    express: bool,
) -> list[str] | None:
    return _find_path(day, line, direction, from_id, to_id, dest_name=dest_name, express=express)


def path_between(
    day: DayTrips,
    line: str,
    direction: str,
    from_id: str,
    to_id: str,
    *,
    dest_name: str | None,
    express: bool,
) -> list[str] | None:
    """`from_id` → `to_id` 정차 목록(양 끝 포함). 없거나 갈래로 갈리면 None.

    요청마다 노선의 열차 수만큼 부르므로 같은 질문은 기억해 둔다.
    운행표가 바뀌면 다른 DayTrips라 새로 찾는다.
    돌려준 목록은 고치지 않는다.
    """
    return _cached_path(day, line, direction, from_id, to_id, dest_name, express)


def estimate_arrival(
    pos: TrainPosition,
    target_id: str,
    *,
    trips: TripsData,
    measured: MeasuredData,
    now: datetime,
) -> datetime | None:
    """이 열차가 `target_id`에 도착할 시각. 계산할 수 없으면 None."""
    if _same_name(pos.dest_name, target_id):
        return None  # 이 역에서 끝나는 열차는 탈 수 없다
    at_current = pos.seen_at + timedelta(seconds=LEAD_SEC.get(pos.status, 0))
    if pos.station_id == target_id:
        if pos.status == 2:
            return None  # 이미 떠났다
        return max(now, at_current)
    day = trips.day(day_type_at(now))
    path = path_between(
        day,
        pos.line,
        pos.direction,
        pos.station_id,
        target_id,
        dest_name=pos.dest_name,
        express=pos.express,
    )
    if path is None:
        return None
    total = 0.0
    for a, b in zip(path, path[1:], strict=False):
        hop = measured.hop_seconds(pos.line, pos.direction, a, b, express=pos.express, at=now)
        if hop is None:
            return None
        total += hop
    return max(now, at_current + timedelta(seconds=total))
