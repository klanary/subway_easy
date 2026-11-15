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
    assert art["kind"] == "b1" and art["version"] == 1
    assert art["modes"] == {"9호선": "train_no"}
    (entry,) = art["cells"][CELL_A]
    assert entry == {
        "secs": H8 + 20 + 30, "train_no": "100", "n_days": 3, "dest_name": "D행", "express": 0,
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
