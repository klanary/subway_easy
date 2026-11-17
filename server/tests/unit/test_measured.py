"""실측 결과물 조회(설계 2026-11-17 §4.3). 픽스처는 실제 형식을 줄인 것이다."""

import json
from datetime import date, datetime

import pytest

from app.core.clock import KST
from app.services.measured import MeasuredData
from tests.helpers import FIXTURES_DIR


def at(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=KST)


def load(name: str) -> dict:
    return json.loads((FIXTURES_DIR / "measured" / name).read_text(encoding="utf-8"))


@pytest.fixture
def data() -> MeasuredData:
    return MeasuredData.from_artifacts(load("b1_small.json"), load("b2_small.json"))


def hm(trains) -> list[str]:
    return [t.departs_at.strftime("%H:%M") for t in trains]


def test_departures_come_from_cells_in_time_order(data):
    got = data.departures("강남", "신분당선", "상행", at("2026-11-18T07:00"), 2)
    assert hm(got) == ["07:05", "07:11"]
    assert all(t.source == "measured" for t in got)


def test_secs_past_midnight_belong_to_next_calendar_day(data):
    got = data.departures("강남", "신분당선", "상행", at("2026-11-18T23:50"), 1)
    assert got[0].departs_at.isoformat().startswith("2026-11-19T00:20")


def test_unmapped_station_is_skipped(data):
    # statn_id SBX는 우리 역 id가 없다. 어느 역으로도 나오지 않는다(07:06 칸).
    for station in ("강남", "양재"):
        got = data.departures(station, "신분당선", "상행", at("2026-11-18T07:00"), 5)
        assert "07:06" not in hm(got)


def test_suspect_scope_is_excluded(data):
    # 평일 신분당선 하행 7시대는 변경 의심 중이라 07:10 칸을 내지 않는다.
    got = data.departures("강남", "신분당선", "하행", at("2026-11-18T07:00"), 5)
    assert hm(got)[0] == "09:00"


def test_trains_ending_here_are_dropped(data):
    got = data.departures("양재", "신분당선", "상행", at("2026-11-18T07:00"), 5)
    assert "07:13" not in hm(got) and "07:08" in hm(got)


def test_direction_codes_map_to_names(data):
    got = data.departures("공덕", "경의중앙선", "하행", at("2026-11-18T07:00"), 1)
    assert hm(got) == ["08:00"] and got[0].train_no == "K123"
    assert data.departures("공덕", "경의중앙선", "상행", at("2026-11-18T07:00"), 1) == []


def test_weekend_uses_day_type_2_and_3(data):
    saturday = data.departures("강남", "신분당선", "상행", at("2026-11-21T08:00"), 1)
    sunday = data.departures("강남", "신분당선", "상행", at("2026-11-22T08:00"), 1)
    assert hm(saturday) == ["09:00"] and hm(sunday) == ["10:00"]


def test_arrival_at_matches_train_no_across_stations(data):
    t = data.arrival_at("경의중앙선", "하행", "K123", "서강대", date(2026, 11, 18))
    assert t is not None and t.strftime("%H:%M") == "08:06"


def test_arrival_at_is_none_for_slots_line(data):
    assert data.arrival_at("신분당선", "상행", "D1", "양재", date(2026, 11, 18)) is None


def test_arrival_at_is_none_when_train_not_seen_there(data):
    assert data.arrival_at("경의중앙선", "하행", "K999", "서강대", date(2026, 11, 18)) is None


def test_hop_seconds_prefers_hour_then_day_then_all(data):
    args = ("경의중앙선", "하행", "공덕", "서강대")
    assert data.hop_seconds(*args, express=False, at=at("2026-11-18T08:30")) == 340
    assert data.hop_seconds(*args, express=False, at=at("2026-11-18T10:00")) == 350
    assert data.hop_seconds(*args, express=False, at=at("2026-11-21T10:00")) == 360
    assert data.hop_seconds(*args, express=True, at=at("2026-11-18T08:30")) is None


def test_as_of_is_read(data):
    assert data.as_of == date(2026, 11, 17)
