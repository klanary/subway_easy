import re

import httpx
import respx

from app.services.station_index import StationIndex
from ml.b0 import B0, TIMETABLE_LINES, TimetableCache, fetch_timetables, main, station_codes
from tests.helpers import fixture_response, load_fixture
from tests.ml_helpers import MON, trip, write_db

TIMETABLE_URL = re.compile(r"http://openapi\.seoul\.go\.kr:8088/[^/]+/json/SearchSTNTimeTable.*")
CODES = {("5호선", "X"): "2524"}  # 영등포구청(5호선)


def _rows() -> list[dict]:
    _, body = load_fixture("seoul_timetable_yeongdeungpo_weekday_up")
    return body["SearchSTNTimeTableByIDService"]["row"]


def _b0(tmp_path) -> B0:
    cache = TimetableCache(tmp_path / "tt")
    cache.save("2524", 1, 1, _rows())
    return B0(cache, CODES)


def test_cache_round_trip_and_miss(tmp_path):
    cache = TimetableCache(tmp_path / "tt")
    assert cache.rows("2524", 1, 1) is None
    cache.save("2524", 1, 1, _rows())
    assert len(TimetableCache(tmp_path / "tt").rows("2524", 1, 1)) == 198
    assert cache.rows("2524", 1, 2) is None


def test_departure_of_the_same_train_number(tmp_path):
    b0 = _b0(tmp_path)
    # 5004: 도착 05:49:50, 출발 05:50:20. 방향 0(상행) = INOUT_TAG 1.
    assert b0.departure(1, "5호선", 0, "X", train_no="5004", near_secs=0) == 21020
    assert b0.arrival(1, "5호선", 0, "X", train_no="5004") == 20990


def test_departure_falls_back_to_the_nearest_time(tmp_path):
    b0 = _b0(tmp_path)
    assert b0.departure(1, "5호선", 0, "X", train_no="없는번호", near_secs=21000) == 21020
    assert b0.departure(1, "5호선", 0, "X", train_no="없는번호", near_secs=3 * 3600) is None
    assert b0.arrival(1, "5호선", 0, "X", train_no="없는번호") is None


def test_after_midnight_rows_use_hours_past_24(tmp_path):
    # 5214: 24:33:40 도착, 24:34:10 출발.
    assert _b0(tmp_path).departure(1, "5호선", 0, "X", train_no="5214", near_secs=0) == 88450


def test_unknown_station_day_or_direction_is_none(tmp_path):
    b0 = _b0(tmp_path)
    assert b0.departure(1, "5호선", 0, "모르는역", train_no="5004", near_secs=21000) is None
    assert b0.departure(2, "5호선", 0, "X", train_no="5004", near_secs=21000) is None
    assert b0.departure(1, "5호선", 1, "X", train_no="5004", near_secs=21000) is None


def test_station_codes_cover_only_timetable_lines(tmp_path):
    index = StationIndex.load("data/stations.sqlite")
    banghwa = index.resolve("방화", "5호선")
    seoul = index.resolve("서울역", "1호선")
    events = trip(MON, 8 * 3600, line="5호선", stations=("1005000510",), statuses=(1,))
    events = [o.__class__(**{**vars(o), "station_id": banghwa.id}) for o in events]
    more = trip(MON, 8 * 3600, line="1호선", stations=("1001000133",), statuses=(1,))
    events += [o.__class__(**{**vars(o), "station_id": seoul.id}) for o in more]
    db = write_db(tmp_path / "c.sqlite", events)
    codes = station_codes(db, index)
    assert codes == {("5호선", "1005000510"): index.station_code(banghwa.id, "5호선")}
    assert "1호선" not in TIMETABLE_LINES and len(TIMETABLE_LINES) == 8


@respx.mock
def test_fetch_saves_rows_and_skips_what_is_cached(tmp_path):
    route = respx.get(TIMETABLE_URL).mock(
        return_value=fixture_response("seoul_timetable_yeongdeungpo_weekday_up")
    )
    cache = TimetableCache(tmp_path / "tt")
    targets = [("2524", 1, 1), ("2524", 1, 2)]
    with httpx.Client() as http:
        assert fetch_timetables(http, "GENERALKEY", targets, cache, sleep=lambda s: None) == (2, 0)
        assert fetch_timetables(http, "GENERALKEY", targets, cache, sleep=lambda s: None) == (0, 0)
    assert route.call_count == 2
    assert route.calls[0].request.url.path.endswith("/1/1000/2524/1/1/")
    assert len(cache.rows("2524", 1, 2)) == 198


@respx.mock
def test_fetch_keeps_no_data_as_empty_and_counts_failures(tmp_path, caplog):
    cache = TimetableCache(tmp_path / "tt")
    replies = [
        fixture_response("seoul_timetable_nodata"),
        httpx.Response(500, text="x"),
        httpx.ConnectError("http://openapi.seoul.go.kr:8088/GENERALKEY/json"),
    ]
    respx.get(TIMETABLE_URL).mock(side_effect=replies)
    targets = [("1", 1, 1), ("2", 1, 1), ("3", 1, 1)]
    with httpx.Client() as http:
        assert fetch_timetables(http, "GENERALKEY", targets, cache, sleep=lambda s: None) == (1, 2)
    assert cache.rows("1", 1, 1) == []
    assert cache.rows("2", 1, 1) is None
    assert "GENERALKEY" not in caplog.text


def test_main_needs_db_and_key(tmp_path, monkeypatch):
    monkeypatch.delenv("SEOUL_OPENAPI_KEY", raising=False)
    env = tmp_path / ".env"
    env.write_text("SEOUL_POSITION_API_KEY=POS\n", encoding="utf-8")
    db = write_db(tmp_path / "c.sqlite", trip(MON, 8 * 3600))
    base = ["--cache", str(tmp_path / "tt"), "--env-file", str(env)]
    assert main(["--db", str(tmp_path / "없음.sqlite"), *base]) == 2
    assert main(["--db", str(db), *base]) == 2  # 일반 키 없음
