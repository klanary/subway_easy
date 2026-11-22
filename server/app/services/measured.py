"""VM이 만든 실측 결과물(B1 실측 시간표, B2 구간 시간)을 조회한다(설계 2026-11-17 §4.3).

외부 호출 없는 순수 모듈이다. 받기는 `measured_store`가 한다.

- B1 칸 키: `요일구분|호선|방향(0 상행·내선, 1 하행·외선)|statn_id`. 시각은 운행일 0시부터 초
  (24시를 넘으면 86400 이상).
- statn_id는 결과물의 `stations`로 우리 역 id에 잇는다. 못 이은 칸은 버린다.
- 변경 의심(`suspect`)·재학습 중(`retraining`)인 단위(요일|호선|방향|시간대)의 칸은 내지 않는다.
"""

import sys
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta

from app.core.clock import KST
from app.models.schemas import TimetableTrain
from app.services.timetable import service_days, week_tag

# 서버용 사본(ml/publish.server_b1·server_b2). 시간표 API가 없는 노선만, 필요한 칸만 담는다
# (최종 리뷰 #1: 전체 B1을 그대로 올리면 Render 512MB를 넘는다).
B1_KIND, B1_VERSION = "b1-server", 1
B2_KIND, B2_VERSION = "b2-server", 1
_EXCLUDED_STATES = frozenset({"suspect", "retraining"})
_HOP_SERVICE_DAY_START_HOUR = 1  # 수집기는 01시 전 이벤트를 전날 운행일로 센다


def direction_name(line: str, code: int) -> str:
    if line == "2호선":
        return "내선" if code == 0 else "외선"
    return "상행" if code == 0 else "하행"


def direction_code(direction: str) -> int:
    return 0 if direction in ("상행", "내선") else 1


@dataclass(frozen=True, slots=True)
class _Entry:
    secs: int
    train_no: str | None
    dest_name: str | None
    express: bool


Key = tuple[int, str, str, str]  # (요일 구분, 호선, 방향 이름, 우리 역 id)


@dataclass(frozen=True)
class MeasuredData:
    as_of: date
    built_at: str
    modes: Mapping[str, str]
    cells: Mapping[Key, tuple[_Entry, ...]]
    statn_of: Mapping[tuple[str, str], str]  # (호선, 우리 역 id) → statn_id
    hops: Mapping[str, Mapping[str, float]] = field(default_factory=dict)  # 표 이름 → 키 → 중앙값
    # 변경 의심·재학습으로 뺀 단위(요일|호선|방향 코드|시). 그 시간대는 "모름"이다.
    excluded: frozenset[str] = frozenset()

    @classmethod
    def from_artifacts(cls, b1: dict, b2: dict | None) -> "MeasuredData":
        """형식이 맞지 않으면 ValueError."""
        if b1.get("kind") != B1_KIND or b1.get("version") != B1_VERSION:
            raise ValueError("B1 결과물 형식이 다릅니다")
        if b2 is not None and (b2.get("kind") != B2_KIND or b2.get("version") != B2_VERSION):
            raise ValueError("B2 결과물 형식이 다릅니다")
        stations = b1.get("stations") or {}
        dests = [sys.intern(name) for name in b1.get("dests") or []]
        excluded = {
            key for key, state in (b1.get("scopes") or {}).items() if state in _EXCLUDED_STATES
        }
        cells: dict[Key, list[_Entry]] = {}
        statn_of: dict[tuple[str, str], str] = {}
        for key, entries in (b1.get("cells") or {}).items():
            day_type, line, code, statn_id = key.split("|")
            station = stations.get(f"{line}|{statn_id}") or {}
            station_id = station.get("station_id")
            if not station_id:
                continue
            statn_of[(line, station_id)] = statn_id
            kept = []
            for secs, train_no, dest, express in entries:
                dest_name = dests[dest] if dest >= 0 else None
                if f"{day_type}|{line}|{code}|{secs // 3600}" in excluded:
                    continue
                if dest_name is not None and dest_name == station.get("name"):
                    continue  # 이 역이 종착인 열차
                number = sys.intern(train_no) if train_no else None  # 역마다 같은 번호가 되풀이된다
                kept.append(_Entry(secs, number, dest_name, bool(express)))
            name = direction_name(line, int(code))
            cells[(int(day_type), line, name, station_id)] = tuple(
                sorted(kept, key=lambda x: x.secs)
            )
        hops = {}
        if b2 is not None:
            hops = {name: b2.get(name) or {} for name in ("hops_hour", "hops_day", "hops")}
        return cls(
            as_of=date.fromisoformat(b1["as_of"]),
            built_at=str(b1.get("built_at") or ""),
            modes=dict(b1.get("modes") or {}),
            cells=cells,
            statn_of=statn_of,
            hops=hops,
            excluded=frozenset(excluded),
        )

    def departures(
        self, station_id: str, line: str, direction: str, after: datetime, count: int
    ) -> list[TimetableTrain]:
        """`after` 이후 그 역을 떠나는 실측 열차 `count`대. 모자라면 다음 운행일에서 채운다."""
        days = [day for day, _ in service_days(after)]
        if not any(self.cells.get((week_tag(d), line, direction, station_id)) for d in days):
            # 오늘 요일 구분의 실측이 없다(주말 실측이 아직 없을 때 등). 다음 날 첫차를 다음 열차로
            # 주지 않는다(2026-11-22 일요일 낮에 "05:00 출발"이 다음 열차로 보였다).
            return []
        found = self._departures_on(days, station_id, line, direction, after)
        if len(found) < count and not self._excluded_ahead(days[-1], line, direction, after):
            # 막차가 지난 밤에만 다음 날 첫차로 채운다. 앞으로의 시간대가 빠져 있으면 모르는 것이다.
            found += self._departures_on(
                [days[-1] + timedelta(days=1)], station_id, line, direction, after
            )
        found.sort(key=lambda t: t.departs_at)
        return found[:count]

    def _excluded_ahead(self, day: date, line: str, direction: str, after: datetime) -> bool:
        start = datetime.combine(day, time(0), tzinfo=KST)
        hour = max(0, int((after - start).total_seconds() // 3600))
        code = direction_code(direction)
        return any(f"{week_tag(day)}|{line}|{code}|{h}" in self.excluded for h in range(hour, 30))

    def _departures_on(
        self, days: list[date], station_id: str, line: str, direction: str, after: datetime
    ) -> list[TimetableTrain]:
        trains = []
        for day in days:
            start = datetime.combine(day, time(0), tzinfo=KST)
            for entry in self.cells.get((week_tag(day), line, direction, station_id), ()):
                departs_at = start + timedelta(seconds=entry.secs)
                if departs_at < after:
                    continue
                trains.append(
                    TimetableTrain(
                        departs_at=departs_at,
                        destination=entry.dest_name,
                        is_express=entry.express,
                        train_no=entry.train_no,
                        source="measured",
                    )
                )
        return trains

    def arrival_at(
        self, line: str, direction: str, train_no: str, station_id: str, service_day: date
    ) -> datetime | None:
        """그 열차가 그 역을 떠나는 실측 시각. 열차 번호로 묶지 않는 호선이거나 못 찾으면 None."""
        if self.modes.get(line) != "train_no":
            return None
        start = datetime.combine(service_day, time(0), tzinfo=KST)
        for entry in self.cells.get((week_tag(service_day), line, direction, station_id), ()):
            if entry.train_no == train_no:
                return start + timedelta(seconds=entry.secs)
        return None

    def hop_seconds(
        self, line: str, direction: str, from_id: str, to_id: str, *, express: bool, at: datetime
    ) -> float | None:
        """이웃한 두 역 사이 실측 이동 시간(중앙값). 시간대 → 요일 → 전체 순으로 있는 것."""
        a, b = self.statn_of.get((line, from_id)), self.statn_of.get((line, to_id))
        if a is None or b is None:
            return None
        local = at.astimezone(KST)
        day = local.date()
        if local.hour < _HOP_SERVICE_DAY_START_HOUR:
            day -= timedelta(days=1)
        base = f"{line}|{direction_code(direction)}|{a}|{b}|{1 if express else 0}"
        day_type = week_tag(day)
        for table, key in (
            ("hops_hour", f"{base}|{day_type}|{local.hour}"),
            ("hops_day", f"{base}|{day_type}"),
            ("hops", base),
        ):
            found = (self.hops.get(table) or {}).get(key)
            if found:
                return float(found)
        return None
