import json
from datetime import date, timedelta

import pytest

from ml.changes import (
    ChangeState,
    CorruptState,
    annotate,
    append_log,
    clear,
    detect_day,
    main,
    mark,
    render,
)
from ml.points import PointStore
from tests.ml_helpers import cover, weekdays
from tests.unit.test_ml_changes_signals import day_points

DAYS = weekdays(12)  # 11-02(월)부터 평일 12일. DAYS[9]는 금요일(11-13), DAYS[10]은 월요일(11-16)
SCOPE = (1, "9호선", 0, 6)


@pytest.fixture
def store(tmp_path):
    s = PointStore(tmp_path / "points.sqlite")
    yield s
    s.close()


def put(store, day, **kw):
    store.put(day_points(day, **kw))
    cover(store, [day])


def base(store, n=5):
    for day in DAYS[:n]:
        put(store, day)


def run(store, state, upto, **kw):
    """DAYS[0..upto]를 날짜순으로 판단하고, 마지막 날에 바뀐 기록을 돌려준다."""
    changed = []
    for day in DAYS[: upto + 1]:
        changed = detect_day(store, state, day, **kw)
    return changed


def only(state, status=None):
    records = [r for r in state.records if status is None or r.status == status]
    assert len(records) == 1, [(r.scope, r.status) for r in state.records]
    return records[0]


def test_normal_days_leave_no_records(store):
    base(store, 7)
    state = ChangeState()
    assert run(store, state, 6) == []
    assert state.records == [] and state.epochs() == {}
    assert state.checked == {"9호선|1": DAYS[6]}


def test_two_matching_days_confirm_with_first_day_as_change_date(store):
    base(store)
    put(store, DAYS[5], shift=180)
    put(store, DAYS[6], shift=180)
    state = ChangeState()
    first = run(store, state, 5)
    assert [(r.scope, r.status) for r in first] == [(SCOPE, "suspect")]
    assert first[0].triggers == ("shifted", "moved") and first[0].kind == "retime"
    second = detect_day(store, state, DAYS[6])
    record = only(state)
    assert second == [record]
    assert (record.status, record.first_date, record.decided_date) == (
        "confirmed", DAYS[5], DAYS[6],
    )  # fmt: skip
    assert record.days == [DAYS[5], DAYS[6]] and record.source == "measured"
    assert state.epochs() == {SCOPE: DAYS[5]}


def test_one_bad_day_then_normal_is_cleared(store):
    base(store)
    put(store, DAYS[5], shift=180)
    put(store, DAYS[6])
    state = ChangeState()
    run(store, state, 6)
    record = only(state)
    assert (record.status, record.note) == ("cleared", "하루짜리")
    assert state.epochs() == {}
    assert state.incidents() == {SCOPE: {DAYS[5]}}


def test_two_different_delay_days_do_not_confirm(store):
    base(store)
    put(store, DAYS[5], shift=180)
    put(store, DAYS[6], shift=400)
    put(store, DAYS[7], shift=650)
    state = ChangeState()
    run(store, state, 6)
    record = only(state)
    assert record.status == "suspect" and record.days == [DAYS[5], DAYS[6]]
    detect_day(store, state, DAYS[7])
    assert only(state).status == "confirmed"  # 서로 안 맞아도 3일 연속이면 확정
    assert only(state).first_date == DAYS[5]


def test_renumber_confirms_as_renumber(store):
    base(store)
    put(store, DAYS[5], first_no=8001)
    put(store, DAYS[6], first_no=8001)
    state = ChangeState()
    run(store, state, 6)
    record = only(state)
    assert (record.status, record.kind, record.triggers) == ("confirmed", "renumber", ("numbers",))


def test_uncovered_band_is_neither_suspected_nor_cleared(store):
    base(store)
    put(store, DAYS[5], shift=180)
    state = ChangeState()
    run(store, state, 5)
    # 수집이 빈 날: 열차가 절반만 보이지만 판단하지 않는다.
    store.put(day_points(DAYS[6], n=5))
    store.set_coverage("9호선", DAYS[6], {6: 10})
    assert detect_day(store, state, DAYS[6]) == []
    record = only(state)
    assert record.status == "suspect" and record.missed == [DAYS[6]]
    # 수집 횟수를 모르는 날도 같다.
    for day in DAYS[7:9]:
        store.put(day_points(day, n=5))
        assert detect_day(store, state, day) == []
    assert only(state).status == "suspect"
    store.put(day_points(DAYS[9], n=5))
    gave_up = detect_day(store, state, DAYS[9])
    assert [(r.status, r.note) for r in gave_up] == [("cleared", "확인 못 함")]


def test_uncovered_day_creates_no_new_suspect(store):
    base(store)
    store.put(day_points(DAYS[5], n=5))  # coverage 없음
    state = ChangeState()
    assert run(store, state, 5) == [] and state.records == []


def test_weekend_days_between_do_not_interrupt(store):
    base(store, 9)
    put(store, DAYS[9], shift=180)  # 금요일
    saturday, sunday = DAYS[9] + timedelta(days=1), DAYS[9] + timedelta(days=2)
    put(store, saturday)
    put(store, sunday)
    put(store, DAYS[10], shift=180)  # 월요일
    state = ChangeState()
    run(store, state, 9)
    assert detect_day(store, state, saturday) == []
    assert detect_day(store, state, sunday) == []
    assert only(state).status == "suspect"
    detect_day(store, state, DAYS[10])
    assert only(state).status == "confirmed"


def test_official_change_confirms_on_first_suspect_day(store):
    base(store)
    put(store, DAYS[5], shift=180)
    state = ChangeState()
    run(store, state, 5, official_changed={SCOPE})
    record = only(state)
    assert (record.status, record.source, record.first_date) == ("confirmed", "official", DAYS[5])


def test_after_confirm_next_day_is_quiet(store):
    base(store)
    for day in DAYS[5:8]:
        put(store, day, shift=180)
    state = ChangeState()
    run(store, state, 6)
    assert detect_day(store, state, DAYS[7]) == []
    assert len(state.records) == 1


def test_detect_day_twice_is_idempotent(store):
    base(store)
    put(store, DAYS[5], shift=180)
    state = ChangeState()
    run(store, state, 5)
    before = state.to_json()
    assert detect_day(store, state, DAYS[5]) == []
    assert detect_day(store, state, DAYS[3]) == []
    assert state.to_json() == before


def test_state_roundtrip_and_atomic_save(store, tmp_path):
    base(store)
    put(store, DAYS[5], shift=180)
    put(store, DAYS[6], shift=180)
    state = ChangeState()
    run(store, state, 6)
    path = tmp_path / "state" / "change_state.json"
    state.save(path)
    loaded = ChangeState.load(path)
    assert loaded.to_json() == state.to_json()
    assert loaded.epochs() == {SCOPE: DAYS[5]} and loaded.checked == state.checked
    state.save(path)
    assert sorted(p.name for p in path.parent.iterdir()) == [
        "change_state.json", "change_state.json.prev",
    ]  # fmt: skip


def test_corrupt_state_raises_and_file_is_untouched(tmp_path):
    path = tmp_path / "change_state.json"
    path.write_text('{"records": [{"scope": ', encoding="utf-8")
    with pytest.raises(CorruptState):
        ChangeState.load(path)
    path.write_text('{"records": "아님"}', encoding="utf-8")
    with pytest.raises(CorruptState):
        ChangeState.load(path)
    assert path.read_text(encoding="utf-8") == '{"records": "아님"}'


def test_missing_state_is_empty(tmp_path):
    state = ChangeState.load(tmp_path / "없음.json")
    assert state.records == [] and state.checked == {}


def test_mark_then_clear_restores_window():
    state = ChangeState()
    scopes = [(1, "9호선", 1, band) for band in (5, 6, 7)]
    made = mark(state, scopes, DAYS[5], today=DAYS[6], note="기사: 급행 증편")
    assert [(r.status, r.source, r.first_date) for r in made] == [
        ("confirmed", "manual", DAYS[5])
    ] * 3
    assert state.epochs() == dict.fromkeys(scopes, DAYS[5])
    cleared = clear(state, scopes[:2], today=DAYS[7], note="잘못 봄")
    assert [r.status for r in cleared] == ["cleared", "cleared"]
    assert state.epochs() == {scopes[2]: DAYS[5]}


def test_mark_replaces_an_open_suspect(store):
    base(store)
    put(store, DAYS[5], shift=180)
    state = ChangeState()
    run(store, state, 5)
    mark(state, [SCOPE], DAYS[5], today=DAYS[5], note="공지")
    assert state.open_suspect(SCOPE) is None
    assert state.epochs() == {SCOPE: DAYS[5]}


def test_render_merges_adjacent_bands():
    state = ChangeState()
    made = mark(
        state,
        [(1, "9호선", 1, band) for band in (5, 6, 7)] + [(1, "9호선", 1, 10)],
        date(2026, 11, 20),
        today=date(2026, 11, 23),
        note="",
    )
    lines = render(made)
    assert len(lines) == 2
    assert lines[0].startswith("- 2026-11-23 확정 | 9호선 하행 평일 05~07시 |")
    assert "2026-11-20부터" in lines[0] and lines[0].endswith("manual")
    assert "10시" in lines[1]
    assert render([]) == []


def test_render_shows_signals_and_direction_names(store):
    base(store)
    put(store, DAYS[5], shift=180)
    state = ChangeState()
    (record,) = run(store, state, 5)
    (line,) = render([record], direction_names={("9호선", 0): "개화 방면"})
    assert "의심" in line and "9호선 개화 방면 평일 06시" in line
    assert "어긋남 100%" in line and "retime" in line


def test_append_log_adds_dated_sections(tmp_path):
    path = tmp_path / "ml_data" / "changes.md"
    append_log(path, DAYS[5], [])
    assert not path.exists()
    append_log(path, DAYS[5], ["- 줄 하나"])
    append_log(path, DAYS[6], ["- 줄 둘", "- 줄 셋"])
    text = path.read_text(encoding="utf-8")
    assert text.count("## ") == 2 and f"## {DAYS[6].isoformat()}" in text
    assert text.index("줄 하나") < text.index("줄 둘")


def test_annotate_adds_scopes_and_changes(store):
    base(store)
    put(store, DAYS[5], shift=180)
    put(store, DAYS[6], shift=180)
    put(store, DAYS[6], shift=180, start=10 * 3600, first_no=7001)
    state = ChangeState()
    run(store, state, 6)
    mark(state, [(1, "9호선", 1, 8)], DAYS[6], today=DAYS[6], note="공지")
    artifact = {"scopes": {"1|9호선|1|8": {"state": "retraining", "since": DAYS[6].isoformat()}}}
    out = annotate(artifact, state, as_of=DAYS[6], enforce=False)
    assert out["enforce"] is False
    assert out["scopes"]["1|9호선|0|6"]["state"] == "changed"
    assert out["scopes"]["1|9호선|1|8"]["state"] == "retraining"  # 이미 적힌 것은 그대로
    assert out["scopes"]["1|9호선|1|8"]["kind"] == "manual"
    kinds = {(c["direction"], c["band_from"], c["band_to"], c["source"]) for c in out["changes"]}
    assert kinds == {(0, 6, 6, "measured"), (1, 8, 8, "manual")}
    later = annotate({"scopes": {}}, state, as_of=DAYS[6] + timedelta(days=15), enforce=True)
    assert later["scopes"] == {} and len(later["changes"]) == 2  # 14일이 지나면 표시는 빠진다


def test_annotate_marks_open_suspects(store):
    base(store)
    put(store, DAYS[5], shift=180)
    state = ChangeState()
    run(store, state, 5)
    out = annotate({"scopes": {}}, state, as_of=DAYS[5], enforce=False)
    assert out["scopes"]["1|9호선|0|6"]["state"] == "suspect" and out["changes"] == []


def test_cli_mark_show_clear(tmp_path, capsys):
    state_path = tmp_path / "change_state.json"
    log_path = tmp_path / "changes.md"
    common = ["--state", str(state_path), "--log", str(log_path)]
    code = main(
        ["mark", *common, "--line", "9호선", "--direction", "1", "--day-type", "1",
         "--from", "5", "--to", "7", "--date", "2026-11-20", "--note", "기사"]
    )  # fmt: skip
    assert code == 0
    state = ChangeState.load(state_path)
    assert len(state.epochs()) == 3
    assert "05~07시" in log_path.read_text(encoding="utf-8")
    assert main(["show", "--state", str(state_path)]) == 0
    assert "9호선" in capsys.readouterr().out
    code = main(
        ["clear", *common, "--line", "9호선", "--direction", "1", "--day-type", "1",
         "--from", "5", "--to", "7", "--note", "되돌림"]
    )  # fmt: skip
    assert code == 0 and ChangeState.load(state_path).epochs() == {}


def test_cli_with_corrupt_state_exits_2(tmp_path):
    state_path = tmp_path / "change_state.json"
    state_path.write_text("{", encoding="utf-8")
    assert main(["show", "--state", str(state_path)]) == 2
    assert state_path.read_text(encoding="utf-8") == "{"
    assert json.loads("{}") == {}
