"""공식 시간표로 변경을 확정한다(1~9호선).

`ml.b0 --refresh`가 날마다 적는 차이 목록에서 달라진 단위를 "후보"로 들이고, 그 요일 구분의
관측이 있는 날 밤에 옛·새 공식 시간표 가운데 관측이 어느 쪽에 맞는지 견줘 확정한다.
설계는 `docs/superpowers/specs/2026-11-17-official-timetable-confirmation-design.md`.
"""

import logging
import statistics
from bisect import bisect_left
from dataclasses import dataclass
from datetime import date

from ml.changes import CHANGES_KEEP_DAYS, DEFAULT, Candidate, ChangeState, Thresholds
from ml.scopes import band_of, parse_scope_key

logger = logging.getLogger("ml.official")
_MIN_DELAY_SAMPLES = 20  # 평소 지연을 구하는 데 필요한 표본 수


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
    same: dict[str, list[int]], obs: dict[str, list[int]], reach_sec: int
) -> int | None:
    """공식 시각 대비 평소 지연(초): 관측 − 가장 가까운 "그대로" 시각의 중앙값.

    `reach_sec` 안에서 이어지는 것만 쓴다. 표본이 `_MIN_DELAY_SAMPLES`개 미만이면 None.
    """
    offsets: list[int] = []
    for station, times in obs.items():
        slots = sorted(same.get(station, ()))
        if not slots:
            continue
        for t in times:
            i = bisect_left(slots, t)
            near = [slots[j] for j in (i - 1, i) if 0 <= j < len(slots)]
            best = min(near, key=lambda slot: abs(slot - t))
            if abs(best - t) <= reach_sec:
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
