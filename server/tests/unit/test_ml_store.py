import sqlite3
import zlib
from dataclasses import replace
from datetime import date, datetime, timedelta

import pytest

from app.core.clock import KST
from ml.parse import Event
from ml.store import Store

T0 = datetime(2026, 11, 15, 15, 0, 0, tzinfo=KST)
DAY = date(2026, 11, 15)


def _event(**over) -> Event:
    base = Event(
        service_date=DAY,
        day_type=3,
        line="5호선",
        direction=1,
        train_no="5603",
        statn_id="1005000510",
        statn_nm="방화",
        status=1,
        event_at=T0,
        dest_statn_id="1005080555",
        dest_name="마천",
        express=0,
        last_train=0,
        flag=None,
    )
    return replace(base, **over)


@pytest.fixture
def store(tmp_path):
    s = Store(tmp_path / "c.sqlite")
    yield s
    s.close()


def _rows(store: Store, sql: str) -> list[tuple]:
    store.commit()
    con = sqlite3.connect(store.path)
    try:
        return con.execute(sql).fetchall()
    finally:
        con.close()


def test_new_event_is_inserted_with_station_id(store):
    added = store.upsert_events([_event()], {"1005000510": "S1"}, T0 + timedelta(seconds=40))
    assert added == 1
    assert _rows(store, "SELECT station_id, event_at, first_seen_at, status FROM events") == [
        ("S1", "2026-11-15T15:00:00+09:00", "2026-11-15T15:00:40+09:00", 1)
    ]


def test_earlier_time_replaces_and_later_does_not(store):
    store.upsert_events([_event()], {}, T0)
    later = _event(event_at=T0 + timedelta(seconds=20))
    assert store.upsert_events([later], {}, T0 + timedelta(seconds=30)) == 0
    assert _rows(store, "SELECT event_at FROM events") == [("2026-11-15T15:00:00+09:00",)]
    earlier = _event(event_at=T0 - timedelta(seconds=15), flag="stale")
    assert store.upsert_events([earlier], {}, T0 + timedelta(seconds=60)) == 0
    assert _rows(store, "SELECT event_at, first_seen_at, flag FROM events") == [
        ("2026-11-15T14:59:45+09:00", "2026-11-15T15:00:00+09:00", "stale")
    ]


def test_different_status_or_station_is_a_different_event(store):
    events = [_event(), _event(status=2), _event(statn_id="1005000511")]
    assert store.upsert_events(events, {}, T0) == 3


def test_snapshot_is_stored_compressed_and_recoverable(store):
    text = '{"errorMessage": {"code": "INFO-000"}, "realtimePositionList": []}'
    store.add_snapshot("5호선", T0, "INFO-000", text)
    line, received, code, body = _rows(
        store, "SELECT line, received_at, code, body FROM raw_snapshots"
    )[0]
    assert (line, received, code) == ("5호선", "2026-11-15T15:00:00+09:00", "INFO-000")
    assert zlib.decompress(body).decode("utf-8") == text


def test_key_usage_counts_and_quota_hit(store):
    store.record_call(DAY, "position", error=False)
    store.record_call(DAY, "position", error=True)
    store.record_call(DAY, "spare1", error=False)
    assert store.blocked_keys(DAY) == []
    store.mark_quota_hit(DAY, "position", T0)
    assert store.blocked_keys(DAY) == ["position"]
    assert store.blocked_keys(DAY + timedelta(days=1)) == []
    assert _rows(
        store, "SELECT key_name, calls, errors, quota_hit_at FROM key_usage ORDER BY 1"
    ) == [
        ("position", 2, 1, "2026-11-15T15:00:00+09:00"),
        ("spare1", 1, 0, None),
    ]


def test_quota_hit_without_prior_call_still_recorded(store):
    store.mark_quota_hit(DAY, "spare2", T0)
    assert store.blocked_keys(DAY) == ["spare2"]


def test_station_map_keeps_first_sighting(store):
    assert store.map_station("5호선", "1005000510", "방화", "S1", DAY) is True
    assert store.map_station("5호선", "1005000510", "방화", "S1", DAY + timedelta(days=1)) is False
    assert store.map_station("5호선", "1005000999", "없는역", None, DAY) is True
    assert store.known_stations("5호선") == {"1005000510": "S1", "1005000999": None}
    assert store.known_stations("2호선") == {}
    assert store.unmapped_stations() == [("5호선", "1005000999", "없는역")]
    assert _rows(store, "SELECT first_seen FROM station_map WHERE statn_id='1005000510'") == [
        ("2026-11-15",)
    ]


def test_reopening_keeps_data(tmp_path):
    first = Store(tmp_path / "c.sqlite")
    first.upsert_events([_event()], {}, T0)
    first.close()
    second = Store(tmp_path / "c.sqlite")
    assert second.upsert_events([_event()], {}, T0) == 0
    second.close()
