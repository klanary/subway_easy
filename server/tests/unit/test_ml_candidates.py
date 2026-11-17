"""공식 시간표 후보: 들이기, 판정 계산, 밤 작업에서의 판정, 채우기."""

import json
from datetime import date

from ml.changes import ChangeState
from ml.official import ingest_candidates, prune_candidates
from ml.scopes import scope_key

SCOPE = (1, "9호선", 0, 6)
ENTRY = {
    "checked_on": "2026-11-18",
    "changed": {scope_key(SCOPE): 0.12},
    "old_cache": "tt-20261118",
    "skipped": [],
}


def test_state_round_trips_candidates_and_delays(tmp_path):
    state = ChangeState(delays={"9호선|0": 40})
    ingest_candidates(state, [ENTRY])
    state.candidates[0].tries.append((date(2026, 11, 18), "hold:few_votes"))
    state.save(tmp_path / "s.json")
    loaded = ChangeState.load(tmp_path / "s.json")
    assert loaded.candidates == state.candidates and loaded.delays == {"9호선|0": 40}
    assert json.loads((tmp_path / "s.json").read_text(encoding="utf-8"))["version"] == 2


def test_version_1_state_file_loads_with_no_candidates(tmp_path):
    old = '{"version": 1, "checked": {}, "records": []}'
    (tmp_path / "s.json").write_text(old, encoding="utf-8")
    state = ChangeState.load(tmp_path / "s.json")
    assert state.candidates == [] and state.delays == {}


def test_ingest_is_idempotent_and_skips_entries_without_old_cache():
    state = ChangeState()
    no_cache = {**ENTRY, "checked_on": "2026-11-19", "old_cache": None}
    assert len(ingest_candidates(state, [ENTRY, no_cache])) == 1
    assert ingest_candidates(state, [ENTRY]) == []
    found = state.open_candidate(SCOPE)
    assert (found.ratio, found.found_on, found.old_cache) == (
        0.12,
        date(2026, 11, 18),
        "tt-20261118",
    )


def test_newer_candidate_supersedes_the_open_one():
    state = ChangeState()
    later = {**ENTRY, "checked_on": "2026-11-20", "old_cache": "tt-20261120"}
    ingest_candidates(state, [later, ENTRY])  # 순서가 뒤섞여 와도 날짜순으로 본다
    assert [c.status for c in state.candidates] == ["superseded", "open"]
    assert state.candidates[0].closed_on == date(2026, 11, 20)


def test_malformed_entry_is_ignored():
    state = ChangeState()
    assert ingest_candidates(state, [{"checked_on": "어제"}, {"changed": {}}, ENTRY]) != []
    assert len(state.candidates) == 1


def test_closed_candidates_are_pruned_after_60_days():
    state = ChangeState()
    ingest_candidates(state, [ENTRY])
    state.candidates[0].status, state.candidates[0].closed_on = "expired", date(2026, 11, 20)
    prune_candidates(state, date(2027, 1, 18))
    assert len(state.candidates) == 1
    prune_candidates(state, date(2027, 1, 20))
    assert state.candidates == []
