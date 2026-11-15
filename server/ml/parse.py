"""위치 API 응답 해석(설계 §2.1·§2.4). 외부 호출 없는 순수 함수만 둔다."""

from dataclasses import dataclass
from datetime import date, datetime, timedelta

from app.core.clock import KST
from app.services.timetable import week_tag

CODE_OK = "INFO-000"
CODE_NO_DATA = "INFO-200"
CODE_QUOTA_EXCEEDED = "ERROR-337"

# 01시 전 이벤트는 전날 운행으로 친다(막차가 자정을 넘는다).
SERVICE_DAY_START_HOUR = 1
# 이상값 기준: 받은 시각보다 이만큼 넘게 미래이거나 오래되면 표시만 한다(지우지 않는다).
FUTURE_SEC = 30
STALE_SEC = 600

_STATUSES = frozenset({0, 1, 2, 3})  # 진입, 도착, 출발, 전역출발
_DIRECTIONS = frozenset({0, 1})  # 0 상행·내선, 1 하행·외선


@dataclass(frozen=True)
class Event:
    service_date: date
    day_type: int  # 1 평일, 2 토요일, 3 휴일(시간표 API의 WEEK_TAG와 같다)
    line: str
    direction: int
    train_no: str
    statn_id: str
    statn_nm: str
    status: int
    event_at: datetime
    dest_statn_id: str | None
    dest_name: str | None
    express: int  # directAt 그대로(0 일반, 1 급행, 7 특급)
    last_train: int
    flag: str | None


def result_code(body: object) -> str | None:
    """정상 응답은 errorMessage.code에, 데이터 없음·오류는 본문 바로 아래 code에 온다."""
    if not isinstance(body, dict):
        return None
    holder = body.get("errorMessage", body)
    code = holder.get("code") if isinstance(holder, dict) else None
    return code if isinstance(code, str) else None


def service_date(at: datetime) -> date:
    local = at.astimezone(KST)
    if local.hour < SERVICE_DAY_START_HOUR:
        return local.date() - timedelta(days=1)
    return local.date()


def _flag(event_at: datetime, received_at: datetime) -> str | None:
    ahead = (event_at - received_at).total_seconds()
    if ahead > FUTURE_SEC:
        return "future"
    if -ahead > STALE_SEC:
        return "stale"
    return None


def _int(value: object) -> int | None:
    try:
        return int(str(value))
    except ValueError:
        return None


def _parse_row(row: object, line: str, received_at: datetime) -> Event | None:
    if not isinstance(row, dict):
        return None
    train_no, statn_id = row.get("trainNo"), row.get("statnId")
    if not (isinstance(train_no, str) and train_no and isinstance(statn_id, str) and statn_id):
        return None
    status, direction = _int(row.get("trainSttus")), _int(row.get("updnLine"))
    if status not in _STATUSES or direction not in _DIRECTIONS:
        return None
    try:
        event_at = datetime.strptime(row.get("recptnDt") or "", "%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError):
        return None
    event_at = event_at.replace(tzinfo=KST)
    day = service_date(event_at)
    return Event(
        service_date=day,
        day_type=week_tag(day),
        line=line,
        direction=direction,
        train_no=train_no,
        statn_id=statn_id,
        statn_nm=str(row.get("statnNm") or ""),
        status=status,
        event_at=event_at,
        dest_statn_id=row.get("statnTid") or None,
        dest_name=row.get("statnTnm") or None,
        express=_int(row.get("directAt")) or 0,
        last_train=_int(row.get("lstcarAt")) or 0,
        flag=_flag(event_at, received_at),
    )


def parse_rows(body: dict, line: str, received_at: datetime) -> tuple[list[Event], int]:
    """(이벤트 목록, 건너뛴 행 수). 필수 값이 없거나 형식이 틀린 행만 건너뛴다."""
    rows = body.get("realtimePositionList")
    if not isinstance(rows, list):
        return [], 0
    events = [e for row in rows if (e := _parse_row(row, line, received_at)) is not None]
    return events, len(rows) - len(events)
