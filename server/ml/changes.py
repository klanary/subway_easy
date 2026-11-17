"""시간표 변경 감지: 하루치 관측을 기준 실측 시간표(B1)와 견줘 바뀐 단위를 찾는다.

단위(scope)는 (요일 구분, 호선, 방향, 1시간대)다. 신호 다섯 가지 가운데 하나라도 기준을
넘으면 그 날 그 단위는 "의심"이고, 다음 같은 요일 구분의 날도 같은 모양이면 "확정"이다
(지연은 날마다 다르고, 바뀐 시간표는 날마다 같다).

기준값은 지연 분포를 모르는 채 정한 초안이다(계획 Task 9에서 자료로 맞춘다).
"""

import argparse
import json
import os
import shutil
import sys
from collections import defaultdict
from collections.abc import Callable, Iterable
from collections.abc import Set as Set
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

from app.core.clock import KST
from ml.b0 import B0, TIMETABLE_LINES, load_diff_log
from ml.b1 import WINDOW, Departure, Entry, build_b1_from_points
from ml.points import PointStore
from ml.scopes import Scope, band_of, merge_bands, parse_scope_key, scope_key


@dataclass(frozen=True)
class Thresholds:
    late_sec: int = 120  # 이보다 크게 어긋나면 "다른 시각"
    shifted: float = 0.30  # 같은 번호끼리 어긋난 비율
    moved: float = 0.30  # 번호 없이 시각만 짝지었을 때 짝 없는 비율
    numbers: float = 0.20  # 사라지거나 새로 생긴 번호의 비율
    tph: float = 0.20  # 시간당 출발 수의 변화
    tph_min_trains: int = 2  # 그리고 역당 이만큼 이상 차이
    express: float = 0.15  # 급행 비율의 차
    agree_max: float = 0.50  # 두 날이 "서로 맞다"고 볼 어긋남의 한도
    min_ref: int = 20  # 기준 쪽 출발이 이보다 적은 단위는 판단하지 않는다
    min_coverage: int = 96  # 시간대의 정상 응답 수(기대 120회의 80%)
    streak: int = 3  # 서로 안 맞아도 이만큼 연속 의심이면 확정
    give_up: int = 4  # 이만큼 판단 못 하면 의심을 내려놓는다
    # 공식 시간표 후보 판정(ml.official). 모두 초안이다.
    same_sec: int = 20  # 옛·새 공식 시각이 이 안이면 "그대로"인 자리
    tie_sec: int = 20  # 옛·새까지의 거리 차가 이보다 작으면 표가 아니다
    vote_reach_sec: int = 300  # 옛·새 어느 쪽에도 이 안에 없으면 표가 아니다
    delay_reach_sec: int = 180  # 평소 지연을 구할 때 관측과 "그대로" 자리를 잇는 거리
    base_reach_sec: int = 60  # "그대로" 자리에 관측이 있다고 보는 거리(지연을 뺀 뒤)
    base_fit: float = 0.60  # 그 비율이 이보다 낮으면 공식 시간표가 이 단위를 설명하지 못한다
    base_min_slots: int = 10  # "그대로" 자리가 이보다 적으면 위 확인을 하지 않는다
    min_votes: int = 20  # 표가 이보다 적으면 정하지 않는다
    new_share: float = 0.70  # 새 쪽 표가 이 비율 이상이면 확정
    old_share: float = 0.30  # 이 비율 이하면 공식 시간표가 먼저 바뀐 것
    tries_weekday: int = 5  # 평일 후보를 이만큼 판정하고도 못 정하면 만료
    tries_other: int = 3  # 토요일·휴일 후보


DEFAULT = Thresholds()


@dataclass(frozen=True)
class ScopeStats:
    scope: Scope
    n_ref: int
    n_obs: int
    n_stations: int
    shifted: float | None
    numbers: float | None
    moved: float
    tph_ratio: float
    express_delta: float


def cells_of(artifact: dict) -> dict[str, list[Entry]]:
    """B1 결과물의 cells를 Entry로 읽는다."""
    return {key: [Entry(**e) for e in entries] for key, entries in artifact["cells"].items()}


def _unmatched(ref: list[int], obs: list[int], late_sec: int) -> tuple[list[int], list[int]]:
    """시각순으로 훑으며 late_sec 안의 것끼리 짝짓고, 짝 없는 것을 돌려준다."""
    lone_ref: list[int] = []
    lone_obs: list[int] = []
    i = j = 0
    while i < len(ref) and j < len(obs):
        if abs(ref[i] - obs[j]) <= late_sec:
            i += 1
            j += 1
        elif ref[i] < obs[j]:
            lone_ref.append(ref[i])
            i += 1
        else:
            lone_obs.append(obs[j])
            j += 1
    return lone_ref + ref[i:], lone_obs + obs[j:]


def scope_stats(
    cells: dict[str, list[Entry]],
    obs: list[Departure],
    *,
    mode: str,
    th: Thresholds = DEFAULT,
) -> dict[Scope, ScopeStats]:
    """한 호선·한 날의 출발(`obs`)을 기준표(`cells`)와 견준 단위별 수치.

    시간대는 기준 쪽은 기준 시각, 관측 쪽은 관측 시각으로 정한다.
    기준 쪽 출발이 `th.min_ref` 미만인 단위는 결과에 없다.
    """
    by_number = mode == "train_no"
    obs_by_cell: dict[tuple, list[Departure]] = defaultdict(list)
    for p in obs:
        obs_by_cell[(p.day_type, p.line, p.direction, p.statn_id)].append(p)
    if not obs_by_cell:
        return {}
    day_types = {key[0] for key in obs_by_cell}
    lines = {key[1] for key in obs_by_cell}

    count: dict[Scope, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    stations: dict[Scope, set[str]] = defaultdict(set)
    ref_cells: dict[tuple, list[Entry]] = {}
    for key, entries in cells.items():
        day_type, line, direction, statn_id = key.split("|")
        if int(day_type) in day_types and line in lines:
            ref_cells[(int(day_type), line, int(direction), statn_id)] = entries

    for cell in set(ref_cells) | set(obs_by_cell):
        day_type, line, direction, statn_id = cell
        ref = sorted(ref_cells.get(cell, []), key=lambda e: e.secs)
        seen = sorted(obs_by_cell.get(cell, []), key=lambda p: p.secs)

        def scope_at(secs: int, _cell=cell) -> Scope:
            return (_cell[0], _cell[1], _cell[2], band_of(secs))

        for e in ref:
            c = count[scope_at(e.secs)]
            c["n_ref"] += 1
            c["ref_express"] += e.express > 0
            stations[scope_at(e.secs)].add(statn_id)
        for p in seen:
            c = count[scope_at(p.secs)]
            c["n_obs"] += 1
            c["obs_express"] += p.express > 0

        if by_number:
            ref_no = {e.train_no: e for e in ref if e.train_no is not None}
            obs_no: dict[str, Departure] = {}
            for p in seen:
                obs_no.setdefault(p.train_no, p)
            for number, e in ref_no.items():
                c = count[scope_at(e.secs)]
                c["numbered_ref"] += 1
                p = obs_no.get(number)
                if p is None:
                    c["gone"] += 1
                else:
                    c["pairs"] += 1
                    c["late"] += abs(p.secs - e.secs) > th.late_sec
            for number, p in obs_no.items():
                if number not in ref_no:
                    count[scope_at(p.secs)]["new"] += 1

        lone_ref, lone_obs = _unmatched([e.secs for e in ref], [p.secs for p in seen], th.late_sec)
        for secs in lone_ref:
            count[scope_at(secs)]["lone"] += 1
        for secs in lone_obs:
            count[scope_at(secs)]["lone"] += 1

    out: dict[Scope, ScopeStats] = {}
    for scope, c in count.items():
        n_ref, n_obs = int(c["n_ref"]), int(c["n_obs"])
        if n_ref < th.min_ref:
            continue
        number_base = c["numbered_ref"] + c["new"]
        out[scope] = ScopeStats(
            scope=scope,
            n_ref=n_ref,
            n_obs=n_obs,
            n_stations=len(stations[scope]),
            shifted=c["late"] / c["pairs"] if by_number and c["pairs"] else None,
            numbers=(c["gone"] + c["new"]) / number_base if by_number and number_base else None,
            moved=c["lone"] / (n_ref + n_obs),
            tph_ratio=n_obs / n_ref,
            express_delta=(c["obs_express"] / n_obs if n_obs else 0.0) - c["ref_express"] / n_ref,
        )
    return out


def _tph_over(stats: ScopeStats, th: Thresholds) -> bool:
    diff = abs(stats.n_obs - stats.n_ref)
    # 비율 비교는 정수로 한다(0.2 × 30 같은 값이 부동소수 오차를 타지 않게).
    return (
        diff * 100 >= round(th.tph * 100) * stats.n_ref
        and diff >= th.tph_min_trains * stats.n_stations
    )


def triggers(stats: ScopeStats, th: Thresholds = DEFAULT) -> tuple[str, ...]:
    """기준을 넘은 신호 이름(순서: shifted, numbers, moved, tph, express)."""
    names: list[str] = []
    if stats.shifted is not None and stats.shifted >= th.shifted:
        names.append("shifted")
    if stats.numbers is not None and stats.numbers >= th.numbers:
        names.append("numbers")
    if stats.moved >= th.moved:
        names.append("moved")
    if _tph_over(stats, th):
        names.append("tph")
    if abs(stats.express_delta) >= th.express - 1e-9:
        names.append("express")
    return tuple(names)


def kind_of(names: tuple[str, ...]) -> str:
    """변경의 종류: frequency(배차), pattern(급행 비율), renumber(번호만), retime(시각)."""
    if "tph" in names:
        return "frequency"
    if "express" in names:
        return "pattern"
    if "numbers" in names and "moved" not in names:
        return "renumber"
    return "retime"


def agree(
    first_day: list[Departure],
    today: list[Departure],
    *,
    mode: str,
    th: Thresholds = DEFAULT,
) -> dict[Scope, bool]:
    """첫 의심일 하루치를 기준표로 삼아 오늘과 견준다. 단위별로 "서로 맞는가"."""
    if not first_day:
        return {}
    lines = {p.line for p in first_day}
    reference = build_b1_from_points(first_day, modes={line: mode for line in lines}, min_days=1)
    result: dict[Scope, bool] = {}
    for scope, stats in scope_stats(cells_of(reference), today, mode=mode, th=th).items():
        result[scope] = (
            (stats.shifted is None or stats.shifted < th.agree_max)
            and stats.moved < th.agree_max
            and (stats.numbers is None or stats.numbers < th.numbers)
            and not _tph_over(stats, th)
            and abs(stats.express_delta) < th.express - 1e-9
        )
    return result


# --- 의심 → 확정 상태, 상태 파일, 변경 기록 ---

STATE_VERSION = 2  # 2: candidates(공식 시간표 후보), delays(평소 지연)
OFFICIAL_CHANGED_MIN = 0.10  # 공식 시간표가 이 비율 이상 달라진 단위를 "바뀜"으로 본다
OFFICIAL_FIT_AFTER_DAYS = 3  # 확정 뒤 이만큼 지나서 공식 시간표가 따라왔는지 본다
CHANGED_SHOW_DAYS = 14  # 확정 뒤 이 기간은 결과물에 "changed"로 표시한다
CHANGES_KEEP_DAYS = 60  # 결과물의 changes에 남기는 기간
DAY_TYPE_NAMES = {1: "평일", 2: "토요일", 3: "휴일"}
STATUS_NAMES = {"suspect": "의심", "confirmed": "확정", "cleared": "해제"}
_SIGNAL_LABELS = (
    ("shifted", "어긋남 {:.0%}"),
    ("numbers", "번호 {:.0%}"),
    ("moved", "짝 없음 {:.0%}"),
    ("tph", "배차 ×{:.2f}"),
    ("express", "급행 비율 {:+.2f}"),
)


class CorruptState(Exception):
    """상태 파일을 읽을 수 없다. 덮어쓰지 않고 멈춰야 한다."""


@dataclass
class ChangeRecord:
    scope: Scope
    status: str  # suspect | confirmed | cleared
    first_date: date
    days: list[date]  # 의심으로 판단된 날들
    missed: list[date]  # 수집이 비어 판단하지 못한 날들
    decided_date: date | None
    kind: str
    triggers: tuple[str, ...]
    signals: dict[str, float | None]
    source: str  # measured | official | manual
    official: str = "unknown"  # unknown | changed | fits | stale
    note: str = ""

    def to_json(self) -> dict:
        return {
            "scope": scope_key(self.scope),
            "status": self.status,
            "first_date": self.first_date.isoformat(),
            "days": [d.isoformat() for d in self.days],
            "missed": [d.isoformat() for d in self.missed],
            "decided_date": self.decided_date.isoformat() if self.decided_date else None,
            "kind": self.kind,
            "triggers": list(self.triggers),
            "signals": self.signals,
            "source": self.source,
            "official": self.official,
            "note": self.note,
        }

    @classmethod
    def from_json(cls, data: dict) -> "ChangeRecord":
        return cls(
            scope=parse_scope_key(data["scope"]),
            status=data["status"],
            first_date=date.fromisoformat(data["first_date"]),
            days=[date.fromisoformat(d) for d in data["days"]],
            missed=[date.fromisoformat(d) for d in data["missed"]],
            decided_date=date.fromisoformat(data["decided_date"]) if data["decided_date"] else None,
            kind=data["kind"],
            triggers=tuple(data["triggers"]),
            signals=dict(data["signals"]),
            source=data["source"],
            official=data.get("official", "unknown"),
            note=data.get("note", ""),
        )


@dataclass
class Candidate:
    """공식 시간표가 달라진 단위. 관측으로 확인될 때까지는 후보일 뿐이다."""

    scope: Scope
    found_on: date  # 공식 시간표를 받은 날
    old_cache: str  # 옛 캐시 폴더 이름
    ratio: float
    status: str = "open"  # open | confirmed | expired | superseded
    tries: list[tuple[date, str]] = field(default_factory=list)  # (판정한 날, 결과)
    closed_on: date | None = None
    note: str = ""

    def to_json(self) -> dict:
        return {
            "scope": scope_key(self.scope),
            "found_on": self.found_on.isoformat(),
            "old_cache": self.old_cache,
            "ratio": self.ratio,
            "status": self.status,
            "tries": [[day.isoformat(), result] for day, result in self.tries],
            "closed_on": self.closed_on.isoformat() if self.closed_on else None,
            "note": self.note,
        }

    @classmethod
    def from_json(cls, data: dict) -> "Candidate":
        return cls(
            scope=parse_scope_key(data["scope"]),
            found_on=date.fromisoformat(data["found_on"]),
            old_cache=data["old_cache"],
            ratio=float(data["ratio"]),
            status=data["status"],
            tries=[(date.fromisoformat(day), str(result)) for day, result in data["tries"]],
            closed_on=date.fromisoformat(data["closed_on"]) if data.get("closed_on") else None,
            note=data.get("note", ""),
        )


@dataclass
class ChangeState:
    records: list[ChangeRecord] = field(default_factory=list)
    checked: dict[str, date] = field(default_factory=dict)  # "호선|day_type" → 마지막 판단일
    candidates: list[Candidate] = field(default_factory=list)
    delays: dict[str, int] = field(
        default_factory=dict
    )  # "호선|방향" → 공식 시각 대비 평소 지연(초)

    def to_json(self) -> dict:
        return {
            "version": STATE_VERSION,
            "checked": {key: day.isoformat() for key, day in sorted(self.checked.items())},
            "records": [record.to_json() for record in self.records],
            "candidates": [candidate.to_json() for candidate in self.candidates],
            "delays": dict(sorted(self.delays.items())),
        }

    @classmethod
    def load(cls, path: str | Path) -> "ChangeState":
        """없으면 빈 상태. 읽을 수 없으면 CorruptState(파일은 건드리지 않는다)."""
        path = Path(path)
        if not path.is_file():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return cls(
                records=[ChangeRecord.from_json(item) for item in data["records"]],
                checked={k: date.fromisoformat(v) for k, v in data.get("checked", {}).items()},
                candidates=[Candidate.from_json(item) for item in data.get("candidates", [])],
                delays={k: int(v) for k, v in data.get("delays", {}).items()},
            )
        except (ValueError, KeyError, TypeError, AttributeError) as e:
            raise CorruptState(f"{path.name}: {type(e).__name__}") from e

    def save(self, path: str | Path) -> None:
        """임시 파일에 다 쓴 뒤 바꾼다. 바꾸기 전 것은 `….prev`로 남긴다."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(json.dumps(self.to_json(), ensure_ascii=False, indent=1), encoding="utf-8")
        if path.is_file():
            shutil.copyfile(path, path.with_name(path.name + ".prev"))
        os.replace(tmp, path)

    def epochs(self) -> dict[Scope, date]:
        """단위별 가장 늦은 확정 변경일."""
        out: dict[Scope, date] = {}
        for record in self.records:
            if record.status == "confirmed" and record.first_date >= out.get(
                record.scope, record.first_date
            ):
                out[record.scope] = record.first_date
        return out

    def open_suspect(self, scope: Scope) -> ChangeRecord | None:
        for record in self.records:
            if record.scope == scope and record.status == "suspect":
                return record
        return None

    def open_candidate(self, scope: Scope) -> Candidate | None:
        for candidate in self.candidates:
            if candidate.scope == scope and candidate.status == "open":
                return candidate
        return None

    def incidents(self) -> dict[Scope, set[date]]:
        """해제된 의심의 날들(하루짜리 사고·지연으로 본 날)."""
        out: dict[Scope, set[date]] = defaultdict(set)
        for record in self.records:
            if record.status == "cleared":
                out[record.scope] |= set(record.days)
        return dict(out)


def _signals(stats: ScopeStats) -> dict[str, float | None]:
    return {
        "shifted": stats.shifted,
        "numbers": stats.numbers,
        "moved": stats.moved,
        "tph": stats.tph_ratio,
        "express": stats.express_delta,
    }


def step(
    state: ChangeState,
    day: date,
    line: str,
    day_type: int,
    stats: dict[Scope, ScopeStats],
    *,
    covered: Set[Scope],
    agrees: Callable[[date], dict[Scope, bool]],
    official_changed: Set[Scope] = frozenset(),
    th: Thresholds = DEFAULT,
) -> list[ChangeRecord]:
    """그 날 그 호선·요일 구분의 수치로 상태를 한 걸음 옮긴다. 새로 생기거나 바뀐 기록."""
    changed: list[ChangeRecord] = []
    open_scopes = {
        r.scope
        for r in state.records
        if r.status == "suspect" and r.scope[0] == day_type and r.scope[1] == line
    }
    for scope in sorted(set(stats) | open_scopes):
        record = state.open_suspect(scope)
        if scope not in covered:
            # 수집이 빈 시간대: 의심도 해제도 하지 않는다. 오래 못 보면 내려놓는다.
            if record is not None and day not in record.days and day not in record.missed:
                record.missed.append(day)
                if len(record.missed) >= th.give_up:
                    record.status, record.decided_date, record.note = "cleared", day, "확인 못 함"
                    changed.append(record)
            continue
        names = triggers(stats[scope], th) if scope in stats else ()
        if record is None:
            if not names:
                continue
            record = ChangeRecord(
                scope=scope, status="suspect", first_date=day, days=[day], missed=[],
                decided_date=None, kind=kind_of(names), triggers=names,
                signals=_signals(stats[scope]), source="measured",
            )  # fmt: skip
            if scope in official_changed:
                record.status, record.decided_date = "confirmed", day
                record.source, record.official = "official", "changed"
            state.records.append(record)
            changed.append(record)
        elif day in record.days:
            continue
        elif not names:
            n = len(record.days)
            record.status, record.decided_date = "cleared", day
            record.note = "하루짜리" if n == 1 else f"{n}일"
            changed.append(record)
        else:
            record.days.append(day)
            if (
                scope in official_changed
                or agrees(record.first_date).get(scope, False)
                or len(record.days) >= th.streak
            ):
                record.status, record.decided_date = "confirmed", day
                if scope in official_changed:
                    record.source, record.official = "official", "changed"
                changed.append(record)
    return changed


def mark(
    state: ChangeState, scopes: Iterable[Scope], change_date: date, *, today: date, note: str
) -> list[ChangeRecord]:
    """사람이 공지·기사를 보고 손으로 확정한다. 열려 있던 의심은 닫는다."""
    made: list[ChangeRecord] = []
    for scope in scopes:
        suspect = state.open_suspect(scope)
        if suspect is not None:
            suspect.status, suspect.decided_date, suspect.note = "cleared", today, "손으로 확정함"
        record = ChangeRecord(
            scope=scope, status="confirmed", first_date=change_date, days=[change_date],
            missed=[], decided_date=today, kind="manual", triggers=(), signals={},
            source="manual", note=note,
        )  # fmt: skip
        state.records.append(record)
        made.append(record)
    return made


def clear(
    state: ChangeState, scopes: Iterable[Scope], *, today: date, note: str
) -> list[ChangeRecord]:
    """확정·의심을 해제로 되돌린다(그 단위는 다시 최근 창 전체를 쓴다)."""
    wanted = set(scopes)
    cleared: list[ChangeRecord] = []
    for record in state.records:
        if record.scope in wanted and record.status in ("suspect", "confirmed"):
            record.status, record.decided_date, record.note = "cleared", today, note
            cleared.append(record)
    return cleared


def detect_day(
    store: PointStore,
    state: ChangeState,
    day: date,
    *,
    th: Thresholds = DEFAULT,
    window: dict[int, int] = WINDOW,
    official_changed: Set[Scope] = frozenset(),
) -> list[ChangeRecord]:
    """그 날 출발이 있는 호선마다, 전날까지의 기준표와 견줘 상태를 옮긴다.

    이미 판단한 날(같거나 앞선 날)은 건너뛴다. 날짜순으로 불러야 한다.
    """
    changed: list[ChangeRecord] = []
    for line, day_type in store.lines(day):
        key = f"{line}|{day_type}"
        if key in state.checked and state.checked[key] >= day:
            continue
        ref_dates = store.dates(line, day_type, upto=day - timedelta(days=1), n=window[day_type])
        reference = build_b1_from_points(
            store.points(line, day_type, ref_dates), epochs=state.epochs(), window=window
        )
        mode = reference["modes"].get(line, "train_no")
        obs = store.points(line, day_type, [day])
        stats = scope_stats(cells_of(reference), obs, mode=mode, th=th)
        counts = store.coverage(line, day)
        open_scopes = {
            r.scope
            for r in state.records
            if r.status == "suspect" and r.scope[0] == day_type and r.scope[1] == line
        }
        covered = {
            scope
            for scope in set(stats) | open_scopes
            if counts is not None and counts.get(scope[3], 0) >= th.min_coverage
        }
        cache: dict[date, dict[Scope, bool]] = {}

        def agrees(
            first: date, _line=line, _day_type=day_type, _obs=obs, _mode=mode, _cache=cache
        ) -> dict[Scope, bool]:
            if first not in _cache:
                first_points = store.points(_line, _day_type, [first])
                _cache[first] = agree(first_points, _obs, mode=_mode, th=th)
            return _cache[first]

        changed += step(
            state, day, line, day_type, stats, covered=covered, agrees=agrees,
            official_changed=official_changed, th=th,
        )  # fmt: skip
        state.checked[key] = day
    return changed


def load_official_changed(path: str | Path, *, today: date, max_age_days: int = 8) -> set[Scope]:
    """`python -m ml.b0 --refresh`가 쓴 차이 목록에서 최근에 바뀐 단위. 없으면 빈 집합."""
    found: set[Scope] = set()
    for entry in load_diff_log(path)["entries"]:
        try:
            if (today - date.fromisoformat(entry["checked_on"])).days > max_age_days:
                continue
            found |= {
                parse_scope_key(key)
                for key, ratio in entry["changed"].items()
                if ratio >= OFFICIAL_CHANGED_MIN
            }
        except (ValueError, KeyError, TypeError, AttributeError):
            continue
    return found


def official_fit(
    b0: B0, store: PointStore, state: ChangeState, day: date, *, th: Thresholds = DEFAULT
) -> list[ChangeRecord]:
    """확정된 변경을 공식 시간표가 따라왔는지 그 날 관측과 직접 견줘 본다(1~9호선).

    공식 시간표를 기준표로(번호 없이 시각만) 놓고 관측과 짝지어, 짝 없는 비율이 기준을 넘으면
    공식 시간표가 아직 옛것(`stale`), 아니면 맞음(`fits`). 판정이 바뀐 기록만 돌려준다.
    """
    changed: list[ChangeRecord] = []
    by_line: dict[tuple[str, int], list[ChangeRecord]] = defaultdict(list)
    for record in state.records:
        day_type, line, _, _ = record.scope
        if (
            record.status == "confirmed"
            and line in TIMETABLE_LINES
            and record.decided_date is not None
            and (day - record.decided_date).days >= OFFICIAL_FIT_AFTER_DAYS
        ):
            by_line[(line, day_type)].append(record)
    for (line, day_type), records in by_line.items():
        obs = store.points(line, day_type, [day])
        if not obs:
            continue
        cells: dict[str, list[Entry]] = {}
        for direction, statn_id in {(p.direction, p.statn_id) for p in obs}:
            table = b0.departures(day_type, line, direction, statn_id)
            if table:
                cells[f"{day_type}|{line}|{direction}|{statn_id}"] = [
                    Entry(secs=secs, train_no=None, n_days=1, dest_name=None, express=0)
                    for _, secs in table
                ]
        stats = scope_stats(cells, obs, mode="slots", th=th)
        for record in records:
            found = stats.get(record.scope)
            if found is None:
                continue
            verdict = "stale" if found.moved >= th.moved else "fits"
            if record.official != verdict:
                record.official = verdict
                changed.append(record)
    return changed


def _bands_text(first: int, last: int) -> str:
    return f"{first:02d}시" if first == last else f"{first:02d}~{last:02d}시"


def _direction_name(line: str, direction: int, names: dict[tuple[str, int], str] | None) -> str:
    if names and (line, direction) in names:
        return names[(line, direction)]
    if line == "2호선":
        return "내선" if direction == 0 else "외선"
    return "상행" if direction == 0 else "하행"


def _signals_text(record: ChangeRecord) -> str:
    parts = [
        label.format(record.signals[name])
        for name, label in _SIGNAL_LABELS
        if name in record.triggers and record.signals.get(name) is not None
    ]
    return ", ".join(parts) or record.note or "-"


def _grouped(records: Iterable[ChangeRecord]) -> list[tuple[ChangeRecord, int, int]]:
    """상태·날짜·종류가 같고 시간대가 이어진 기록을 묶는다: (대표 기록, 첫 시간대, 끝 시간대)."""
    groups: dict[tuple, list[ChangeRecord]] = defaultdict(list)
    for r in records:
        key = (r.scope[0], r.scope[1], r.scope[2], r.status, r.first_date, r.decided_date,
               r.kind, r.source, r.official)  # fmt: skip
        groups[key].append(r)
    out: list[tuple[ChangeRecord, int, int]] = []
    for members in groups.values():
        by_band = {r.scope[3]: r for r in members}
        for first, last in merge_bands(by_band):
            out.append((by_band[first], first, last))
    out.sort(key=lambda item: (item[0].scope[1], item[0].scope[2], item[0].scope[0], item[1]))
    return out


def render(
    records: list[ChangeRecord], *, direction_names: dict[tuple[str, int], str] | None = None
) -> list[str]:
    """기록을 사람이 읽는 줄로. 이어진 시간대는 한 줄로 합친다."""
    lines: list[str] = []
    for record, first, last in _grouped(records):
        day_type, line, direction, _ = record.scope
        when = record.decided_date or record.days[-1]
        text = (
            f"- {when.isoformat()} {STATUS_NAMES[record.status]}"
            f" | {line} {_direction_name(line, direction, direction_names)}"
            f" {DAY_TYPE_NAMES.get(day_type, str(day_type))} {_bands_text(first, last)}"
            f" | {record.kind} | {_signals_text(record)}"
            f" | {record.first_date.isoformat()}부터 | {record.source}"
        )
        if record.official == "stale":
            text += " | 공식 시간표가 아직 옛것"
        lines.append(text)
    return lines


def append_log(path: str | Path, day: date, lines: list[str]) -> None:
    """변경 기록 파일 끝에 `## 날짜` 아래로 덧붙인다. 줄이 없으면 아무것도 쓰지 않는다."""
    if not lines:
        return
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    head = "" if path.is_file() else "# 시간표 변경 기록\n"
    with path.open("a", encoding="utf-8") as f:
        f.write(f"{head}\n## {day.isoformat()}\n\n" + "\n".join(lines) + "\n")


def annotate(artifact: dict, state: ChangeState, *, as_of: date, enforce: bool) -> dict:
    """B1 결과물에 단위 상태(scopes)와 최근 확정 기록(changes)을 적는다."""
    scopes: dict[str, dict] = dict(artifact.get("scopes") or {})
    recent: list[ChangeRecord] = []
    for record in state.records:
        key = scope_key(record.scope)
        if record.status == "suspect":
            scopes.setdefault(
                key,
                {"state": "suspect", "since": record.first_date.isoformat(), "kind": record.kind},
            )
        elif record.status == "confirmed" and record.decided_date is not None:
            age = (as_of - record.decided_date).days
            if age <= CHANGES_KEEP_DAYS:
                recent.append(record)
            if key in scopes and scopes[key].get("state") == "retraining":
                scopes[key].update(kind=record.kind, official=record.official)
            elif age <= CHANGED_SHOW_DAYS:
                scopes[key] = {
                    "state": "changed",
                    "since": record.first_date.isoformat(),
                    "kind": record.kind,
                    "official": record.official,
                }
    artifact["scopes"] = scopes
    artifact["changes"] = [
        {
            "day_type": record.scope[0],
            "line": record.scope[1],
            "direction": record.scope[2],
            "band_from": first,
            "band_to": last,
            "change_date": record.first_date.isoformat(),
            "decided_date": record.decided_date.isoformat(),
            "kind": record.kind,
            "source": record.source,
            "triggers": list(record.triggers),
            "official": record.official,
            "note": record.note,
        }
        for record, first, last in _grouped(recent)
    ]
    artifact["enforce"] = enforce
    return artifact


def _scopes_from_args(args: argparse.Namespace) -> list[Scope]:
    return [
        (args.day_type, args.line, args.direction, band)
        for band in range(args.band_from, args.band_to + 1)
    ]


def main(argv: list[str] | None = None, *, now: datetime | None = None) -> int:
    parser = argparse.ArgumentParser(description="시간표 변경 기록 보기·손으로 확정·되돌리기")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("show", "mark", "clear"):
        p = sub.add_parser(name)
        p.add_argument("--state", default="ml_data/change_state.json")
        if name == "show":
            continue
        p.add_argument("--log", default="ml_data/changes.md")
        p.add_argument("--line", required=True)
        p.add_argument("--direction", type=int, required=True, choices=(0, 1))
        p.add_argument("--day-type", type=int, required=True, choices=(1, 2, 3))
        p.add_argument("--from", dest="band_from", type=int, required=True, help="첫 시간대(시)")
        p.add_argument("--to", dest="band_to", type=int, required=True, help="끝 시간대(시)")
        p.add_argument("--note", default="")
        if name == "mark":
            p.add_argument("--date", required=True, help="변경일 YYYY-MM-DD")
    args = parser.parse_args(argv)
    try:
        state = ChangeState.load(args.state)
    except CorruptState as e:
        print(f"상태 파일을 읽을 수 없어 멈춥니다(덮어쓰지 않음): {e}", file=sys.stderr)
        return 2
    today = (now or datetime.now(KST)).astimezone(KST).date()
    if args.command == "show":
        open_or_confirmed = [r for r in state.records if r.status != "cleared"]
        lines = render(open_or_confirmed)
        print("\n".join(lines) if lines else "의심·확정된 변경이 없습니다.")
        return 0
    if args.command == "mark":
        records = mark(
            state, _scopes_from_args(args), date.fromisoformat(args.date), today=today,
            note=args.note,
        )  # fmt: skip
    else:
        records = clear(state, _scopes_from_args(args), today=today, note=args.note)
    state.save(args.state)
    lines = render(records)
    append_log(args.log, today, lines)
    print("\n".join(lines) if lines else "바뀐 기록이 없습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
