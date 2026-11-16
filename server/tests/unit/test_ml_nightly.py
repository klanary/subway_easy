import json
from datetime import datetime, timedelta

import pytest

from app.core.clock import KST
from ml import nightly
from ml.changes import ChangeState
from ml.nightly import main, run_nightly
from ml.store import Store
from tests.ml_helpers import weekdays, write_db
from tests.unit.test_ml_changes_signals import day_events

DAYS = weekdays(8)
CELL_A = "1|9호선|0|A"
H6 = 6 * 3600


def add_raw(db, days, line="9호선", bands=(6, 7), n=120):
    """그 날들의 시간대마다 정상 응답 n건(30초 간격)."""
    store = Store(db)
    for day in days:
        for band in bands:
            at = datetime(day.year, day.month, day.day, tzinfo=KST) + timedelta(hours=band)
            for i in range(n):
                store.add_snapshot(line, at + timedelta(seconds=30 * i), "INFO-000", "{}")
    store.close()


def make_db(tmp_path, shifts, name="c.sqlite"):
    """DAYS[i]를 shifts[i]초만큼 밀어 만든 수집 DB."""
    events, days = [], []
    for day, shift in zip(DAYS, shifts, strict=False):
        events += day_events(day, shift=shift)
        days.append(day)
    db = write_db(tmp_path / name, events)
    add_raw(db, days)
    return db


@pytest.fixture
def paths(tmp_path):
    return {
        "points_path": tmp_path / "ml_data" / "points.sqlite",
        "state_path": tmp_path / "ml_data" / "change_state.json",
        "out_path": tmp_path / "ml_data" / "artifacts" / "measured_timetable.json",
        "log_path": tmp_path / "ml_data" / "changes.md",
    }


def night(db, paths, n_days, **kw):
    """DAYS[n_days - 1]까지 수집된 다음 날 새벽에 돌린다."""
    return run_nightly(db=db, today=DAYS[n_days - 1] + timedelta(days=1), **paths, **kw)


def artifact(paths):
    return json.loads(paths["out_path"].read_text(encoding="utf-8"))


def a_entry(paths, train_no="9001"):
    return {e["train_no"]: e for e in artifact(paths)["cells"][CELL_A]}[train_no]


def statuses(paths):
    return [r.status for r in ChangeState.load(paths["state_path"]).records]


def test_first_run_extracts_all_days_and_writes_artifact(tmp_path, paths, capsys):
    db = make_db(tmp_path, [0] * 5)
    assert night(db, paths, 5) == 0
    art = artifact(paths)
    assert art["version"] == 2 and art["as_of"] == DAYS[4].isoformat()
    assert art["enforce"] is False and art["changes"] == []
    assert a_entry(paths)["secs"] == H6 + 30 and a_entry(paths)["n_days"] == 5
    assert art["stations"]["9호선|A"]["name"] == "A역"
    assert art["window"] == {"1": 20, "2": 8, "3": 8}
    state = ChangeState.load(paths["state_path"])
    assert state.checked == {"9호선|1": DAYS[4]} and state.records == []
    assert not paths["log_path"].exists()
    assert "5일" in capsys.readouterr().out


def test_observe_mode_records_but_does_not_apply_epochs(tmp_path, paths):
    db = make_db(tmp_path, [0] * 5 + [180, 180])
    assert night(db, paths, 7) == 0
    state = ChangeState.load(paths["state_path"])
    assert [(r.status, r.first_date) for r in state.records] == [("confirmed", DAYS[5])]
    art = artifact(paths)
    assert art["enforce"] is False
    assert a_entry(paths)["secs"] == H6 + 30  # 7일의 중앙값은 아직 옛 시각
    assert art["scopes"]["1|9호선|0|6"]["state"] == "changed"
    assert art["changes"][0]["change_date"] == DAYS[5].isoformat()
    log = paths["log_path"].read_text(encoding="utf-8")
    assert "확정" in log and "06시" in log


def test_enforce_applies_epochs(tmp_path, paths):
    db = make_db(tmp_path, [0] * 5 + [180, 180])
    assert night(db, paths, 7, enforce=True) == 0
    entry = a_entry(paths)
    assert (entry["secs"], entry["n_days"]) == (H6 + 180 + 30, 2)
    assert artifact(paths)["enforce"] is True


def test_night_by_night_gives_suspect_then_confirmed(tmp_path, paths):
    six = make_db(tmp_path, [0] * 5 + [180], "six.sqlite")
    assert night(six, paths, 6) == 0
    assert statuses(paths) == ["suspect"]
    assert artifact(paths)["scopes"]["1|9호선|0|6"]["state"] == "suspect"
    seven = make_db(tmp_path, [0] * 5 + [180, 180], "seven.sqlite")
    assert night(seven, paths, 7) == 0
    assert statuses(paths) == ["confirmed"]


def test_catches_up_missed_days_in_order(tmp_path, paths):
    db = make_db(tmp_path, [0] * 5 + [180, 180, 180])
    assert night(db, paths, 8) == 0
    (record,) = ChangeState.load(paths["state_path"]).records
    assert (record.status, record.first_date, record.decided_date) == (
        "confirmed", DAYS[5], DAYS[6],
    )  # fmt: skip


def test_rerun_same_night_changes_nothing(tmp_path, paths):
    db = make_db(tmp_path, [0] * 5 + [180, 180])
    night(db, paths, 7)
    state_before = paths["state_path"].read_text(encoding="utf-8")
    art_before = artifact(paths)
    log_before = paths["log_path"].read_text(encoding="utf-8")
    assert night(db, paths, 7) == 0
    art_after = artifact(paths)
    art_before.pop("built_at")
    art_after.pop("built_at")
    assert art_after == art_before
    assert paths["state_path"].read_text(encoding="utf-8") == state_before
    assert paths["log_path"].read_text(encoding="utf-8") == log_before


def test_notify_gets_only_confirmed_lines(tmp_path, paths):
    sent: list[str] = []
    six = make_db(tmp_path, [0] * 5 + [180], "six.sqlite")
    night(six, paths, 6, notify=sent.append)
    assert sent == []  # 의심은 기록만
    seven = make_db(tmp_path, [0] * 5 + [180, 180], "seven.sqlite")
    night(seven, paths, 7, notify=sent.append)
    assert len(sent) == 1 and "확정" in sent[0] and "9호선" in sent[0]


def test_notify_failure_does_not_break_the_run(tmp_path, paths):
    def boom(text: str) -> None:
        raise RuntimeError("웹훅 실패")

    db = make_db(tmp_path, [0] * 5 + [180, 180])
    assert night(db, paths, 7, notify=boom) == 0


def test_corrupt_state_exits_2_and_keeps_old_artifact(tmp_path, paths, capsys):
    db = make_db(tmp_path, [0] * 5)
    night(db, paths, 5)
    before = paths["out_path"].read_bytes()
    paths["state_path"].write_text("{", encoding="utf-8")
    assert night(db, paths, 5) == 2
    assert paths["out_path"].read_bytes() == before
    assert paths["state_path"].read_text(encoding="utf-8") == "{"
    assert "상태 파일" in capsys.readouterr().err


def test_one_line_failing_keeps_its_previous_cells_and_exits_1(tmp_path, paths, monkeypatch):
    events = []
    for day in DAYS[:5]:
        events += day_events(day)
        events += [o.__class__(**{**vars(o), "line": "5호선"}) for o in day_events(day)]
    db = write_db(tmp_path / "two.sqlite", events)
    assert night(db, paths, 5) == 0
    before = artifact(paths)
    real = nightly.build_b1_from_points

    def flaky(points, **kw):
        if points and points[0].line == "5호선":
            raise RuntimeError("고장")
        return real(points, **kw)

    monkeypatch.setattr(nightly, "build_b1_from_points", flaky)
    assert night(db, paths, 5) == 1
    after = artifact(paths)
    assert after["cells"]["1|5호선|0|A"] == before["cells"]["1|5호선|0|A"]
    assert after["cells"][CELL_A] == before["cells"][CELL_A]
    assert after["modes"]["5호선"] == before["modes"]["5호선"]
    assert (paths["out_path"].parent / "measured_timetable.json.prev").is_file()


def test_no_events_db_exits_2(tmp_path, paths):
    assert run_nightly(db=tmp_path / "없음.sqlite", today=DAYS[0], **paths) == 2
    assert not paths["out_path"].exists()


def test_stale_trains_drop_out_without_detection(tmp_path, paths):
    # 번호 9010이 마지막 사흘 동안 안 다닌다. 감지와 무관하게 시간표에서 빠진다.
    events = []
    for i, day in enumerate(DAYS[:8]):
        events += day_events(day, n=10 if i < 5 else 9)
    db = write_db(tmp_path / "c.sqlite", events)
    add_raw(db, DAYS[:8])
    assert night(db, paths, 8) == 0
    numbers = {e["train_no"] for e in artifact(paths)["cells"][CELL_A]}
    assert "9010" not in numbers and "9009" in numbers


def test_days_without_coverage_keep_trains(tmp_path, paths):
    # 같은 데이터인데 마지막 사흘의 수집 횟수가 모자라면(수집기가 멈춤) 9010을 빼지 않는다.
    events = []
    for i, day in enumerate(DAYS[:8]):
        events += day_events(day, n=10 if i < 5 else 9)
    db = write_db(tmp_path / "c.sqlite", events)
    add_raw(db, DAYS[:5])
    add_raw(db, DAYS[5:8], n=10)
    assert night(db, paths, 8) == 0
    assert "9010" in {e["train_no"] for e in artifact(paths)["cells"][CELL_A]}


def test_main_uses_day_option_and_enforce_flag(tmp_path, paths):
    db = make_db(tmp_path, [0] * 5)
    args = [
        "--db", str(db), "--points", str(paths["points_path"]), "--state", str(paths["state_path"]),
        "--out", str(paths["out_path"]), "--log", str(paths["log_path"]),
        "--day", (DAYS[4] + timedelta(days=1)).isoformat(),
    ]  # fmt: skip
    assert main(args) == 0
    assert artifact(paths)["as_of"] == DAYS[4].isoformat()
    assert main([*args, "--enforce"]) == 0 and artifact(paths)["enforce"] is True
