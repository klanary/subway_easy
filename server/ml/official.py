"""공식 시간표로 변경을 확정한다(1~9호선).

`ml.b0 --refresh`가 날마다 적는 차이 목록에서 달라진 단위를 "후보"로 들이고, 그 요일 구분의
관측이 있는 날 밤에 옛·새 공식 시간표 가운데 관측이 어느 쪽에 맞는지 견줘 확정한다.
설계는 `docs/superpowers/specs/2026-11-17-official-timetable-confirmation-design.md`.
"""

import logging
import statistics
from bisect import bisect_left
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

from ml.b0 import B0, TimetableCache
from ml.b1 import Entry
from ml.changes import (
    CHANGES_KEEP_DAYS,
    DAY_TYPE_NAMES,
    DEFAULT,
    Candidate,
    ChangeRecord,
    ChangeState,
    Thresholds,
    _bands_text,
    _direction_name,
)
from ml.points import PointStore
from ml.scopes import Scope, band_of, merge_bands, parse_scope_key

logger = logging.getLogger("ml.official")
_MIN_DELAY_SAMPLES = 20  # 평소 지연을 구하는 데 필요한 표본 수
_HOLD_TEXT = {
    "few_votes": "표 모자람",
    "mixed": "엇갈림",
    "base_fit": "공식 시간표가 평소 운행과 안 맞음",
    "no_obs": "관측 없음",
}


def ingest_candidates(state: ChangeState, entries: list[dict]) -> list[Candidate]:
    """차이 목록의 항목을 후보로 들인다. 새로 들인 후보.

    날짜순으로 본다. 옛 캐시가 없는 항목(견줄 것이 없다)과 이미 들인 것은 건너뛴다.
    같은 단위에 열린 후보가 있으면 그것을 닫고 새것으로 바꾼다.
    """
    made: list[Candidate] = []
    have = {(c.scope, c.found_on) for c in state.candidates}
    usable = []
    for entry in entries:
        try:
            found_on = date.fromisoformat(entry["checked_on"])
            changed = {parse_scope_key(k): float(v) for k, v in entry["changed"].items()}
            old_cache = entry.get("old_cache")
        except (ValueError, KeyError, TypeError, AttributeError):
            logger.warning("차이 목록의 항목을 읽을 수 없어 건너뜀")
            continue
        if old_cache:
            usable.append((found_on, str(old_cache), changed))
    for found_on, old_cache, changed in sorted(usable, key=lambda item: item[0]):
        for scope, ratio in sorted(changed.items()):
            if (scope, found_on) in have:
                continue
            previous = state.open_candidate(scope)
            if previous is not None:
                previous.status, previous.closed_on = "superseded", found_on
            candidate = Candidate(scope=scope, found_on=found_on, old_cache=old_cache, ratio=ratio)
            state.candidates.append(candidate)
            have.add((scope, found_on))
            made.append(candidate)
    return made


def prune_candidates(state: ChangeState, today: date) -> None:
    """닫힌 지 `CHANGES_KEEP_DAYS`일이 넘은 후보를 버린다."""
    state.candidates = [
        c
        for c in state.candidates
        if c.closed_on is None or (today - c.closed_on).days <= CHANGES_KEEP_DAYS
    ]


def _nearest_gap(sorted_times: list[int], t: int) -> int | None:
    """정렬된 시각 목록에서 t와 가장 가까운 시각까지의 거리. 목록이 비면 None."""
    if not sorted_times:
        return None
    i = bisect_left(sorted_times, t)
    gaps = [abs(sorted_times[j] - t) for j in (i - 1, i) if 0 <= j < len(sorted_times)]
    return min(gaps)


def split_changed(
    old: list[int], new: list[int], same_sec: int
) -> tuple[list[int], list[int], list[int]]:
    """옛·새 시각을 `same_sec` 안에서 일대일로 짝지어 (그대로, 옛것만, 새것만)으로 가른다.

    "그대로"는 옛 시각으로 적는다. 둘 다 정렬해 앞에서부터 짝짓는다.
    """
    old, new = sorted(old), sorted(new)
    same: list[int] = []
    old_only: list[int] = []
    new_only: list[int] = []
    i = j = 0
    while i < len(old) and j < len(new):
        if abs(old[i] - new[j]) <= same_sec:
            same.append(old[i])
            i += 1
            j += 1
        elif old[i] < new[j]:
            old_only.append(old[i])
            i += 1
        else:
            new_only.append(new[j])
            j += 1
    return same, old_only + old[i:], new_only + new[j:]


def usual_delay(
    same: dict[str, list[int]],
    obs: dict[str, list[int]],
    reach_sec: int,
    *,
    others: dict[str, list[int]] | None = None,
) -> int | None:
    """공식 시각 대비 평소 지연(초): 관측 − 가장 가까운 "그대로" 시각의 중앙값.

    `reach_sec` 안에서 이어지는 것만 쓴다. `others`(바뀐 자리의 옛·새 시각)를 주면, 그쪽에
    더 가까운 관측은 바뀐 열차의 것이므로 쓰지 않는다. 표본이 `_MIN_DELAY_SAMPLES`개
    미만이면 None.
    """
    offsets: list[int] = []
    for station, times in obs.items():
        slots = sorted(same.get(station, ()))
        if not slots:
            continue
        changed = sorted((others or {}).get(station, ()))
        for t in times:
            i = bisect_left(slots, t)
            near = [slots[j] for j in (i - 1, i) if 0 <= j < len(slots)]
            best = min(near, key=lambda slot: abs(slot - t))
            gap = abs(best - t)
            other_gap = _nearest_gap(changed, t)
            if gap <= reach_sec and (other_gap is None or gap < other_gap):
                offsets.append(t - best)
    if len(offsets) < _MIN_DELAY_SAMPLES:
        return None
    return round(statistics.median(offsets))


@dataclass(frozen=True)
class Verdict:
    result: str  # confirm | early | hold
    reason: str  # "" | few_votes | mixed | base_fit | no_obs
    new_votes: int
    old_votes: int
    base_fit: float | None


def judge_scope(
    old: dict[str, list[int]],
    new: dict[str, list[int]],
    obs: dict[str, list[int]],
    *,
    band: int,
    delay: int,
    th: Thresholds = DEFAULT,
) -> Verdict:
    """그 시간대의 관측이 옛·새 공식 시간표 가운데 어느 쪽에 맞는가.

    표는 역 id → 그 호선·방향·요일 구분의 하루 출발 시각(초)이다. 번호는 쓰지 않는다.
    관측에서 평소 지연(`delay`)을 뺀 뒤, 관측마다 가장 가까운 옛 시각과 새 시각까지의 거리를
    견줘 가까운 쪽에 한 표를 준다. 두 거리가 비슷하면(안 바뀐 열차) 표가 아니다.
    """
    seen = {
        station: sorted(t - delay for t in times if band_of(t - delay) == band)
        for station, times in obs.items()
    }
    seen = {station: times for station, times in seen.items() if times}
    if not seen:
        return Verdict("hold", "no_obs", 0, 0, None)

    slots = hits = new_votes = old_votes = 0
    for station in sorted(set(old) | set(new)):
        old_times, new_times = sorted(old.get(station, ())), sorted(new.get(station, ()))
        times = seen.get(station, [])
        same, _, _ = split_changed(old_times, new_times, th.same_sec)
        for slot in same:
            if band_of(slot) != band:
                continue
            slots += 1
            gap = _nearest_gap(times, slot)
            hits += gap is not None and gap <= th.base_reach_sec
        for t in times:
            d_old, d_new = _nearest_gap(old_times, t), _nearest_gap(new_times, t)
            d_old = th.vote_reach_sec + 1 if d_old is None else d_old
            d_new = th.vote_reach_sec + 1 if d_new is None else d_new
            if min(d_old, d_new) > th.vote_reach_sec or abs(d_old - d_new) < th.tie_sec:
                continue
            if d_new < d_old:
                new_votes += 1
            else:
                old_votes += 1

    base_fit = hits / slots if slots >= th.base_min_slots else None
    if base_fit is not None and base_fit < th.base_fit:
        return Verdict("hold", "base_fit", new_votes, old_votes, base_fit)
    votes = new_votes + old_votes
    if votes < th.min_votes:
        return Verdict("hold", "few_votes", new_votes, old_votes, base_fit)
    share = new_votes / votes
    if share >= th.new_share:
        return Verdict("confirm", "", new_votes, old_votes, base_fit)
    if share <= th.old_share:
        return Verdict("early", "", new_votes, old_votes, base_fit)
    return Verdict("hold", "mixed", new_votes, old_votes, base_fit)


def _scope_lines(day: date, word: str, items: list[tuple[Scope, str]]) -> list[str]:
    """(단위, 문구)들을 기록 줄로. 같은 호선·방향·요일 구분·문구의 이어진 시간대는 합친다."""
    groups: dict[tuple[int, str, int, str], list[int]] = defaultdict(list)
    for (day_type, line, direction, band), text in items:
        groups[(day_type, line, direction, text)].append(band)
    lines: list[str] = []
    for (day_type, line, direction, text), bands in sorted(groups.items()):
        for first, last in merge_bands(bands):
            lines.append(
                f"- {day.isoformat()} {word} | {line} {_direction_name(line, direction, None)}"
                f" {DAY_TYPE_NAMES.get(day_type, str(day_type))} {_bands_text(first, last)}"
                f" | {text}"
            )
    return lines


def candidate_lines(found: list[Candidate]) -> list[str]:
    """새로 들인 후보를 알리는 기록 줄."""
    by_day: dict[date, list[tuple[Scope, str]]] = defaultdict(list)
    for candidate in found:
        by_day[candidate.found_on].append((candidate.scope, "공식 시간표가 달라짐"))
    return [line for day in sorted(by_day) for line in _scope_lines(day, "후보", by_day[day])]


class _Tables:
    """옛·새 공식 시간표와 그 날 관측을 (호선, 방향)별 `역 id → 시각 목록`으로 내준다."""

    def __init__(
        self, store: PointStore, cache_dir: Path, codes: dict[tuple[str, str], str], day: date
    ):
        self._store, self._cache_dir, self._codes, self._day = store, cache_dir, codes, day
        self._b0: dict[str, B0 | None] = {}
        self._official: dict[tuple[str, int, str, int], dict[str, list[int]]] = {}
        self._obs: dict[tuple[str, int], dict[int, dict[str, list[int]]]] = {}
        self.delays: dict[tuple[str, int, str, int], int | None] = {}  # 한 밤에 한 번만 구한다

    def has(self, cache_name: str) -> bool:
        return (self._cache_dir.parent / cache_name).is_dir()

    def official(
        self, cache_name: str, day_type: int, line: str, direction: int
    ) -> dict[str, list[int]]:
        key = (cache_name, day_type, line, direction)
        if key not in self._official:
            if cache_name not in self._b0:
                directory = self._cache_dir.parent / cache_name
                self._b0[cache_name] = B0(TimetableCache(directory), self._codes)
            b0 = self._b0[cache_name]
            table: dict[str, list[int]] = {}
            for known_line, statn_id in self._codes:
                if known_line != line:
                    continue
                times = [secs for _, secs in b0.departures(day_type, line, direction, statn_id)]
                if times:
                    table[statn_id] = times
            self._official[key] = table
        return self._official[key]

    def observed(self, day_type: int, line: str, direction: int) -> dict[str, list[int]]:
        key = (line, day_type)
        if key not in self._obs:
            by_direction: dict[int, dict[str, list[int]]] = defaultdict(lambda: defaultdict(list))
            for point in self._store.points(line, day_type, [self._day]):
                by_direction[point.direction][point.statn_id].append(point.secs)
            self._obs[key] = by_direction
        return {station: sorted(times) for station, times in self._obs[key][direction].items()}


def _delay(tables: _Tables, state: ChangeState, candidate: Candidate, th: Thresholds) -> int:
    """그 호선·방향의 평소 지연. 구하지 못하면 반대 방향, 저장해 둔 값, 0 순서로 쓴다."""
    day_type, line, direction, _ = candidate.scope
    new_name = tables._cache_dir.name
    for which in (direction, 1 - direction):
        memo = (candidate.old_cache, day_type, line, which)
        if memo in tables.delays:
            if tables.delays[memo] is not None:
                return tables.delays[memo]
            continue
        old = tables.official(candidate.old_cache, day_type, line, which)
        new = tables.official(new_name, day_type, line, which)
        parts = {
            station: split_changed(old.get(station, []), new.get(station, []), th.same_sec)
            for station in set(old) | set(new)
        }
        found = usual_delay(
            {station: part[0] for station, part in parts.items()},
            tables.observed(day_type, line, which),
            th.delay_reach_sec,
            others={station: sorted(part[1] + part[2]) for station, part in parts.items()},
        )
        tables.delays[memo] = found
        if found is not None:
            state.delays[f"{line}|{which}"] = found
            return found
    return state.delays.get(f"{line}|{direction}", 0)


def judge_candidates(
    state: ChangeState,
    store: PointStore,
    cache_dir: str | Path,
    codes: dict[tuple[str, str], str],
    day: date,
    *,
    th: Thresholds = DEFAULT,
) -> tuple[list[ChangeRecord], list[str]]:
    """열린 후보를 그 날 관측으로 판정한다. (새로 확정된 기록, 기록 파일에 쓸 줄).

    그 날의 요일 구분에 맞는 후보만, 공식 시간표를 받은 날의 전날 운행분부터, 하루에 한 번 본다.
    그 시간대의 수집이 비었으면 건너뛴다(판정 횟수에 넣지 않는다).
    """
    cache_dir = Path(cache_dir)
    day_types = dict(store.lines(day))
    todo = [
        c
        for c in state.candidates
        if c.status == "open"
        and day_types.get(c.scope[1]) == c.scope[0]
        and day >= c.found_on - timedelta(days=1)
        and all(tried != day for tried, _ in c.tries)
    ]
    records: list[ChangeRecord] = []
    held: list[tuple[Scope, str]] = []
    expired: list[tuple[Scope, str]] = []
    tables = _Tables(store, cache_dir, codes, day)
    coverage: dict[str, dict[int, int] | None] = {}
    for candidate in sorted(todo, key=lambda c: c.scope):
        day_type, line, direction, band = candidate.scope
        if line not in coverage:
            coverage[line] = store.coverage(line, day)
        if (coverage[line] or {}).get(band, 0) < th.min_coverage:
            continue
        if not tables.has(candidate.old_cache):
            candidate.status, candidate.closed_on = "expired", day
            candidate.note = "옛 시간표 없음"
            expired.append((candidate.scope, candidate.note))
            continue
        verdict = judge_scope(
            tables.official(candidate.old_cache, day_type, line, direction),
            tables.official(cache_dir.name, day_type, line, direction),
            tables.observed(day_type, line, direction),
            band=band,
            delay=_delay(tables, state, candidate, th),
            th=th,
        )
        if verdict.result == "confirm":
            candidate.tries.append((day, "confirm"))
            candidate.status, candidate.closed_on = "confirmed", day
            suspect = state.open_suspect(candidate.scope)
            if suspect is not None:
                suspect.status, suspect.decided_date = "cleared", day
                suspect.note = "공식 시간표로 확정함"
            record = ChangeRecord(
                scope=candidate.scope, status="confirmed", first_date=day, days=[day], missed=[],
                decided_date=day, kind="official", triggers=(),
                signals={"new_votes": verdict.new_votes, "old_votes": verdict.old_votes},
                source="official", official="changed",
                note=f"새 {verdict.new_votes}표·옛 {verdict.old_votes}표",
            )  # fmt: skip
            state.records.append(record)
            records.append(record)
            continue
        if verdict.result == "early":
            candidate.tries.append((day, "early"))
            held.append((candidate.scope, "공식 시간표가 먼저 바뀜"))
        else:
            candidate.tries.append((day, f"hold:{verdict.reason}"))
            held.append((candidate.scope, _HOLD_TEXT[verdict.reason]))
        limit = th.tries_weekday if day_type == 1 else th.tries_other
        if len(candidate.tries) >= limit:
            candidate.status, candidate.closed_on = "expired", day
            candidate.note = f"{len(candidate.tries)}번 보고도 정하지 못함"
            expired.append((candidate.scope, candidate.note))
    lines = _scope_lines(day, "후보 보류", held) + _scope_lines(day, "후보 만료", expired)
    return records, lines


def official_fill(
    part: dict, state: ChangeState, b0: B0, statn_ids: Iterable[str], *, line: str
) -> int:
    """공식 근거로 확정돼 "다시 배우는 중"인 단위를 새 공식 시각 + 평소 지연으로 채운다.

    `part`는 한 호선의 B1 결과물이다. 채운 항목 수를 돌려준다. 단위 상태는 그대로 둔다.
    변경일 뒤 관측이 차면 B1이 그 단위를 다시 내므로, 그때는 채울 것이 없다.
    """
    latest: dict[Scope, ChangeRecord] = {}
    for record in state.records:
        if record.status == "confirmed" and (
            record.scope not in latest or record.first_date >= latest[record.scope].first_date
        ):
            latest[record.scope] = record
    statn_ids = list(statn_ids)
    filled = 0
    for key, info in sorted((part.get("scopes") or {}).items()):
        scope = parse_scope_key(key)
        day_type, scope_line, direction, band = scope
        record = latest.get(scope)
        if (
            scope_line != line
            or info.get("state") != "retraining"
            or record is None
            or record.source != "official"
        ):
            continue
        delay = state.delays.get(f"{line}|{direction}", 0)
        for statn_id in statn_ids:
            entries = [
                vars(
                    Entry(
                        secs=secs + delay,
                        train_no=None,
                        n_days=0,
                        dest_name=None,
                        express=0,
                        official=True,
                    )  # fmt: skip
                ).copy()
                for _, secs in b0.departures(day_type, line, direction, statn_id)
                if band_of(secs) == band
            ]
            if not entries:
                continue
            cell = part["cells"].setdefault(f"{day_type}|{line}|{direction}|{statn_id}", [])
            cell.extend(entries)
            cell.sort(key=lambda e: (e["secs"], e.get("train_no") or ""))
            filled += len(entries)
    return filled
