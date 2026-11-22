"""위치 계산 되돌려 보기(설계 2026-11-22 §9): 문구 어림·시간표와 노선별로 견준다."""

from app.services.trips import TripsData
from ml.dataset import build_runs
from ml.position_eval import evaluate, measured_from_runs, suggest
from tests.ml_helpers import trip, weekdays

H7 = 7 * 3600


def _trips() -> TripsData:
    stations = ["S-A", "S-B", "S-C", "S-D"]
    row = [None, False, "D행", 1, False, [0] * 8]
    return TripsData.from_artifact(
        {
            "kind": "trips-server",
            "version": 1,
            "built_on": "2026-11-02",
            "stations": stations,
            "day_types": {
                "1": [{"line": "9호선", "direction": "상행", "stops": [0, 1, 2, 3], "trips": [row]}]
            },
        }
    )


def _runs(days):
    events = []
    for day in days:
        for k in range(30):  # 07:00부터 6분마다 30대, 구간 120초·정차 30초
            events += trip(day, H7 + 360 * k, train_no=str(100 + k))
    return build_runs(events)


def test_scores_on_synthetic_day():
    train_day, test_day = weekdays(2)
    train, test = _runs([train_day]), _runs([test_day])
    statn = {("9호선", s): f"S-{s}" for s in "ABCD"}
    measured = measured_from_runs(train, statn)
    scores = evaluate(test, measured=measured, trips=_trips(), statn_to_station=statn,
                      timetable_first=lambda *a: None, hours=(7, 9))  # fmt: skip
    line = scores["9호선"]
    assert line[("position", 0)]["n"] > 0 and line[("position", 0)]["mae"] <= 5
    assert line[("text", 0)]["mae"] <= 5
    assert line.get(("timetable", 5)) is None  # 시간표가 없으면 그 칸은 비어 있다


def test_report_suggests_lines():
    def m(mae, within):
        return {"n": 100, "mae": mae, "p90": mae, "within60": within, "within120": within}

    scores = {
        "1호선": {("position", 0): m(30, 0.9), ("text", 0): m(80, 0.5),
                 ("combined", 15): m(60, 0.6), ("timetable", 15): m(90, 0.4)},
        "2호선": {("position", 0): m(30, 0.9), ("text", 0): m(20, 0.95),
                 ("combined", 15): m(60, 0.6), ("timetable", 15): m(40, 0.7)},
    }  # fmt: skip
    now_lines, at_lines = suggest(scores)
    assert now_lines == ["1호선"] and at_lines == ["1호선"]


def test_combined_scores_same_questions_as_timetable():
    """[시각 선택] 비교는 서버가 보이는 것(위치, 없으면 시간표)을
    시간표와 같은 질문에서 견준다(최종 리뷰)."""
    train_day, test_day = weekdays(2)
    train, test = _runs([train_day]), _runs([test_day])
    statn = {("9호선", s): f"S-{s}" for s in "ABCD"}
    scores = evaluate(
        test, measured=measured_from_runs(train, statn), trips=_trips(), statn_to_station=statn,
        timetable_first=lambda line, d, sid, day, ask: ask + 600, hours=(7, 9),
    )  # fmt: skip
    line = scores["9호선"]
    for h in (5, 15):
        assert line[("combined", h)]["n"] == line[("timetable", h)]["n"]


def test_default_hours_cover_after_midnight():
    """자정 넘은 운행(24:05)도 평가한다(최종 리뷰 사소한 것: 07~22시만 보면 자정 전후를 못 본다)."""
    train_day, test_day = weekdays(2)
    events = []
    for day in (train_day, test_day):
        for k in range(10):
            events += trip(day, 24 * 3600 + 300 * k, train_no=str(500 + k))
    runs = build_runs(events)
    train = [r for r in runs if r.service_date == train_day]
    test = [r for r in runs if r.service_date == test_day]
    statn = {("9호선", s): f"S-{s}" for s in "ABCD"}
    scores = evaluate(test, measured=measured_from_runs(train, statn), trips=_trips(),
                      statn_to_station=statn, timetable_first=lambda *a: None)  # fmt: skip
    assert scores["9호선"][("position", 0)]["n"] > 0


def test_timetable_comes_from_training_days_only():
    """실측 시간표 비교는 학습 날짜로 만든다(시험 날짜가 섞이면 시간표가 유리해진다)."""
    from datetime import date as _date

    from ml.position_eval import timetable_from_runs

    train_day, _ = weekdays(2)
    first = timetable_from_runs(_runs([train_day]), {("9호선", s): f"S-{s}" for s in "ABCD"})
    got = first("9호선", "상행", "S-A", _date(2026, 11, 10), H7)
    assert got == H7 + 30  # 첫 열차 07:00 도착 + 정차 30초 출발
