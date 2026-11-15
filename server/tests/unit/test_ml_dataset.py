from dataclasses import replace
from datetime import date

from ml.dataset import build_runs, load_events, station_names
from tests.ml_helpers import MON, trip, write_db


def test_load_events_reads_seconds_of_service_day(tmp_path):
    events = trip(MON, 8 * 3600, stations=("A", "B"))
    db = write_db(tmp_path / "c.sqlite", events)
    loaded = load_events(db)
    assert sorted(loaded, key=lambda o: (o.secs, o.status)) == sorted(
        events, key=lambda o: (o.secs, o.status)
    )


def test_after_midnight_event_belongs_to_previous_service_day(tmp_path):
    late = trip(MON, 24 * 3600 + 1800, stations=("A",), statuses=(1,))
    db = write_db(tmp_path / "c.sqlite", late)
    (obs,) = load_events(db)
    assert (obs.service_date, obs.secs) == (date(2026, 11, 2), 88200)


def test_flagged_events_are_left_out_by_default(tmp_path):
    events = trip(MON, 8 * 3600, stations=("A", "B"), statuses=(1,))
    events[1] = replace(events[1], flag="stale")
    db = write_db(tmp_path / "c.sqlite", events)
    assert [o.statn_id for o in load_events(db)] == ["A"]
    assert len(load_events(db, include_flagged=True)) == 2


def test_missing_db_table_or_empty_db_gives_empty_list(tmp_path):
    db = write_db(tmp_path / "c.sqlite", [])
    assert load_events(db) == []
    assert build_runs([]) == []


def test_run_groups_statuses_into_visits_in_time_order():
    events = trip(MON, 8 * 3600, statuses=(0, 1, 2, 3), hop=120, dwell=30)
    (run,) = build_runs(list(reversed(events)))
    assert (run.line, run.direction, run.train_no, run.service_date) == ("9호선", 0, "100", MON)
    assert [v.statn_id for v in run.visits] == ["A", "B", "C", "D"]
    first = run.visits[0]
    assert (first.approach, first.enter, first.arr, first.dep) == (28740, 28785, 28800, 28830)
    assert run.dest_statn_id == "D"


def test_visit_with_partial_statuses_keeps_none():
    events = trip(MON, 8 * 3600, stations=("A", "B"), statuses=(2,))
    (run,) = build_runs(events)
    assert (run.visits[0].arr, run.visits[0].dep) == (None, 28830)
    assert run.visits[0].first == 28830


def test_runs_split_by_day_direction_and_train():
    events = (
        trip(MON, 8 * 3600, train_no="100")
        + trip(MON, 9 * 3600, train_no="101")
        + trip(MON, 10 * 3600, train_no="100", direction=1, stations=("D", "C"))
        + trip(date(2026, 11, 3), 8 * 3600, train_no="100")
    )
    runs = build_runs(events)
    assert len(runs) == 4
    assert [(r.service_date, r.direction, r.train_no) for r in runs] == [
        (MON, 0, "100"),
        (MON, 0, "101"),
        (MON, 1, "100"),
        (date(2026, 11, 3), 0, "100"),
    ]


def test_express_run_is_marked():
    (run,) = build_runs(trip(MON, 8 * 3600, express=1))
    assert run.express == 1


def test_station_names_come_from_station_map(tmp_path):
    db = write_db(tmp_path / "c.sqlite", trip(MON, 8 * 3600, stations=("A",)))
    assert station_names(db) == {("9호선", "A"): "A역"}
