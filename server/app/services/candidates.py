"""구간별 후보 열차(열차 고르기 설계 2026-11-18 §5.1).

앱은 구간마다 [이전]/[다음]으로 열차를 바꾸고 뒤 구간을 직접 다시 맞춘다. 누를 때마다 서버를 부르지
않도록, 경로를 줄 때 구간마다 탈 수 있는 열차를 넉넉히 보낸다: 같은 노선·방향에서 탈 역과 내릴 역에
모두 서는 운행 가운데 고른 열차 30분 전부터 2시간 뒤까지 떠나는 것, 최대 40대.
"""

from datetime import datetime, time, timedelta

from app.core.clock import KST
from app.services.raptor import Departure, Ride
from app.services.timetable import service_days, week_tag
from app.services.trips import TripsData

BEFORE_SEC = 1800  # 고른 열차 30분 전부터
AFTER_SEC = 7200  # 2시간 뒤까지
MAX_CANDIDATES = 40
EARLIER_KEEP = 10  # 넘치면 고른 열차 앞은 가까운 10대까지


def candidates(
    trips: TripsData,
    ride: Ride,
    *,
    before_sec: int = BEFORE_SEC,
    after_sec: int = AFTER_SEC,
    limit: int = MAX_CANDIDATES,
) -> list[Departure]:
    """`ride`의 탈 역에서 내릴 역까지 갈 수 있는 열차들(출발 순). 고른 열차는 늘 들어간다."""
    lo = ride.departs_at - timedelta(seconds=before_sec)
    hi = ride.departs_at + timedelta(seconds=after_sec)
    days = sorted({d for at in (lo, hi) for d, _ in service_days(at)})
    found: dict[tuple[str | None, datetime], Departure] = {}
    for service_day in days:
        day = trips.day(week_tag(service_day))
        board, alight = day.stop_index(ride.board_station), day.stop_index(ride.alight_station)
        if board is None or alight is None:
            continue
        start = datetime.combine(service_day, time(0), tzinfo=KST)
        for p, i in day.patterns_at(board):
            pattern = day.patterns[p]
            if pattern.line != ride.line or pattern.direction != ride.direction:
                continue
            later = range(i + 1, len(pattern.stops))
            j = next((k for k in later if pattern.stops[k] == alight), None)
            if j is None:
                continue  # 내릴 역에 서지 않는 패턴(급행 등)
            for t in range(pattern.n_trips):
                departs = start + timedelta(seconds=pattern.dep(t, i))
                if not lo <= departs <= hi:
                    continue
                meta = pattern.trip_meta(t)
                found[(meta.train_no, departs)] = Departure(
                    departs_at=departs,
                    train_no=meta.train_no,
                    dest=meta.dest,
                    express=meta.express,
                    arrives_at=start + timedelta(seconds=pattern.arr(t, j)),
                    source=meta.source,
                    estimated=meta.estimated,
                )
    chosen = Departure(
        departs_at=ride.departs_at,
        train_no=ride.train_no,
        dest=ride.dest,
        express=ride.express,
        arrives_at=ride.arrives_at,
        source=ride.source,
        estimated=ride.estimated,
    )
    # 이어 타기로 합친 구간(2호선 성수)은 탈 역·내릴 역이 다른 운행이라 못 찾는다 → 고른 열차만.
    found.setdefault((ride.train_no, ride.departs_at), chosen)
    ordered = sorted(found.values(), key=lambda d: d.departs_at)
    if len(ordered) <= limit:
        return ordered
    at = next(k for k, d in enumerate(ordered) if d.departs_at >= ride.departs_at)
    first = max(0, at - EARLIER_KEEP)
    return ordered[first : first + limit]
