from datetime import date

import pytest

from ml.b0 import B0, TimetableCache
from ml.dataset import build_runs
from ml.evaluate import (
    NotEnoughDates,
    evaluate_b1,
    evaluate_b2,
    main,
    metrics,
    render,
    split_by_date,
    split_same_day,
)
from tests.ml_helpers import STATIONS, trip, weekdays, write_db

H8 = 8 * 3600


def _hms(secs: int) -> str:
    return f"{secs // 3600:02d}:{secs % 3600 // 60:02d}:{secs % 60:02d}"


def _runs(n_days: int, *, last_day_shift: int = 0, hop: int = 120):
    days = weekdays(n_days)
    events = []
    for day in days:
        shift = last_day_shift if day == days[-1] else 0
        events += trip(day, H8 + shift, hop=hop, train_no="100")
        events += trip(day, H8 + 600 + shift, hop=hop, train_no="101")
    return build_runs(events)


def _b0(tmp_path, *, early: int = 20) -> B0:
    """합성 노선의 공식 시간표: 실제(지연 없는 날)보다 early초 이르다."""
    cache = TimetableCache(tmp_path / "tt")
    for i, station in enumerate(STATIONS):
        rows = [
            {
                "TRAIN_NO": train_no,
                "ARRIVETIME": _hms(start + 120 * i - early),
                "LEFTTIME": _hms(start + 120 * i + 30 - early),
                "DESTSTATION": "cd-D",
            }
            for train_no, start in (("100", H8), ("101", H8 + 600))
        ]
        cache.save(f"cd-{station}", 1, 1, rows)
    return B0(cache, {("9호선", s): f"cd-{s}" for s in STATIONS})


def test_metrics_use_absolute_errors():
    m = metrics([10, -70, 130])
    assert (m["n"], m["mae"], m["p90"]) == (3, 70.0, 118.0)
    assert (m["within60"], m["within120"]) == (pytest.approx(1 / 3), pytest.approx(2 / 3))
    assert metrics([]) is None


def test_split_by_date_never_mixes_dates():
    runs = _runs(5)
    train, test = split_by_date(runs)
    assert {r.service_date for r in test} == {weekdays(5)[-1]}
    assert {r.service_date for r in train} == set(weekdays(4))
    train, test = split_by_date(runs, test_days=2)
    assert len({r.service_date for r in test}) == 2
    assert not {r.service_date for r in train} & {r.service_date for r in test}
    ten = _runs(10)
    assert len({r.service_date for r in split_by_date(ten)[1]}) == 2  # 뒤 20%


def test_split_needs_two_dates():
    with pytest.raises(NotEnoughDates):
        split_by_date(_runs(1))
    with pytest.raises(NotEnoughDates):
        split_by_date([])
    with pytest.raises(NotEnoughDates):
        split_by_date(_runs(3), test_days=3)


def test_same_day_split_goes_by_run_start():
    train, test = split_same_day(_runs(1), H8 + 300)
    assert [r.train_no for r in train] == ["100"]
    assert [r.train_no for r in test] == ["101"]


def test_perfect_data_scores_zero_error():
    train, test = split_by_date(_runs(6))
    b1 = evaluate_b1(train, test).summary()
    assert b1["total"] == 6 and b1["coverage"] == 1.0  # 열차 2대 × 출발역 3곳
    assert b1["model"] == {"n": 6, "mae": 0.0, "p90": 0.0, "within60": 1.0, "within120": 1.0}
    b2 = evaluate_b2(train, test).summary()
    assert b2["total"] == 12 and b2["coverage"] == 1.0  # 열차 2대 × 역 쌍 6개
    assert b2["model"]["mae"] == 0.0


def test_delay_on_the_test_day_shows_up_in_b1_not_b2():
    train, test = split_by_date(_runs(6, last_day_shift=40))
    assert evaluate_b1(train, test).summary()["model"]["mae"] == 40.0
    assert evaluate_b2(train, test).summary()["model"]["mae"] == 0.0


def test_too_few_training_days_means_no_prediction():
    train, test = split_by_date(_runs(3))  # 학습 2일 < 하한 3일
    summary = evaluate_b1(train, test).summary()
    assert (summary["coverage"], summary["model"]) == (0.0, None)
    assert evaluate_b1(train, test, min_days=2).summary()["coverage"] == 1.0


def test_b0_is_scored_on_the_same_observations(tmp_path):
    train, test = split_by_date(_runs(6))
    b0 = _b0(tmp_path, early=20)
    b1 = evaluate_b1(train, test, b0=b0).summary()
    assert b1["b0"]["mae"] == 20.0 and b1["model_paired"]["mae"] == 0.0
    assert b1["b0"]["n"] == b1["model_paired"]["n"] == 6
    b2 = evaluate_b2(train, test, b0=b0).summary()
    assert b2["b0"]["mae"] == 0.0  # 구간 시간은 시간표와 같다
    assert b2["b0"]["n"] == 12


def test_reports_break_down_by_line_and_day_type():
    events = []
    for day in weekdays(6):
        events += trip(day, H8, train_no="100")
        events += trip(day, H8, train_no="500", line="5호선")
    for day in (date(2026, 11, 7), date(2026, 11, 14), date(2026, 11, 21), date(2026, 11, 28)):
        events += trip(day, H8 + 900, train_no="100")
        events += trip(day, H8 + 900, train_no="500", line="5호선")
    train, test = split_by_date(build_runs(events), test_days=2)
    report = evaluate_b1(train, test)
    assert set(report.by_line) == {"5호선", "9호선"}
    assert set(report.by_day_type) == {2}  # 뒤 이틀은 토요일
    text = render([report], title="평가", note="테스트")
    assert "## B1" in text and "9호선" in text and "토요일" in text and "MAE" in text


def test_cli_reports_not_enough_dates_and_offers_same_day_check(tmp_path, capsys):
    one_day = [o for o in trip(weekdays(1)[0], H8)] + trip(weekdays(1)[0], H8 + 900, train_no="1")
    db = write_db(tmp_path / "one.sqlite", one_day)
    out = tmp_path / "report.md"
    assert main(["--db", str(db), "--out", str(out)]) == 1
    assert "날짜" in capsys.readouterr().err
    assert main(["--db", str(db), "--out", str(out), "--same-day-cutoff", "08:10"]) == 0
    assert "날짜 분할이 아니다" in out.read_text(encoding="utf-8")
    assert main(["--db", str(tmp_path / "없음.sqlite"), "--out", str(out)]) == 2


def test_cli_writes_date_split_report(tmp_path):
    events = [o for d in weekdays(6) for o in trip(d, H8)]
    db = write_db(tmp_path / "c.sqlite", events)
    out = tmp_path / "report.md"
    assert main(["--db", str(db), "--out", str(out), "--test-days", "1"]) == 0
    text = out.read_text(encoding="utf-8")
    assert "## B1" in text and "## B2" in text
    assert weekdays(6)[-1].isoformat() in text


def test_lines_that_never_report_departures_are_scored_on_arrival_plus_dwell():
    # 경의중앙선처럼 출발(2)이 안 찍히는 노선: 도착 + 학습 날짜의 정차 시간을 실측으로 쓴다.
    days = weekdays(6)
    events = []
    for day in days:
        shift = 40 if day == days[-1] else 0
        events += trip(day, H8 + shift, statuses=(1,))
    train, test = split_by_date(build_runs(events))
    summary = evaluate_b1(train, test).summary()
    assert (summary["total"], summary["coverage"]) == (3, 1.0)
    assert summary["model"]["mae"] == 40.0


# --- 변경 전후·하루짜리 이상을 나눠 보기(시간표 변경 계획 Task 8) ---

SCOPE_8 = (1, "9호선", 0, 8)


def _changed_runs(n_old: int, n_new: int, shift: int = 300):
    from tests.ml_helpers import timetable

    days = weekdays(n_old + n_new)
    events = timetable(days[:n_old], {"100": H8}) + timetable(days[n_old:], {"100": H8 + shift})
    return days, build_runs(events)


def test_observations_right_after_a_change_are_reported_apart():
    from ml.changes import ChangeState, mark

    days, runs = _changed_runs(5, 2)
    train, test = split_by_date(runs, test_days=2)
    state = ChangeState()
    mark(state, [SCOPE_8], days[5], today=days[6], note="")
    plain = evaluate_b1(train, test)
    report = evaluate_b1(train, test, state=state)
    assert report.by_phase["post_change"].total == 6  # 이틀 × 출발역 3곳
    assert "stable" not in report.by_phase or report.by_phase["stable"].total == 0
    assert report.overall.total == plain.overall.total == 6  # 숨기지 않는다
    assert report.by_phase["post_change"].summary()["model"]["mae"] == 300.0
    assert plain.by_phase == {}


def test_change_inside_training_period_is_learned_and_counts_as_stable():
    from ml.changes import ChangeState, mark

    days, runs = _changed_runs(5, 3)
    train, test = split_by_date(runs, test_days=1)
    state = ChangeState()
    mark(state, [SCOPE_8], days[5], today=days[6], note="")
    report = evaluate_b1(train, test, state=state)
    assert report.summary()["model"]["mae"] == 0.0  # 변경 뒤 이틀로 다시 배운 시각
    assert report.by_phase["stable"].total == 3 and "post_change" not in report.by_phase
    # 상태를 주지 않으면 옛 시각 쪽 중앙값으로 맞히므로 5분 틀린다.
    assert evaluate_b1(train, test).summary()["model"]["mae"] == 300.0


def test_change_known_only_after_training_does_not_leak():
    from ml.changes import ChangeState, mark

    # 변경일이 시험 기간 안이면 학습 쪽 B1에는 기준일을 적용하지 않는다.
    days, runs = _changed_runs(6, 1)
    train, test = split_by_date(runs, test_days=1)
    state = ChangeState()
    mark(state, [SCOPE_8], days[6], today=days[6], note="")
    report = evaluate_b1(train, test, state=state)
    assert report.summary()["coverage"] == 1.0  # 옛 시각으로라도 답한다
    assert report.by_phase["post_change"].total == 3


def test_cleared_suspect_days_are_incidents():
    from ml.changes import ChangeState, clear, mark

    days, runs = _changed_runs(5, 1, shift=240)
    train, test = split_by_date(runs, test_days=1)
    state = ChangeState()
    mark(state, [SCOPE_8], days[5], today=days[5], note="")
    clear(state, [SCOPE_8], today=days[5], note="하루짜리")
    report = evaluate_b1(train, test, state=state)
    assert report.by_phase["incident"].total == 3
    text = render([report], title="평가", note="")
    assert "구간별" in text and "하루짜리 이상" in text


def test_render_without_state_has_no_phase_table():
    train, test = split_by_date(_runs(6))
    assert "구간별" not in render([evaluate_b1(train, test)], title="평가", note="")


def test_cli_accepts_a_change_state_file(tmp_path):
    from ml.changes import ChangeState, mark
    from tests.ml_helpers import timetable

    days = weekdays(7)
    events = timetable(days[:5], {"100": H8}) + timetable(days[5:], {"100": H8 + 300})
    db = write_db(tmp_path / "c.sqlite", events)
    state = ChangeState()
    mark(state, [SCOPE_8], days[5], today=days[6], note="")
    state_path = tmp_path / "change_state.json"
    state.save(state_path)
    out = tmp_path / "report.md"
    code = main(
        ["--db", str(db), "--out", str(out), "--test-days", "2", "--changes", str(state_path)]
    )
    assert code == 0
    text = out.read_text(encoding="utf-8")
    assert "구간별" in text and "변경 직후" in text
    assert "공식 시간표가 바뀌었으면" in text
    broken = tmp_path / "broken.json"
    broken.write_text("{", encoding="utf-8")
    assert main(["--db", str(db), "--out", str(out), "--changes", str(broken)]) == 2
