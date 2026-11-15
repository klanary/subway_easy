"""ML 파이프라인 테스트용 합성 데이터. `from tests.ml_helpers import ...`로 쓴다."""

from datetime import date, datetime, time, timedelta
from pathlib import Path

from app.core.clock import KST
from app.services.timetable import week_tag
from ml.dataset import Obs
from ml.parse import Event
from ml.store import Store

MON = date(2026, 11, 2)  # 월요일(평일)
STATIONS = ("A", "B", "C", "D")


def weekdays(n: int, start: date = MON) -> list[date]:
    """start부터 평일 n일(토·일은 건너뜀)."""
    days: list[date] = []
    day = start
    while len(days) < n:
        if week_tag(day) == 1:
            days.append(day)
        day += timedelta(days=1)
    return days


def trip(
    day: date,
    start_secs: int,
    *,
    train_no: str = "100",
    line: str = "9호선",
    direction: int = 0,
    stations: tuple[str, ...] = STATIONS,
    hop: int | tuple[int, ...] = 120,
    dwell: int = 30,
    statuses: tuple[int, ...] = (1, 2),
    express: int = 0,
    dest_statn_id: str | None = None,
) -> list[Obs]:
    """한 열차가 stations를 차례로 지나는 이벤트. 역마다 도착(1) 뒤 dwell초 있다가 출발(2)."""
    hops = hop if isinstance(hop, tuple) else (hop,) * (len(stations) - 1)
    out: list[Obs] = []
    arr = start_secs
    for i, station in enumerate(stations):
        times = {0: arr - 15, 1: arr, 2: arr + dwell, 3: arr - 60}
        for status in statuses:
            out.append(
                Obs(
                    service_date=day,
                    day_type=week_tag(day),
                    line=line,
                    direction=direction,
                    train_no=train_no,
                    statn_id=station,
                    station_id=f"S-{station}",
                    status=status,
                    secs=times[status],
                    dest_statn_id=dest_statn_id or stations[-1],
                    dest_name=f"{stations[-1]}행",
                    express=express,
                    last_train=0,
                    flag=None,
                )
            )
        if i < len(hops):
            arr += hops[i]
    return out


def write_db(path: Path, events: list[Obs]) -> Path:
    """합성 이벤트를 수집기와 같은 모양의 DB로 쓴다."""
    store = Store(path)
    for obs in events:
        at = datetime.combine(obs.service_date, time(0, 0), tzinfo=KST) + timedelta(
            seconds=obs.secs
        )
        event = Event(
            service_date=obs.service_date,
            day_type=obs.day_type,
            line=obs.line,
            direction=obs.direction,
            train_no=obs.train_no,
            statn_id=obs.statn_id,
            statn_nm=f"{obs.statn_id}역",
            status=obs.status,
            event_at=at,
            dest_statn_id=obs.dest_statn_id,
            dest_name=obs.dest_name,
            express=obs.express,
            last_train=obs.last_train,
            flag=obs.flag,
        )
        store.map_station(
            obs.line, obs.statn_id, f"{obs.statn_id}역", obs.station_id, obs.service_date
        )
        store.upsert_events([event], {obs.statn_id: obs.station_id}, at + timedelta(seconds=20))
    store.close()
    return path
