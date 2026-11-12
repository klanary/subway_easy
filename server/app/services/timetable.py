"""서울시 시간표 해석(재설계 스펙 5.2). 외부 호출 없는 순수 함수만 둔다."""

from datetime import date, datetime, time, timedelta

import holidays

from app.core.clock import KST
from app.models.schemas import TimetableTrain

WEEKDAY, SATURDAY, HOLIDAY = 1, 2, 3
# 자정 넘어 다니는 열차("24:30:00")는 전날 시간표에 있다. 이 시각 전에는 전날 시간표도 본다.
SERVICE_DAY_START_HOUR = 5
EXPRESS_FLAGS = frozenset({"D"})  # 시간표 EXPRESS_YN: G 일반, D 급행(2026-11-12 9호선 확인)

_KR_HOLIDAYS = holidays.country_holidays("KR")


def week_tag(day: date) -> int:
    """공휴일(대체공휴일·선거일 포함)·일요일 3, 토요일 2, 그 밖 1."""
    if day in _KR_HOLIDAYS or day.weekday() == 6:
        return HOLIDAY
    return SATURDAY if day.weekday() == 5 else WEEKDAY


def service_days(at: datetime) -> list[tuple[date, int]]:
    """`at` 이후 열차를 찾을 때 볼 시간표 날짜와 요일 코드. 새벽이면 전날도 본다."""
    local = at.astimezone(KST)
    today = local.date()
    days = [(today, week_tag(today))]
    if local.hour < SERVICE_DAY_START_HOUR:
        yesterday = today - timedelta(days=1)
        days.insert(0, (yesterday, week_tag(yesterday)))
    return days


def parse_hms(value: str, service_day: date) -> datetime | None:
    """ "HH:MM:SS"를 그 운행일의 한국 시각으로. 24시를 넘으면 다음 날이다. 형식이 틀리면 None."""
    try:
        h, m, s = (int(x) for x in value.split(":"))
    except (AttributeError, ValueError):
        return None
    if not (0 <= h < 48 and 0 <= m < 60 and 0 <= s < 60):
        return None
    start = datetime.combine(service_day, time(0, 0), tzinfo=KST)
    return start + timedelta(hours=h, minutes=m, seconds=s)


def departures(
    rows: list[dict], station_cd: str, service_day: date, after: datetime, count: int
) -> list[TimetableTrain]:
    """`after` 이후 그 역을 떠나는 열차 `count`대. 그 역에서 끝나는 열차는 뺀다."""
    trains: list[TimetableTrain] = []
    for row in rows:
        left = row.get("LEFTTIME") or ""
        if row.get("DESTSTATION") == station_cd or left == "00:00:00":
            continue
        departs_at = parse_hms(left, service_day)
        if departs_at is None or departs_at < after:
            continue
        trains.append(
            TimetableTrain(
                departs_at=departs_at,
                destination=row.get("SUBWAYENAME") or None,
                is_express=row.get("EXPRESS_YN") in EXPRESS_FLAGS,
                train_no=row.get("TRAIN_NO") or None,
            )
        )
    trains.sort(key=lambda t: t.departs_at)
    return trains[:count]
