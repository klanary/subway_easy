from datetime import datetime, timedelta

import pytest

from app.core.clock import KST
from ml.b1 import departure_points
from ml.dataset import build_runs, load_events, service_dates
from ml.points import PointStore
from ml.store import Store
from tests.ml_helpers import cover, timetable, trip, weekdays, write_db

H8 = 8 * 3600
D1, D2, D3 = weekdays(3)


@pytest.fixture
def db(tmp_path):
    events = timetable([D1, D2, D3], {"100": H8, "101": H8 + 600})
    events += timetable([D2], {"500": H8}, line="5호선")
    return write_db(tmp_path / "c.sqlite", events)


@pytest.fixture
def store(tmp_path):
    s = PointStore(tmp_path / "points.sqlite")
    yield s
    s.close()


def _key(p):
    return (p.statn_id, p.train_no, p.secs, p.direction, p.estimated)


def test_load_events_filters_by_date_and_line(db):
    everything = load_events(db)
    only = load_events(db, date_from=D2, date_to=D2, line="9호선")
    assert {(o.service_date, o.line) for o in only} == {(D2, "9호선")}
    assert len(only) == len([o for o in everything if o.service_date == D2 and o.line == "9호선"])
    assert len(load_events(db, date_from=D2)) == len(
        [o for o in everything if o.service_date >= D2]
    )
    assert len(load_events(db, line="5호선")) == len([o for o in everything if o.line == "5호선"])


def test_service_dates_lists_days_with_events(db):
    assert service_dates(db, date_from=D1, date_to=D3) == [D1, D2, D3]
    assert service_dates(db, date_from=D2, date_to=D2) == [D2]
    assert service_dates(db, date_from=D3 + timedelta(days=1), date_to=D3 + timedelta(days=9)) == []


def test_extract_day_matches_departure_points(db, store):
    n = store.extract_day(db, D2)
    expected = departure_points(build_runs(load_events(db, date_from=D2, date_to=D2)))
    assert n == len(expected) > 0
    got = store.points("9호선", 1, [D2]) + store.points("5호선", 1, [D2])
    assert sorted(map(_key, got)) == sorted(map(_key, expected))
    assert {p.service_date for p in got} == {D2}
    assert store.points("9호선", 1, [D1]) == []


def test_extract_day_twice_gives_the_same_rows(db, store):
    first = store.extract_day(db, D2)
    assert store.extract_day(db, D2) == first
    assert len(store.points("9호선", 1, [D2])) + len(store.points("5호선", 1, [D2])) == first


def test_extract_day_can_be_limited_to_lines(db, store):
    store.extract_day(db, D2, lines=["5호선"])
    assert store.lines(D2) == [("5호선", 1)]


def test_dates_and_lines(db, store):
    for day in (D1, D2, D3):
        store.extract_day(db, day)
    assert store.dates("9호선", 1, upto=D3, n=2) == [D2, D3]
    assert store.dates("9호선", 1, upto=D2, n=5) == [D1, D2]
    assert store.dates("9호선", 2, upto=D3, n=5) == []
    assert store.lines(D2) == [("5호선", 1), ("9호선", 1)]
    assert store.lines(D1) == [("9호선", 1)]


def test_coverage_is_unknown_without_raw_and_counts_ok_responses(tmp_path, db, store):
    store.extract_day(db, D2)
    assert store.coverage("9호선", D2) is None
    raw = Store(db)
    at = datetime(D2.year, D2.month, D2.day, 6, 10, tzinfo=KST)
    for i in range(3):
        raw.add_snapshot("9호선", at + timedelta(seconds=30 * i), "INFO-000", "{}")
    raw.add_snapshot("9호선", at + timedelta(minutes=5), "ERROR-500", "{}")
    raw.add_snapshot("9호선", at + timedelta(hours=18, minutes=20), "INFO-000", "{}")  # 00:30
    raw.add_snapshot("9호선", at + timedelta(hours=19), "INFO-000", "{}")  # 다음 운행일 01:10
    raw.close()
    store.extract_day(db, D2)
    assert store.coverage("9호선", D2) == {6: 3, 24: 1}
    assert store.coverage("5호선", D2) is None


def test_set_coverage_and_helper(store):
    store.set_coverage("9호선", D1, {6: 10})
    assert store.coverage("9호선", D1) == {6: 10}
    cover(store, [D2])
    assert store.coverage("9호선", D2) == {band: 120 for band in range(5, 25)}


def test_after_midnight_departure_is_band_24_of_the_service_day(tmp_path, store):
    db = write_db(tmp_path / "late.sqlite", trip(D1, 24 * 3600 + 1800))
    store.extract_day(db, D1)
    points = store.points("9호선", 1, [D1])
    assert min(p.secs for p in points) == 88200 + 30
    assert {p.service_date for p in points} == {D1}


def test_prune_removes_old_days(db, store):
    for day in (D1, D2, D3):
        store.extract_day(db, day)
    store.set_coverage("9호선", D1, {6: 120})
    assert store.prune(D2) > 0
    assert store.dates("9호선", 1, upto=D3, n=5) == [D2, D3]
    assert store.coverage("9호선", D1) is None


def _snap(raw, at, n, code="INFO-000"):
    import json

    body = json.dumps({"realtimePositionList": [{"trainNo": str(i)} for i in range(n)]})
    raw.add_snapshot("9호선", at, code, body)


def test_coverage_skips_partial_answers_and_hours_with_a_hole(db, store):
    """위치 API는 노선 일부·전체가 잠깐 빠진다(2026-11-18 6호선 07:02~07:12 INFO-200·3대).

    빈약한 응답(그 시간대 중앙값의 절반 미만)은 정상으로 세지 않고, 3분 넘게 연달아 빠진 시간대는
    판단하지 않도록 0으로 둔다. 그날 6호선 07시는 99/120으로 기준(96)을 넘겨 변경 의심이 났다.
    """
    raw = Store(db)
    seven = datetime(D2.year, D2.month, D2.day, 7, 0, tzinfo=KST)
    eight = seven + timedelta(hours=1)
    for i in range(120):  # 07시: 07:05~07:10 구멍(11번 연달아 빈 응답) + 빈약한 응답 1번
        at = seven + timedelta(seconds=30 * i)
        if 10 <= i < 21:
            _snap(raw, at, 0, "INFO-200")
        else:
            _snap(raw, at, 3 if i == 30 else 20)
    for i in range(120):  # 08시: 빈약한 응답 2번만(구멍 아님)
        _snap(raw, eight + timedelta(seconds=30 * i), 4 if i in (5, 50) else 20)
    raw.close()
    store.extract_day(db, D2)
    cov = store.coverage("9호선", D2)
    assert cov.get(7, 0) == 0  # 0인 시간대는 읽을 때 빠지고, 판정은 빠진 시간대를 건너뛴다
    assert cov[8] == 118
