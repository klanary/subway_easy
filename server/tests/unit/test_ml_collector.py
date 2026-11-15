import logging
import re
import sqlite3
from datetime import date, datetime

import httpx
import pytest
import respx

from app.core.clock import KST
from app.services.station_index import StationIndex
from ml.collector import LINES, PERIOD_SEC, Collector, in_service_hours, main
from ml.keys import KeyRing
from ml.store import Store
from tests.helpers import FakeClock, fixture_response, load_fixture

POSITION_URL = re.compile(
    r"http://swopenapi\.seoul\.go\.kr/api/subway/[^/]+/json/realtimePosition/.*"
)
START = datetime(2026, 11, 15, 15, 5, 0, tzinfo=KST)
DAY = date(2026, 11, 15)
KEYS = {"position": "POSKEY111", "spare1": "SPAREKEY222", "spare2": "SPAREKEY333"}


def _quota() -> httpx.Response:
    _, body = load_fixture("seoul_realtime_nodata")
    return httpx.Response(200, json={**body, "code": "ERROR-337"})


def _key_of(request: httpx.Request) -> str:
    return request.url.path.split("/")[3]


@pytest.fixture(scope="module")
def index() -> StationIndex:
    return StationIndex.load("data/stations.sqlite")


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock(START.timestamp())


@pytest.fixture
def store(tmp_path):
    s = Store(tmp_path / "c.sqlite")
    yield s
    s.close()


@pytest.fixture
def http():
    with httpx.Client() as client:
        yield client


def _collector(http, store, index, clock, keys=None, **kw) -> Collector:
    return Collector(http, store, KeyRing(dict(keys or KEYS)), index, clock=clock, **kw)


def _count(store: Store, table: str) -> int:
    store.commit()
    con = sqlite3.connect(store.path)
    try:
        return con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    finally:
        con.close()


def _usage(store: Store) -> dict[str, tuple]:
    store.commit()
    con = sqlite3.connect(store.path)
    try:
        rows = con.execute("SELECT key_name, calls, errors, quota_hit_at FROM key_usage").fetchall()
    finally:
        con.close()
    return {name: (calls, errors, hit) for name, calls, errors, hit in rows}


def test_lines_are_the_nineteen_seoul_lines():
    assert len(LINES) == 19 and len(set(LINES)) == 19
    assert LINES[:9] == tuple(f"{n}호선" for n in range(1, 10))
    assert {"경의중앙선", "공항철도", "신분당선", "GTX-A"} <= set(LINES)
    assert PERIOD_SEC == 30


@respx.mock
def test_ok_response_stores_snapshot_events_stations_and_usage(http, store, index, clock):
    route = respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5"))
    assert _collector(http, store, index, clock).poll_line("5호선") == "INFO-000"
    assert _key_of(route.calls[0].request) == "POSKEY111"
    assert route.calls[0].request.url.path.endswith("/realtimePosition/0/200/5호선")
    assert _count(store, "events") == 35
    assert _count(store, "raw_snapshots") == 1
    assert _usage(store) == {"position": (1, 0, None)}
    known = store.known_stations("5호선")
    assert known["1005000510"] == index.resolve("방화", "5호선").id
    assert store.unmapped_stations() == []


@respx.mock
def test_same_response_twice_adds_no_new_events(http, store, index, clock):
    respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5"))
    collector = _collector(http, store, index, clock)
    collector.poll_line("5호선")
    clock.advance(30)
    collector.poll_line("5호선")
    assert _count(store, "events") == 35
    assert _count(store, "raw_snapshots") == 2


@respx.mock
def test_no_data_keeps_snapshot_only(http, store, index, clock):
    _, body = load_fixture("seoul_realtime_nodata")
    respx.get(POSITION_URL).mock(return_value=httpx.Response(200, json=body))
    assert _collector(http, store, index, clock).poll_line("6호선") == "INFO-200"
    assert (_count(store, "raw_snapshots"), _count(store, "events")) == (1, 0)
    assert _usage(store) == {"position": (1, 0, None)}


@respx.mock
def test_quota_error_blocks_key_and_retries_line_with_next_key(http, store, index, clock):
    def reply(request: httpx.Request) -> httpx.Response:
        if _key_of(request) == "POSKEY111":
            return _quota()
        return fixture_response("seoul_position_line5")

    route = respx.get(POSITION_URL).mock(side_effect=reply)
    collector = _collector(http, store, index, clock)
    assert collector.poll_line("5호선") == "INFO-000"
    assert [_key_of(c.request) for c in route.calls] == ["POSKEY111", "SPAREKEY222"]
    usage = _usage(store)
    assert usage["position"] == (1, 1, "2026-11-15T15:05:00+09:00")
    assert usage["spare1"] == (1, 0, None)
    collector.poll_line("5호선")
    assert _key_of(route.calls[-1].request) == "SPAREKEY222"


@respx.mock
def test_all_keys_exhausted_stops_calling(http, store, index, clock):
    route = respx.get(POSITION_URL).mock(return_value=_quota())
    collector = _collector(http, store, index, clock)
    assert collector.poll_line("5호선") == "NO_KEY"
    assert route.call_count == 3
    assert collector.poll_line("5호선") == "NO_KEY"
    assert route.call_count == 3
    assert sorted(store.blocked_keys(DAY)) == ["position", "spare1", "spare2"]
    assert _count(store, "events") == 0


@respx.mock
def test_blocked_keys_are_free_again_next_day(http, store, index, clock):
    route = respx.get(POSITION_URL).mock(return_value=_quota())
    collector = _collector(http, store, index, clock, keys={"position": "POSKEY111"})
    collector.poll_line("5호선")
    clock.set(datetime(2026, 11, 16, 5, 0, 0, tzinfo=KST).timestamp())
    route.mock(return_value=fixture_response("seoul_position_line5"))
    assert collector.poll_line("5호선") == "INFO-000"


@respx.mock
def test_restart_restores_blocked_keys_from_db(http, store, index, clock):
    store.mark_quota_hit(DAY, "position", START)
    route = respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5"))
    _collector(http, store, index, clock).poll_line("5호선")
    assert _key_of(route.calls[0].request) == "SPAREKEY222"


@respx.mock
def test_network_error_never_logs_the_key(http, store, index, clock, caplog):
    leak = "ConnectError http://swopenapi.seoul.go.kr/api/subway/POSKEY111/json/realtimePosition"
    respx.get(POSITION_URL).mock(side_effect=httpx.ConnectError(leak))
    with caplog.at_level(logging.DEBUG):
        assert _collector(http, store, index, clock).poll_line("5호선") == "NETWORK"
    assert caplog.records, "실패는 로그에 남아야 한다"
    text = caplog.text + "".join(str(r.__dict__) for r in caplog.records)
    assert "POSKEY111" not in text
    assert "***" in text
    assert _usage(store) == {"position": (1, 1, None)}
    assert _count(store, "raw_snapshots") == 0


@respx.mock
def test_timeout_is_a_network_error(http, store, index, clock):
    respx.get(POSITION_URL).mock(side_effect=httpx.ReadTimeout("slow"))
    assert _collector(http, store, index, clock).poll_line("5호선") == "NETWORK"


@respx.mock
def test_html_body_and_server_error_are_counted_not_raised(http, store, index, clock):
    route = respx.get(POSITION_URL).mock(return_value=httpx.Response(200, text="<html>점검</html>"))
    collector = _collector(http, store, index, clock)
    assert collector.poll_line("5호선") == "BAD_BODY"
    route.mock(return_value=httpx.Response(503, text="x"))
    assert collector.poll_line("5호선") == "HTTP_503"
    route.mock(return_value=httpx.Response(200, json=["목록"]))
    assert collector.poll_line("5호선") == "BAD_BODY"
    assert _usage(store) == {"position": (3, 3, None)}
    assert _count(store, "raw_snapshots") == 0


@respx.mock
def test_other_error_code_is_kept_as_snapshot_and_counted(http, store, index, clock):
    _, body = load_fixture("seoul_realtime_sample_error")
    respx.get(POSITION_URL).mock(return_value=httpx.Response(200, json=body))
    assert _collector(http, store, index, clock).poll_line("5호선") == "ERROR-336"
    assert _count(store, "raw_snapshots") == 1
    assert _usage(store) == {"position": (1, 1, None)}


@respx.mock
def test_unknown_station_name_is_mapped_to_null(http, store, index, clock):
    def mutate(body: dict) -> None:
        body["realtimePositionList"][0].update(statnId="1005999999", statnNm="세상에없는역")

    respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5", mutate))
    _collector(http, store, index, clock).poll_line("5호선")
    assert store.unmapped_stations() == [("5호선", "1005999999", "세상에없는역")]
    assert _count(store, "events") == 35


@respx.mock
def test_database_file_never_contains_a_key(http, store, index, clock, tmp_path):
    respx.get(POSITION_URL).mock(side_effect=[_quota(), fixture_response("seoul_position_line5")])
    _collector(http, store, index, clock).poll_line("5호선")
    store.close()
    blob = b"".join(p.read_bytes() for p in tmp_path.iterdir())
    for key in KEYS.values():
        assert key.encode() not in blob


def test_service_hours_are_five_to_one():
    def at(h: int, m: int) -> bool:
        return in_service_hours(datetime(2026, 11, 15, h, m, tzinfo=KST))

    assert (at(4, 59), at(5, 0), at(23, 59), at(0, 59), at(1, 0), at(3, 0)) == (
        False, True, True, True, False, False,
    )  # fmt: skip


class _Sleeper:
    def __init__(self, clock: FakeClock):
        self.clock = clock
        self.naps: list[float] = []

    def __call__(self, sec: float) -> None:
        self.naps.append(sec)
        self.clock.advance(sec)


@respx.mock
def test_run_spreads_lines_over_the_period(http, store, index, clock):
    times: list[float] = []

    def reply(request: httpx.Request) -> httpx.Response:
        times.append(clock() - START.timestamp())
        return fixture_response("seoul_realtime_nodata")

    respx.get(POSITION_URL).mock(side_effect=reply)
    lines = ("5호선", "2호선", "9호선")
    sleeper = _Sleeper(clock)
    _collector(http, store, index, clock, lines=lines).run(sleep=sleeper, max_cycles=2)
    assert times == [0, 10, 20, 30, 40, 50]
    assert clock() - START.timestamp() == 60
    assert all(nap > 0 for nap in sleeper.naps)


@respx.mock
def test_run_waits_outside_service_hours(http, store, index, clock):
    route = respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_realtime_nodata"))
    clock.set(datetime(2026, 11, 16, 4, 59, 0, tzinfo=KST).timestamp())
    sleeper = _Sleeper(clock)
    _collector(http, store, index, clock, lines=("5호선",)).run(sleep=sleeper, max_cycles=1)
    assert route.call_count == 1
    first_call_at = datetime.fromtimestamp(clock() - PERIOD_SEC, KST)
    assert (first_call_at.hour, first_call_at.minute) == (5, 0)


@respx.mock
def test_run_stops_calling_when_every_key_is_blocked(http, store, index, clock):
    route = respx.get(POSITION_URL).mock(return_value=_quota())
    sleeper = _Sleeper(clock)
    stop_after = iter([False] * 50 + [True] * 1000)
    _collector(http, store, index, clock, lines=("5호선", "2호선")).run(
        sleep=sleeper, should_stop=lambda: next(stop_after)
    )
    assert route.call_count == 3


@respx.mock
def test_main_once_reads_env_file_and_uses_only_collector_keys(tmp_path, monkeypatch):
    for name in ("SEOUL_POSITION_API_KEY", "SEOUL_SPARE_KEY_1", "SEOUL_SPARE_KEY_2"):
        monkeypatch.delenv(name, raising=False)
    env = tmp_path / ".env"
    env.write_text("SEOUL_REALTIME_API_KEY=REALTIMEKEY\nSEOUL_SPARE_KEY_1=SPAREKEY222\n", "utf-8")
    route = respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5"))
    db = tmp_path / "out" / "c.sqlite"
    code = main(
        ["--db", str(db), "--env-file", str(env), "--lines", "5호선,2호선", "--once",
         "--period", "0", "--log-file", ""]
    )  # fmt: skip
    assert code == 0
    assert [_key_of(c.request) for c in route.calls] == ["SPAREKEY222", "SPAREKEY222"]
    con = sqlite3.connect(db)
    assert con.execute("SELECT COUNT(*) FROM raw_snapshots").fetchone()[0] == 2
    con.close()


def test_main_without_keys_fails_clearly(tmp_path, monkeypatch, capsys):
    for name in ("SEOUL_POSITION_API_KEY", "SEOUL_SPARE_KEY_1", "SEOUL_SPARE_KEY_2"):
        monkeypatch.delenv(name, raising=False)
    env = tmp_path / ".env"
    env.write_text("SEOUL_REALTIME_API_KEY=REALTIMEKEY\n", "utf-8")
    code = main(["--db", str(tmp_path / "c.sqlite"), "--env-file", str(env), "--log-file", ""])
    assert code == 2
    assert "REALTIMEKEY" not in capsys.readouterr().err


@respx.mock
def test_station_on_a_line_missing_from_our_table_still_maps_by_name(http, store, index, clock):
    # 역 DB에는 창동이 4호선으로만 있다. 이름으로 찾은 역을 그대로 잇는다(설계 §2.4).
    def mutate(body: dict) -> None:
        body["realtimePositionList"][0].update(statnId="1001000116", statnNm="창동")

    respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5", mutate))
    _collector(http, store, index, clock).poll_line("1호선")
    assert store.known_stations("1호선")["1001000116"] == index.resolve("창동", "1호선").id


def test_restart_reconnects_stations_that_resolve_now(http, store, index, clock):
    # 별칭을 더한 뒤 다시 띄우면, 전에 잇지 못한 역과 그 이벤트가 이어진다.
    from tests.unit.test_ml_store import _event

    store.map_station("5호선", "1005000510", "방화", None, DAY)
    store.map_station("5호선", "1005999999", "세상에없는역", None, DAY)
    store.upsert_events([_event()], {"1005000510": None}, START)
    _collector(http, store, index, clock)
    banghwa = index.resolve("방화", "5호선").id
    assert store.known_stations("5호선") == {"1005000510": banghwa, "1005999999": None}
    store.commit()
    con = sqlite3.connect(store.path)
    assert con.execute("SELECT station_id FROM events").fetchall() == [(banghwa,)]
    con.close()


@respx.mock
def test_key_echoed_in_a_response_is_masked_before_storing(http, store, index, clock):
    # 응답 본문은 압축해 저장하므로 파일 바이트 검사로는 안 보인다. 풀어서 확인한다.
    import zlib

    def mutate(body: dict) -> None:
        body["errorMessage"]["link"] = "http://swopenapi.seoul.go.kr/api/subway/POSKEY111/json"

    respx.get(POSITION_URL).mock(return_value=fixture_response("seoul_position_line5", mutate))
    assert _collector(http, store, index, clock).poll_line("5호선") == "INFO-000"
    store.commit()
    con = sqlite3.connect(store.path)
    (blob,) = con.execute("SELECT body FROM raw_snapshots").fetchone()
    con.close()
    text = zlib.decompress(blob).decode("utf-8")
    assert "POSKEY111" not in text and "/api/subway/***/json" in text
