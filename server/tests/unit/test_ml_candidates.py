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


# --- 판정 계산 ---

H6 = 6 * 3600


def table(shift=0, n=14, gap=240, stations=("A", "B", "C"), start=H6):
    """역마다 start부터 gap초 간격 n대(역마다 +120초)."""
    return {
        station: [start + gap * i + 120 * index + shift for i in range(n)]
        for index, station in enumerate(stations)
    }


def shifted(times, by):
    return {station: [t + by for t in secs] for station, secs in times.items()}


def retimed(times, every, by):
    """every번째 열차마다 by초 늦춘 표."""
    return {
        station: sorted(t + by if i % every == 0 else t for i, t in enumerate(secs))
        for station, secs in times.items()
    }


def test_split_changed_pairs_within_tolerance():
    from ml.official import split_changed

    assert split_changed([100, 400, 700], [110, 460, 1000], 20) == ([100], [400, 700], [460, 1000])


def test_usual_delay_is_the_median_offset_or_none():
    from ml.official import usual_delay

    same = table()
    assert usual_delay(same, shifted(same, 40), 180) == 40
    assert usual_delay({"A": [100]}, {"A": [140]}, 180) is None  # 표본 부족


def test_observations_matching_new_times_confirm():
    from ml.official import judge_scope

    old, new = table(), retimed(table(), every=2, by=60)  # 1분짜리 변경
    v = judge_scope(old, new, shifted(new, 40), band=6, delay=40)
    assert (v.result, v.old_votes) == ("confirm", 0) and v.new_votes == 21  # 역 3곳 × 7대
    assert v.base_fit == 1.0


def test_observations_matching_old_times_mean_the_api_changed_early():
    from ml.official import judge_scope

    old, new = table(), retimed(table(), every=2, by=60)
    v = judge_scope(old, new, shifted(old, 40), band=6, delay=40)
    assert (v.result, v.new_votes, v.old_votes) == ("early", 0, 21)


def test_a_delayed_day_is_held_not_confirmed():
    from ml.official import judge_scope

    old, new = table(), retimed(table(), every=2, by=60)
    late = shifted(old, 40 + 30)  # 옛 시간표대로인데 30초 더 늦은 날: 옛·새 한가운데
    assert judge_scope(old, new, late, band=6, delay=40).result == "hold"


def test_few_changed_trains_are_held_for_lack_of_votes():
    from ml.official import judge_scope

    old = table()
    new = retimed(old, every=12, by=60)  # 역마다 두 대만 바뀜(6표)
    v = judge_scope(old, new, new, band=6, delay=0)
    assert (v.result, v.reason, v.new_votes) == ("hold", "few_votes", 6)


def test_official_table_that_does_not_describe_the_line_is_held():
    from ml.official import judge_scope

    old, new = table(), retimed(table(), every=2, by=60)
    unrelated = table(shift=150, gap=420)  # 공식과 무관하게 다니는 노선
    v = judge_scope(old, new, unrelated, band=6, delay=0)
    assert (v.result, v.reason) == ("hold", "base_fit") and v.base_fit < 0.6


def test_whole_table_changed_still_judges_without_same_slots():
    from ml.official import judge_scope

    old, new = table(), table(shift=90)
    v = judge_scope(old, new, new, band=6, delay=0)
    assert v.result == "confirm" and v.base_fit is None


def test_no_observation_in_band_is_held():
    from ml.official import judge_scope

    old, new = table(), retimed(table(), every=2, by=60)
    assert judge_scope(old, new, {}, band=6, delay=0).reason == "no_obs"
    other_band = shifted(new, 3600)
    assert judge_scope(old, new, other_band, band=6, delay=0).reason == "no_obs"


def test_observation_far_from_both_tables_is_not_a_vote():
    from ml.official import judge_scope

    old, new = {"A": [H6 + 100]}, {"A": [H6 + 200]}
    v = judge_scope(old, new, {"A": [H6 + 900]}, band=6, delay=0)
    assert (v.new_votes, v.old_votes) == (0, 0)


def test_times_after_midnight_are_counted_the_same_way():
    from ml.official import judge_scope

    old = table(start=24 * 3600)
    new = retimed(old, every=2, by=60)
    assert judge_scope(old, new, new, band=24, delay=0).result == "confirm"
