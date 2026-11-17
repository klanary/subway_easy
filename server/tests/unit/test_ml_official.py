"""공식 시간표 다시 받기·차이·관측과의 직접 비교(시간표 변경 계획 Task 6)."""

import json
import re
from datetime import date, timedelta

import httpx
import pytest
import respx

from ml.b0 import B0, TimetableCache, diff_caches, main, refresh
from ml.changes import (
    OFFICIAL_CHANGED_MIN,
    ChangeState,
    load_official_changed,
    mark,
    official_fit,
    render,
)
from ml.points import PointStore
from tests.ml_helpers import STATIONS, cover, weekdays
from tests.unit.test_ml_changes_signals import day_points

TIMETABLE_URL = re.compile(r"http://openapi\.seoul\.go\.kr:8088/[^/]+/json/SearchSTNTimeTable.*")
DAYS = weekdays(8)
H6 = 6 * 3600
SCOPE = (1, "9호선", 0, 6)
TODAY = date(2026, 11, 23)


def _hms(secs: int) -> str:
    return f"{secs // 3600:02d}:{secs % 3600 // 60:02d}:{secs % 60:02d}"


def rows(code: str, *, n=10, gap=360, shift=0, station_index=0) -> list[dict]:
    """06:00부터 gap초 간격 n대가 그 역을 떠나는 시간표."""
    return [
        {
            "TRAIN_NO": str(9001 + i),
            "ARRIVETIME": _hms(H6 + gap * i + 120 * station_index + shift),
            "LEFTTIME": _hms(H6 + gap * i + 120 * station_index + 30 + shift),
            "DESTSTATION": "cd-D",
            "STATION_CD": code,
        }
        for i in range(n)
    ]


def cache_with(directory, **kw) -> TimetableCache:
    cache = TimetableCache(directory)
    for i, station in enumerate(STATIONS[:3]):
        cache.save(f"cd-{station}", 1, 1, rows(f"cd-{station}", station_index=i, **kw))
    return cache


TARGETS = [(f"cd-{s}", 1, 1) for s in STATIONS[:3]]
LINES = {f"cd-{s}": "9호선" for s in STATIONS}
CODES = {("9호선", s): f"cd-{s}" for s in STATIONS}


def test_same_caches_have_no_diff(tmp_path):
    old, new = cache_with(tmp_path / "a"), cache_with(tmp_path / "b")
    assert diff_caches(old, new, TARGETS, LINES) == {}


def test_changed_departure_times_show_up_per_scope(tmp_path):
    old = TimetableCache(tmp_path / "a")
    new = TimetableCache(tmp_path / "b")
    before = rows("cd-A")
    after = [dict(r) for r in before]
    for r in after[:4]:
        r["LEFTTIME"] = _hms(H6 + 3000 + 60 * after.index(r))  # 06:50대로 옮김(같은 시간대)
    old.save("cd-A", 1, 1, before)
    new.save("cd-A", 1, 1, after)
    assert diff_caches(old, new, [("cd-A", 1, 1)], LINES) == {SCOPE: pytest.approx(8 / 14)}


def test_target_missing_from_old_cache_is_skipped(tmp_path):
    old = TimetableCache(tmp_path / "a")
    new = cache_with(tmp_path / "b")
    assert diff_caches(old, new, TARGETS, LINES) == {}


def _reply_with(table: list[dict]) -> httpx.Response:
    body = {
        "SearchSTNTimeTableByIDService": {
            "list_total_count": len(table),
            "RESULT": {"CODE": "INFO-000", "MESSAGE": "정상 처리되었습니다"},
            "row": table,
        }
    }
    return httpx.Response(200, json=body)


@respx.mock
def test_refresh_swaps_directories_and_reports_the_diff(tmp_path):
    cache_dir = tmp_path / "timetable_cache"
    cache_with(cache_dir)

    def reply(request: httpx.Request) -> httpx.Response:
        code = request.url.path.split("/")[-4]
        index = [f"cd-{s}" for s in STATIONS].index(code)
        return _reply_with(rows(code, shift=180, station_index=index))

    respx.get(TIMETABLE_URL).mock(side_effect=reply)
    with httpx.Client() as http:
        changed, failed = refresh(
            http, "GENERALKEY", TARGETS, cache_dir, today=TODAY, lines_by_code=LINES,
            sleep=lambda s: None,
        )  # fmt: skip
    assert failed == 0 and changed[SCOPE] == 1.0
    names = sorted(p.name for p in tmp_path.iterdir())
    assert names == ["timetable_cache", "timetable_cache-20261123"]
    assert TimetableCache(cache_dir).rows("cd-A", 1, 1)[0]["LEFTTIME"] == _hms(H6 + 30 + 180)
    old = TimetableCache(tmp_path / "timetable_cache-20261123")
    assert old.rows("cd-A", 1, 1)[0]["LEFTTIME"] == _hms(H6 + 30)


@respx.mock
def test_refresh_keeps_only_recent_old_directories(tmp_path):
    cache_dir = tmp_path / "timetable_cache"
    cache_with(cache_dir)
    (tmp_path / "timetable_cache-20261101").mkdir()
    (tmp_path / "timetable_cache-20261108").mkdir()
    respx.get(TIMETABLE_URL).mock(side_effect=lambda request: _reply_with(rows("x")))
    with httpx.Client() as http:
        refresh(http, "K", TARGETS, cache_dir, today=TODAY, lines_by_code=LINES, keep=1,
                sleep=lambda s: None)  # fmt: skip
    assert sorted(p.name for p in tmp_path.iterdir()) == [
        "timetable_cache", "timetable_cache-20261123",
    ]  # fmt: skip


@respx.mock
def test_refresh_with_a_failure_changes_nothing(tmp_path):
    cache_dir = tmp_path / "timetable_cache"
    cache_with(cache_dir)
    replies = [_reply_with(rows("cd-A", shift=180)), httpx.Response(500, text="x"),
               _reply_with(rows("cd-C", shift=180))]  # fmt: skip
    respx.get(TIMETABLE_URL).mock(side_effect=replies)
    with httpx.Client() as http:
        changed, failed = refresh(
            http, "GENERALKEY", TARGETS, cache_dir, today=TODAY, lines_by_code=LINES,
            sleep=lambda s: None,
        )  # fmt: skip
    assert (changed, failed) == ({}, 1)
    assert [p.name for p in tmp_path.iterdir()] == ["timetable_cache"]
    assert TimetableCache(cache_dir).rows("cd-A", 1, 1)[0]["LEFTTIME"] == _hms(H6 + 30)


def test_load_official_changed_applies_minimum_and_age(tmp_path):
    path = tmp_path / "official_diff.json"
    assert load_official_changed(path, today=TODAY) == set()
    data = {"checked_on": TODAY.isoformat(), "changed": {"1|9호선|0|6": 0.10, "1|9호선|0|7": 0.05}}
    path.write_text(json.dumps(data), encoding="utf-8")
    assert OFFICIAL_CHANGED_MIN == 0.10
    assert load_official_changed(path, today=TODAY) == {SCOPE}
    assert load_official_changed(path, today=TODAY + timedelta(days=8)) == {SCOPE}
    assert load_official_changed(path, today=TODAY + timedelta(days=9)) == set()
    path.write_text("{", encoding="utf-8")
    assert load_official_changed(path, today=TODAY) == set()


def test_b0_departures_lists_trains_leaving_the_station(tmp_path):
    b0 = B0(cache_with(tmp_path / "tt"), CODES)
    table = b0.departures(1, "9호선", 0, "A")
    assert table[0] == ("9001", H6 + 30) and len(table) == 10
    assert b0.departures(1, "9호선", 1, "A") == []
    assert b0.departures(1, "9호선", 0, "모르는역") == []


@pytest.fixture
def store(tmp_path):
    s = PointStore(tmp_path / "points.sqlite")
    yield s
    s.close()


def _confirmed_state(decided: date) -> ChangeState:
    state = ChangeState()
    mark(state, [SCOPE], decided, today=decided, note="")
    return state


def test_official_fit_marks_stale_when_official_still_has_old_times(tmp_path, store):
    b0 = B0(cache_with(tmp_path / "tt"), CODES)  # 공식은 옛 시각
    state = _confirmed_state(DAYS[0])
    store.put(day_points(DAYS[4], shift=180))  # 관측은 3분 뒤로 바뀐 시각
    cover(store, [DAYS[4]])
    changed = official_fit(b0, store, state, DAYS[4])
    assert [r.official for r in changed] == ["stale"]
    assert render(changed)[0].endswith("공식 시간표가 아직 옛것")
    assert official_fit(b0, store, state, DAYS[4]) == []  # 달라진 것이 없으면 다시 알리지 않는다


def test_official_fit_marks_fits_when_official_matches(tmp_path, store):
    b0 = B0(cache_with(tmp_path / "tt", shift=180), CODES)
    state = _confirmed_state(DAYS[0])
    store.put(day_points(DAYS[4], shift=180))
    changed = official_fit(b0, store, state, DAYS[4])
    assert [r.official for r in changed] == ["fits"]


def test_official_fit_waits_three_days_and_skips_lines_without_timetable(tmp_path, store):
    b0 = B0(cache_with(tmp_path / "tt"), CODES)
    state = _confirmed_state(DAYS[3])
    store.put(day_points(DAYS[4], shift=180))
    assert official_fit(b0, store, state, DAYS[4]) == []  # 확정 하루 뒤
    other = ChangeState()
    mark(other, [(1, "경의중앙선", 0, 6)], DAYS[0], today=DAYS[0], note="")
    assert official_fit(b0, store, other, DAYS[4]) == []


def test_cli_refresh_needs_db_and_key(tmp_path, monkeypatch):
    monkeypatch.delenv("SEOUL_OPENAPI_KEY", raising=False)
    env = tmp_path / ".env"
    env.write_text("SEOUL_POSITION_API_KEY=POS\n", encoding="utf-8")
    args = ["--refresh", "--db", str(tmp_path / "없음.sqlite"), "--env-file", str(env)]
    assert main(args) == 2


def test_targets_cover_lines_1_to_9_and_all_three_day_types():
    from ml.b0 import TIMETABLE_LINES, targets_for

    codes = {("1호선", "s1"): "0150", ("9호선", "s9"): "4101", ("경의중앙선", "k"): "1219"}
    targets = targets_for(codes, set(TIMETABLE_LINES))
    assert "1호선" in TIMETABLE_LINES and len(TIMETABLE_LINES) == 9
    assert targets == sorted(
        (code, day_type, inout)
        for code in ("0150", "4101")
        for day_type in (1, 2, 3)
        for inout in (1, 2)
    )
