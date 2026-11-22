"""실측 결과물 조회(설계 2026-11-17 §4.3). 픽스처는 실제 형식을 줄인 것이다."""

import json
from datetime import date, datetime

import pytest

from app.core.clock import KST
from app.services.measured import MeasuredData
from ml.publish import server_b1, server_b2
from tests.helpers import FIXTURES_DIR


def at(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=KST)


def load(name: str) -> dict:
    return json.loads((FIXTURES_DIR / "measured" / name).read_text(encoding="utf-8"))


@pytest.fixture
def data() -> MeasuredData:
    return MeasuredData.from_artifacts(
        server_b1(load("b1_small.json")), server_b2(load("b2_small.json"))
    )


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


def test_no_cells_for_todays_day_type_means_unknown(data):
    """공덕 경의중앙선은 평일 칸만 있다. 일요일 낮에 다음 날(월) 첫차를 다음 열차로 주지 않는다.

    2026-11-22(일) 실제: 주말 실측이 아직 없어 서울역 경의중앙선 다음 열차가
    "05:00 출발"(17시간 뒤)로 보였다.
    """
    assert data.departures("공덕", "경의중앙선", "하행", at("2026-11-22T12:00"), 2) == []


def _gtx_like(scopes: dict) -> MeasuredData:
    """평일 GTX-A 서울역 상행: 05시 두 대만 남고 06시부터는 변경 의심으로 빠진 칸.

    2026-11-22 실제 결과물의 모양이다.
    """
    return MeasuredData.from_artifacts(
        {
            "kind": "b1-server",
            "version": 1,
            "as_of": "2026-11-21",
            "built_at": "2026-11-22T03:30:04+09:00",
            "modes": {"GTX-A": "train_no"},
            "dests": ["운정중앙"],
            "cells": {
                "1|GTX-A|0|G1": [[18000, "G1", 0, 0], [19800, "G2", 0, 0], [43200, "G3", 0, 0]]
            },
            "stations": {"GTX-A|G1": {"station_id": "서울", "name": "서울역"}},
            "scopes": scopes,
        },
        None,
    )


def test_excluded_hours_ahead_mean_unknown_not_next_day():
    data = _gtx_like({f"1|GTX-A|0|{h}": "suspect" for h in range(6, 25)})
    assert data.departures("서울", "GTX-A", "상행", at("2026-11-18T11:00"), 2) == []


def test_after_last_train_next_day_first_trains_still_come():
    """빠진 시간대가 없으면 막차 뒤에는 다음 날 첫차를 준다(밤늦게)."""
    data = _gtx_like({})
    got = data.departures("서울", "GTX-A", "상행", at("2026-11-18T13:00"), 2)
    assert [t.departs_at.isoformat()[:16] for t in got] == ["2026-11-19T05:00", "2026-11-19T05:30"]
