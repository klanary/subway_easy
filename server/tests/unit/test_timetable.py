"""서울시 시간표 해석(재설계 스펙 5.2).

영등포구청 5호선 평일 상행 샘플은 2026-11-12에 받은 실제 응답이다.
"""

from datetime import date, datetime

import pytest

from app.core.clock import KST
from app.services.timetable import departures, parse_hms, service_days, week_tag
from tests.helpers import load_fixture

AFTER_8 = datetime(2026, 11, 13, 8, 0, tzinfo=KST)


@pytest.fixture
def real_rows() -> list[dict]:
    _, body = load_fixture("seoul_timetable_yeongdeungpo_weekday_up.json")
    return body["SearchSTNTimeTableByIDService"]["row"]


def test_week_tag():
    assert [week_tag(date(2026, 11, d)) for d in (13, 14, 15)] == [1, 2, 3]  # 금·토·일
    assert week_tag(date(2026, 12, 25)) == 3  # 성탄절(금)
    assert week_tag(date(2026, 3, 2)) == 3  # 삼일절 대체공휴일(월)


def test_parse_past_midnight():
    assert parse_hms("25:00:00", date(2026, 11, 13)) == datetime(2026, 11, 14, 1, 0, tzinfo=KST)
    assert parse_hms("05:35:30", date(2026, 11, 13)) == datetime(
        2026, 11, 13, 5, 35, 30, tzinfo=KST
    )
    assert parse_hms("bad", date(2026, 11, 13)) is None


def test_after_midnight_uses_previous_service_day():
    days = service_days(datetime(2026, 11, 14, 0, 30, tzinfo=KST))  # 토요일 새벽
    assert days == [(date(2026, 11, 13), 1), (date(2026, 11, 14), 2)]


def test_daytime_uses_today_only():
    assert service_days(AFTER_8) == [(date(2026, 11, 13), 1)]


def test_departures_sorted_after_time(real_rows):
    trains = departures(real_rows, "2524", date(2026, 11, 13), AFTER_8, 4)
    assert len(trains) == 4
    assert [t.departs_at for t in trains] == sorted(t.departs_at for t in trains)
    assert all(t.departs_at >= AFTER_8 for t in trains)
    assert trains[0].destination == "방화" and trains[0].train_no


def test_departures_after_midnight_from_previous_day(real_rows):
    after = datetime(2026, 11, 14, 0, 10, tzinfo=KST)
    trains = departures(real_rows, "2524", date(2026, 11, 13), after, 4)
    assert trains and trains[0].departs_at.day == 14 and trains[0].departs_at.hour == 0


def test_departures_skip_terminating(real_rows):
    rows = [{**real_rows[0], "DESTSTATION": "2524"}, {**real_rows[1], "LEFTTIME": "00:00:00"}]
    assert (
        departures(rows, "2524", date(2026, 11, 13), datetime(2026, 11, 13, 0, 0, tzinfo=KST), 4)
        == []
    )


def test_express_flag(real_rows):
    rows = [{**real_rows[0], "EXPRESS_YN": "D"}, {**real_rows[1], "EXPRESS_YN": "G"}]
    trains = departures(
        rows, "2524", date(2026, 11, 13), datetime(2026, 11, 13, 0, 0, tzinfo=KST), 4
    )
    assert [t.is_express for t in trains] == [True, False]
