"""공식 시간표로 변경을 확정한다(1~9호선).

`ml.b0 --refresh`가 날마다 적는 차이 목록에서 달라진 단위를 "후보"로 들이고, 그 요일 구분의
관측이 있는 날 밤에 옛·새 공식 시간표 가운데 관측이 어느 쪽에 맞는지 견줘 확정한다.
설계는 `docs/superpowers/specs/2026-11-17-official-timetable-confirmation-design.md`.
"""

import logging
from datetime import date

from ml.changes import CHANGES_KEEP_DAYS, Candidate, ChangeState
from ml.scopes import parse_scope_key

logger = logging.getLogger("ml.official")


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
