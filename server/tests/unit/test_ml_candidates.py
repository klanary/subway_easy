"""공식 시간표 후보: 들이기, 판정 계산, 밤 작업에서의 판정, 채우기."""

import json
import shutil
from datetime import date

import pytest

from ml.b0 import TimetableCache
from ml.b1 import departure_points
from ml.changes import ChangeRecord, ChangeState
from ml.dataset import build_runs
from ml.official import candidate_lines, ingest_candidates, judge_candidates, prune_candidates
from ml.points import PointStore
from ml.scopes import scope_key
from tests.ml_helpers import cover, trip, weekdays
from tests.unit.test_ml_changes_signals import day_points
from tests.unit.test_ml_official import CODES, _hms, cache_with, rows

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


def test_usual_delay_ignores_observations_of_changed_trains():
    from ml.official import split_changed, usual_delay

    old, new = table(), retimed(table(), every=2, by=60)
    parts = {s: split_changed(old[s], new[s], 20) for s in old}
    same = {s: part[0] for s, part in parts.items()}
    others = {s: sorted(part[1] + part[2]) for s, part in parts.items()}
    obs = shifted(new, 40)
    # 바뀐 열차의 관측을 옆의 안 바뀐 자리에 이으면 지연이 틀어진다.
    assert usual_delay(same, obs, 180) != 40
    assert usual_delay(same, obs, 180, others=others) == 40


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


# --- 밤 작업에서의 판정 ---

DAYS = weekdays(8)
DAY = DAYS[0]


@pytest.fixture
def store(tmp_path):
    s = PointStore(tmp_path / "points.sqlite")
    yield s
    s.close()


def scene(tmp_path, *, old_shift=0, new_shift=180, scope=SCOPE) -> ChangeState:
    """옛 표(`tt-old`)와 새 표(`tt`)를 두고, 그 단위의 열린 후보 하나가 든 상태."""
    cache_with(tmp_path / "tt-old", shift=old_shift)
    cache_with(tmp_path / "tt", shift=new_shift)
    state = ChangeState()
    entry = {"checked_on": DAY.isoformat(), "changed": {scope_key(scope): 1.0}}
    ingest_candidates(state, [{**entry, "old_cache": "tt-old"}])
    return state


def judge(state, store, tmp_path, day=DAY):
    return judge_candidates(state, store, tmp_path / "tt", CODES, day)


def test_candidate_is_confirmed_the_night_observations_match_the_new_table(tmp_path, store):
    state = scene(tmp_path)
    store.put(day_points(DAY, shift=180))
    cover(store, [DAY])
    records, lines = judge(state, store, tmp_path)
    (record,) = records
    assert (record.status, record.source, record.first_date) == ("confirmed", "official", DAY)
    assert (record.kind, record.official, record.note) == ("official", "changed", "새 29표·옛 0표")
    candidate = state.candidates[0]
    assert (candidate.status, candidate.closed_on, candidate.tries) == (
        "confirmed",
        DAY,
        [(DAY, "confirm")],
    )
    assert state.epochs()[SCOPE] == DAY and lines == []


def test_open_measured_suspect_is_closed_by_the_official_confirmation(tmp_path, store):
    state = scene(tmp_path)
    suspect = ChangeRecord(
        scope=SCOPE, status="suspect", first_date=DAY, days=[DAY], missed=[], decided_date=None,
        kind="retime", triggers=("moved",), signals={}, source="measured",
    )  # fmt: skip
    state.records.append(suspect)
    store.put(day_points(DAY, shift=180))
    cover(store, [DAY])
    judge(state, store, tmp_path)
    assert (suspect.status, suspect.note) == ("cleared", "공식 시간표로 확정함")
    assert state.open_suspect(SCOPE) is None


def test_hold_is_retried_and_expires_after_five_weekdays(tmp_path, store):
    state = scene(tmp_path)
    lines = []
    for day in DAYS[:5]:
        store.put(day_points(day, shift=0))  # 관측은 계속 옛 시각
        cover(store, [day])
        records, lines = judge(state, store, tmp_path, day)
        assert records == []
    candidate = state.candidates[0]
    assert candidate.status == "expired" and candidate.closed_on == DAYS[4]
    assert [result for _, result in candidate.tries] == ["early"] * 5
    assert "후보 보류" in lines[0] and "공식 시간표가 먼저 바뀜" in lines[0]
    assert "후보 만료" in lines[-1] and "5번 보고도 정하지 못함" in lines[-1]
    assert "9호선 상행 평일 06시" in lines[-1]


def test_day_without_coverage_is_not_counted_as_a_try(tmp_path, store):
    state = scene(tmp_path)
    store.put(day_points(DAY, shift=180))  # cover() 없음
    assert judge(state, store, tmp_path) == ([], [])
    assert state.candidates[0].tries == [] and state.candidates[0].status == "open"


def test_same_day_is_not_judged_twice(tmp_path, store):
    state = scene(tmp_path)
    store.put(day_points(DAY, shift=0))
    cover(store, [DAY])
    judge(state, store, tmp_path)
    assert judge(state, store, tmp_path) == ([], [])
    assert len(state.candidates[0].tries) == 1


def test_other_day_type_is_left_alone(tmp_path, store):
    state = scene(tmp_path, scope=(2, "9호선", 0, 6))  # 토요일 후보
    store.put(day_points(DAY, shift=180))  # 평일 관측
    cover(store, [DAY])
    assert judge(state, store, tmp_path) == ([], [])
    assert state.candidates[0].tries == []


def test_day_long_before_the_timetable_was_fetched_is_not_judged(tmp_path, store):
    state = scene(tmp_path)
    state.candidates[0].found_on = DAYS[3]
    for day in (DAYS[0], DAYS[2]):
        store.put(day_points(day, shift=0))
        cover(store, [day])
    judge(state, store, tmp_path, DAYS[0])
    assert state.candidates[0].tries == []
    judge(state, store, tmp_path, DAYS[2])  # 받은 날의 전날 운행분부터 본다
    assert [day for day, _ in state.candidates[0].tries] == [DAYS[2]]


def test_missing_old_cache_folder_expires_the_candidate_without_raising(tmp_path, store):
    state = scene(tmp_path)
    shutil.rmtree(tmp_path / "tt-old")
    store.put(day_points(DAY, shift=180))
    cover(store, [DAY])
    records, lines = judge(state, store, tmp_path)
    assert records == [] and state.candidates[0].status == "expired"
    assert state.candidates[0].note == "옛 시간표 없음" and "옛 시간표 없음" in lines[0]


def test_delay_falls_back_to_the_stored_value_when_nothing_is_unchanged(tmp_path, store):
    # 전체가 60초 앞당겨진 표. 관측은 새 표보다 50초 늦다(= 옛 표보다 10초 이르다).
    # 지연을 빼지 않으면 옛 표에 더 가깝게 보인다.
    state = scene(tmp_path, new_shift=-60)
    store.put(day_points(DAY, shift=-10))
    cover(store, [DAY])
    assert judge(state, store, tmp_path)[0] == []
    assert state.candidates[0].tries == [(DAY, "early")]

    state = scene(tmp_path, new_shift=-60)
    state.delays = {"9호선|0": 50}
    (record,) = judge(state, store, tmp_path)[0]
    assert record.status == "confirmed"


def _half_retimed_cache(directory, by):
    """14대 가운데 짝수 번째만 by초 늦춘 시간표."""
    cache = TimetableCache(directory)
    for index, station in enumerate("ABC"):
        table = rows(f"cd-{station}", n=14, gap=240, station_index=index)
        for i, row in enumerate(table):
            if i % 2 == 0:
                secs = H6 + 240 * i + 120 * index + 30 + by
                row["LEFTTIME"] = _hms(secs)
        cache.save(f"cd-{station}", 1, 1, table)
    return cache


def test_delay_is_measured_from_unchanged_trains_and_stored(tmp_path, store):
    cache = TimetableCache(tmp_path / "tt-old")
    for index, station in enumerate("ABC"):
        cache.save(f"cd-{station}", 1, 1, rows(f"cd-{station}", n=14, gap=240, station_index=index))
    _half_retimed_cache(tmp_path / "tt", 60)
    state = ChangeState()
    entry = {"checked_on": DAY.isoformat(), "changed": {scope_key(SCOPE): 0.5}}
    ingest_candidates(state, [{**entry, "old_cache": "tt-old"}])
    events = []
    for i in range(14):  # 새 표대로 다니되 40초씩 늦다
        start = H6 + 240 * i + (60 if i % 2 == 0 else 0) + 40
        events += trip(DAY, start, train_no=str(9001 + i))
    store.put(departure_points(build_runs(events)))
    cover(store, [DAY])
    (record,) = judge(state, store, tmp_path)[0]
    assert record.note == "새 21표·옛 0표"
    assert state.delays == {"9호선|0": 40}


def test_candidate_lines_merge_neighbouring_bands():
    state = ChangeState()
    changed = {scope_key((1, "9호선", 0, band)): 0.2 for band in (6, 7, 9)}
    found = ingest_candidates(
        state, [{"checked_on": "2026-11-18", "changed": changed, "old_cache": "tt-old"}]
    )
    assert candidate_lines(found) == [
        "- 2026-11-18 후보 | 9호선 상행 평일 06~07시 | 공식 시간표가 달라짐",
        "- 2026-11-18 후보 | 9호선 상행 평일 09시 | 공식 시간표가 달라짐",
    ]


# --- 확정 단위 채우기 ---


def retraining_part(tmp_path, *, source, delay):
    """그 단위가 "다시 배우는 중"인 호선 결과물, 확정 기록이 든 상태, 새 공식 시간표."""
    from ml.b0 import B0

    part = {
        "cells": {},
        "scopes": {scope_key(SCOPE): {"state": "retraining", "since": DAY.isoformat()}},
    }
    record = ChangeRecord(
        scope=SCOPE, status="confirmed", first_date=DAY, days=[DAY], missed=[], decided_date=DAY,
        kind="official", triggers=(), signals={}, source=source,
    )  # fmt: skip
    state = ChangeState(records=[record], delays={"9호선|0": delay})
    return part, state, B0(cache_with(tmp_path / "tt", shift=180), CODES)


def test_retraining_scope_confirmed_by_official_is_filled_with_official_times_plus_delay(tmp_path):
    from ml.official import official_fill

    part, state, b0 = retraining_part(tmp_path, source="official", delay=40)
    n = official_fill(part, state, b0, ["A", "B", "C"], line="9호선")
    cell = part["cells"]["1|9호선|0|A"]
    assert n == sum(len(c) for c in part["cells"].values()) == 29  # 06시대에 드는 출발
    assert all(e["official"] and e["train_no"] is None and e["n_days"] == 0 for e in cell)
    assert cell[0]["secs"] == H6 + 30 + 180 + 40
    assert [e["secs"] for e in cell] == sorted(e["secs"] for e in cell)
    assert part["scopes"][scope_key(SCOPE)]["state"] == "retraining"


def test_fill_keeps_measured_entries_of_other_bands_in_the_same_cell(tmp_path):
    from ml.official import official_fill

    part, state, b0 = retraining_part(tmp_path, source="official", delay=0)
    measured = {"secs": 8 * 3600, "train_no": "9099", "n_days": 5, "official": False}
    part["cells"]["1|9호선|0|A"] = [measured]
    official_fill(part, state, b0, ["A"], line="9호선")
    cell = part["cells"]["1|9호선|0|A"]
    assert cell[-1] == measured and len(cell) == 11


def test_scope_confirmed_by_measurement_only_is_not_filled(tmp_path):
    from ml.official import official_fill

    part, state, b0 = retraining_part(tmp_path, source="measured", delay=40)
    assert official_fill(part, state, b0, ["A"], line="9호선") == 0 and part["cells"] == {}


def test_entry_default_is_not_official():
    from ml.b1 import Entry

    assert Entry(secs=1, train_no=None, n_days=1, dest_name=None, express=0).official is False
