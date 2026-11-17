import json
from datetime import date

from ml.b1 import MeasuredTimetable, build_b1, departure_points, main, train_no_stability
from ml.dataset import build_runs
from tests.ml_helpers import MON, trip, weekdays, write_db

H8 = 8 * 3600
CELL_A = "1|9호선|0|A"


def _runs(starts_by_day: dict[date, list[tuple[str, int]]], **kw):
    events = []
    for day, trains in starts_by_day.items():
        for train_no, start in trains:
            events += trip(day, start, train_no=train_no, **kw)
    return build_runs(events)


def test_median_departure_per_train_number():
    d1, d2, d3 = weekdays(3)
    runs = _runs({d1: [("100", H8)], d2: [("100", H8 + 20)], d3: [("100", H8 + 60)]}, dwell=30)
    art = build_b1(runs, min_days=3)
    assert art["kind"] == "b1" and art["version"] == 2
    assert art["modes"] == {"9호선": "train_no"}
    (entry,) = art["cells"][CELL_A]
    assert entry == {
        "secs": H8 + 20 + 30, "train_no": "100", "n_days": 3, "dest_name": "D행", "express": 0,
        "first_seen": d1.isoformat(), "last_seen": d3.isoformat(),
    }  # fmt: skip
    assert (art["date_from"], art["date_to"]) == (d1.isoformat(), d3.isoformat())
    assert art["n_dates"] == {"1": 3}


def test_cells_under_min_days_are_left_out():
    d1, d2 = weekdays(2)
    runs = _runs({d1: [("100", H8)], d2: [("100", H8)]})
    assert build_b1(runs, min_days=3)["cells"] == {}
    assert CELL_A in build_b1(runs, min_days=2)["cells"]


def test_terminal_station_has_no_departure():
    runs = _runs({d: [("100", H8)] for d in weekdays(3)})
    cells = build_b1(runs, min_days=3)["cells"]
    assert "1|9호선|0|D" not in cells
    assert set(cells) == {CELL_A, "1|9호선|0|B", "1|9호선|0|C"}


def test_arrival_only_visit_uses_line_dwell_then_fallback():
    # 출발이 찍히지 않은 역은 도착 + 그 호선의 정차 중앙값(표본이 모자라면 fallback_dwell).
    runs = _runs({d: [("100", H8)] for d in weekdays(3)}, statuses=(1,))
    points = departure_points(runs, fallback_dwell=30)
    assert {p.estimated for p in points} == {True}
    (entry,) = build_b1(runs, min_days=3, fallback_dwell=30)["cells"][CELL_A]
    assert entry["secs"] == H8 + 30


def test_day_types_are_separate_cells():
    saturday = date(2026, 11, 7)
    runs = _runs({MON: [("100", H8)], saturday: [("100", H8 + 600)]})
    cells = build_b1(runs, min_days=1)["cells"]
    assert cells[CELL_A][0]["secs"] == H8 + 30
    assert cells["2|9호선|0|A"][0]["secs"] == H8 + 630


def test_stability_tells_whether_train_numbers_repeat():
    days = weekdays(3)
    stable = _runs({d: [("100", H8 + 10 * i)] for i, d in enumerate(days)})
    assert train_no_stability(stable) == {"9호선": 1.0}
    moving = _runs({d: [("100", H8 + 1800 * i)] for i, d in enumerate(days)})
    assert train_no_stability(moving) == {"9호선": 0.0}
    assert train_no_stability(_runs({days[0]: [("100", H8)]})) == {}


def test_slots_mode_groups_nearby_departures_when_numbers_change_daily():
    days = weekdays(3)
    runs = _runs(
        {
            d: [(f"{i}1", H8 + 20 * i), (f"{i}2", H8 + 600 + 20 * i)]  # 날마다 번호가 다름
            for i, d in enumerate(days)
        }
    )
    art = build_b1(runs, min_days=3, slot_width=120, mode="slots")
    assert art["modes"] == {"9호선": "slots"}
    entries = art["cells"][CELL_A]
    assert [(e["secs"], e["train_no"], e["n_days"]) for e in entries] == [
        (H8 + 20 + 30, None, 3),
        (H8 + 620 + 30, None, 3),
    ]


def test_slots_never_put_two_trains_of_one_day_in_one_slot():
    d1, d2 = weekdays(2)
    runs = _runs({d1: [("1", H8), ("2", H8 + 60)], d2: [("3", H8 + 10), ("4", H8 + 70)]})
    entries = build_b1(runs, min_days=2, slot_width=120, mode="slots")["cells"][CELL_A]
    assert [(e["secs"] - 30, e["n_days"]) for e in entries] == [(H8 + 5, 2), (H8 + 65, 2)]


def test_auto_mode_picks_slots_for_unstable_numbers():
    days = weekdays(3)
    runs = _runs({d: [("100", H8 + 1800 * i)] for i, d in enumerate(days)})
    assert build_b1(runs, min_days=1, mode="auto")["modes"] == {"9호선": "slots"}


def test_lookup_departures_after_a_time():
    days = weekdays(3)
    runs = _runs({d: [("100", H8), ("101", H8 + 600), ("102", H8 + 1200)] for d in days})
    table = MeasuredTimetable(build_b1(runs, min_days=3))
    found = table.departures(1, "9호선", 0, "A", after_secs=H8 + 100, count=2)
    assert [(e.train_no, e.secs) for e in found] == [("101", H8 + 630), ("102", H8 + 1230)]
    assert table.departures(1, "9호선", 0, "없는역", after_secs=0, count=2) == []
    assert table.departures(3, "9호선", 0, "A", after_secs=0, count=2) == []
    assert table.entry_for(1, "9호선", 0, "A", "101").secs == H8 + 630
    assert table.entry_for(1, "9호선", 0, "A", "999") is None
    assert table.nearest(1, "9호선", 0, "A", H8 + 700).train_no == "101"


def test_after_midnight_departure_stays_on_the_service_day():
    runs = _runs({d: [("900", 24 * 3600 + 600)] for d in weekdays(3)})
    (entry,) = build_b1(runs, min_days=3)["cells"][CELL_A]
    assert entry["secs"] == 87030


def test_empty_input_builds_an_empty_table():
    art = build_b1([], min_days=3)
    assert art["cells"] == {} and art["date_from"] is None
    assert MeasuredTimetable(art).departures(1, "9호선", 0, "A", after_secs=0, count=3) == []


def test_cli_writes_artifact(tmp_path, capsys):
    events = [o for d in weekdays(3) for o in trip(d, H8)]
    db = write_db(tmp_path / "c.sqlite", events)
    out = tmp_path / "data" / "measured_timetable.json"
    assert main(["--db", str(db), "--out", str(out)]) == 0
    art = json.loads(out.read_text(encoding="utf-8"))
    assert len(art["cells"]) == 3
    assert art["stations"]["9호선|A"] == {"station_id": "S-A", "name": "A역"}
    assert "3" in capsys.readouterr().out
    assert main(["--db", str(tmp_path / "없음.sqlite"), "--out", str(out)]) == 2


def test_departure_points_can_reuse_a_given_dwell():
    from ml.b1 import line_dwell

    with_dep = _runs({d: [(str(i), H8 + 600 * i) for i in range(8)] for d in weekdays(3)}, dwell=47)
    assert line_dwell(with_dep, fallback=30) == {"9호선": 47}  # 표본 20개 이상이면 중앙값
    arrivals = _runs({MON: [("100", H8)]}, statuses=(1,))
    points = departure_points(arrivals, dwell={"9호선": 47})
    assert points[0].secs == H8 + 47
    assert build_b1(with_dep, min_days=3)["dwell"] == {"9호선": 47}


# --- 최근 창, 안 보인 열차 빼기, 변경 기준일(시간표 변경 계획 Task 2) ---


def _points(days, starts, **kw):
    from tests.ml_helpers import timetable

    return departure_points(build_runs(timetable(days, starts, **kw)))


def _a(artifact, key=CELL_A):
    return {e["train_no"]: e for e in artifact["cells"].get(key, [])}


def test_window_keeps_only_recent_dates():
    from ml.b1 import build_b1_from_points

    days = weekdays(6)
    points = _points(days[:3], {"100": H8}) + _points(days[3:], {"100": H8 + 600})
    entry = _a(build_b1_from_points(points, window={1: 3}))["100"]
    assert (entry["secs"], entry["n_days"]) == (H8 + 600 + 30, 3)
    # 창이 없으면 6일이 다 쓰인다.
    # (시각이 10분 벌어져 자동으로는 묶음 방식이 되므로 방식을 정해 준다.)
    whole = build_b1_from_points(points, modes={"9호선": "train_no"})
    assert _a(whole)["100"]["n_days"] == 6


def test_as_of_ignores_later_dates():
    from ml.b1 import build_b1_from_points

    days = weekdays(6)
    points = _points(days[:3], {"100": H8}) + _points(days[3:], {"100": H8 + 600})
    art = build_b1_from_points(points, as_of=days[2], window={1: 3})
    assert _a(art)["100"]["secs"] == H8 + 30
    assert art["as_of"] == days[2].isoformat()


def test_train_gone_for_stale_days_is_dropped():
    from ml.b1 import build_b1_from_points

    days = weekdays(6)
    points = _points(days[:3], {"100": H8}) + _points(days, {"200": H8 + 300})
    assert set(_a(build_b1_from_points(points, stale_days={1: 3}))) == {"200"}
    assert set(_a(build_b1_from_points(points, stale_days={1: 4}))) == {"100", "200"}
    assert set(_a(build_b1_from_points(points))) == {"100", "200"}


def test_days_without_coverage_do_not_count_as_absence():
    from ml.b1 import build_b1_from_points

    days = weekdays(6)
    points = _points(days[:3], {"100": H8}) + _points(days, {"200": H8 + 300})
    active = {(1, "9호선", 0, 8): set(days[:3]) | {days[4]}}
    art = build_b1_from_points(points, stale_days={1: 3}, active=active)
    assert set(_a(art)) == {"100", "200"}


def test_epoch_drops_days_before_change_and_needs_two_days():
    from ml.b1 import build_b1_from_points

    days = weekdays(7)
    points = _points(days[:5], {"100": H8}) + _points(days[5:], {"100": H8 + 300})
    assert _a(build_b1_from_points(points))["100"]["secs"] == H8 + 30  # 기준일이 없으면 옛 시각 쪽
    art = build_b1_from_points(points, epochs={(1, "9호선", 0, 8): days[5]})
    entry = _a(art)["100"]
    assert (entry["secs"], entry["n_days"]) == (H8 + 300 + 30, 2)
    assert (entry["first_seen"], entry["last_seen"]) == (days[5].isoformat(), days[6].isoformat())
    assert art["scopes"] == {}


def test_scope_with_one_day_after_change_is_retraining():
    from ml.b1 import build_b1_from_points

    days = weekdays(6)
    points = _points(days[:5], {"100": H8, "300": 10 * 3600}) + _points(
        days[5:], {"100": H8 + 300, "300": 10 * 3600}
    )
    art = build_b1_from_points(points, epochs={(1, "9호선", 0, 8): days[5]})
    assert "100" not in _a(art)
    assert "300" in _a(art)  # 다른 시간대(10시)는 그대로
    assert art["scopes"]["1|9호선|0|8"] == {"state": "retraining", "since": days[5].isoformat()}
    table = MeasuredTimetable(art)
    assert table.scope_state(1, "9호선", 0, 28900) == "retraining"
    assert table.scope_state(1, "9호선", 0, 10 * 3600) == "ok"
    assert table.scope_state(2, "9호선", 0, 28900) == "ok"


def test_epoch_margin_covers_trains_near_band_edge():
    from ml.b1 import build_b1_from_points

    days = weekdays(7)
    before, after = 8 * 3600 + 58 * 60 - 30, 9 * 3600 + 60 - 30  # A역 출발 08:58:00 → 09:01:00
    points = _points(days[:5], {"100": before}) + _points(days[5:], {"100": after})
    art = build_b1_from_points(points, epochs={(1, "9호선", 0, 8): days[5]})
    entry = _a(art)["100"]
    assert (entry["secs"], entry["n_days"]) == (9 * 3600 + 60, 2)


def test_version1_artifact_still_loads():
    old = {"version": 1, "cells": {CELL_A: [
        {"secs": H8, "train_no": "100", "n_days": 3, "dest_name": "D행", "express": 0}
    ]}}  # fmt: skip
    table = MeasuredTimetable(old)
    assert table.departures(1, "9호선", 0, "A", after_secs=0, count=1)[0].first_seen is None
    assert table.scope_state(1, "9호선", 0, H8) == "ok"


def test_slots_mode_entries_carry_last_seen_and_obey_stale_days():
    from ml.b1 import build_b1_from_points

    days = weekdays(6)
    points = _points(days[:3], {"1": H8}) + _points(days, {"2": H8 + 600})
    modes = {"9호선": "slots"}
    art = build_b1_from_points(points, modes=modes)
    first, second = art["cells"][CELL_A]
    assert (first["train_no"], first["last_seen"]) == (None, days[2].isoformat())
    assert (second["first_seen"], second["last_seen"]) == (days[0].isoformat(), days[5].isoformat())
    stale = build_b1_from_points(points, modes=modes, stale_days={1: 3})
    assert [e["secs"] for e in stale["cells"][CELL_A]] == [H8 + 600 + 30]


def test_build_b1_passes_new_options_through():
    days = weekdays(6)
    from tests.ml_helpers import timetable

    runs = build_runs(timetable(days[:3], {"100": H8}) + timetable(days[3:], {"100": H8 + 600}))
    art = build_b1(runs, window={1: 3})
    assert art["version"] == 2 and art["window"] == {"1": 3}
    assert _a(art)["100"]["secs"] == H8 + 600 + 30


def test_stability_survives_a_few_badly_delayed_days():
    # 7일 가운데 이틀이 크게 늦어도(3분, 7분) 같은 번호가 같은 시각에 다니는 노선이다.
    from ml.b1 import stability_from_points

    days = weekdays(7)
    points = _points(days[:5], {"100": H8}) + _points([days[5]], {"100": H8 + 180})
    points += _points([days[6]], {"100": H8 + 400})
    assert stability_from_points(points) == {"9호선": 1.0}
    # 날마다 30분씩 다르면 여전히 불안정이다.
    moving = [p for i, d in enumerate(days) for p in _points([d], {"100": H8 + 1800 * i})]
    assert stability_from_points(moving) == {"9호선": 0.0}


# --- 서지 않고 지나가는 급행 ---


def express_run(visits, train_no="9501", express=1):
    from ml.dataset import Run

    return Run(
        service_date=MON, day_type=1, line="9호선", direction=0, train_no=train_no,
        express=express, dest_statn_id="end", dest_name="끝행", visits=tuple(visits),
    )  # fmt: skip


def _trains(make_visits, n):
    return [express_run(make_visits(H8 + i * 600), train_no=f"95{i:02d}") for i in range(n)]


def test_express_visit_with_a_few_seconds_between_arrival_and_departure_is_a_pass():
    from ml.dataset import Visit

    run = express_run(
        [
            Visit("stop", arr=H8, dep=H8 + 80),
            Visit("thru", arr=H8 + 200, dep=H8 + 209),
            Visit("end", arr=H8 + 400),
        ]
    )
    assert [p.statn_id for p in departure_points([run], fallback_dwell=30)] == ["stop"]


def test_local_train_with_a_short_dwell_is_kept():
    from ml.dataset import Visit

    run = express_run([Visit("a", arr=H8, dep=H8 + 9), Visit("end", arr=H8 + 400)], express=0)
    assert [p.statn_id for p in departure_points([run], fallback_dwell=30)] == ["a"]


def test_departure_only_visit_follows_the_majority_at_that_station():
    from ml.b1 import passed_stations
    from ml.dataset import Visit

    seen = _trains(lambda t: [Visit("thru", arr=t, dep=t + 8), Visit("end", arr=t + 300)], 5)
    only_dep = express_run(
        [Visit("thru", dep=H8 + 5000), Visit("end", arr=H8 + 5300)], train_no="9599"
    )
    assert ("9호선", 0, 1, "thru") in passed_stations(seen + [only_dep])
    assert departure_points(seen + [only_dep], fallback_dwell=30) == []


def test_arrival_only_visit_at_a_stop_station_is_kept():
    from ml.dataset import Visit

    seen = _trains(lambda t: [Visit("stop", arr=t, dep=t + 80), Visit("end", arr=t + 300)], 5)
    only_arr = express_run(
        [Visit("stop", arr=H8 + 5000), Visit("end", arr=H8 + 5300)], train_no="9599"
    )
    points = departure_points(seen + [only_arr], fallback_dwell=30)
    assert len(points) == 6 and points[-1].estimated


def test_one_sided_visit_without_enough_evidence_is_kept():
    from ml.dataset import Visit

    only_dep = express_run([Visit("thru", dep=H8), Visit("end", arr=H8 + 300)])
    assert len(departure_points([only_dep], fallback_dwell=30)) == 1


def test_pass_visits_do_not_pull_down_the_line_dwell():
    from ml.b1 import line_dwell
    from ml.dataset import Visit

    runs = _trains(
        lambda t: [
            Visit("stop", arr=t, dep=t + 60),
            Visit("thru", arr=t + 200, dep=t + 208),
            Visit("end", arr=t + 400),
        ],
        20,
    )
    assert line_dwell(runs, fallback=30) == {"9호선": 60}
