"""서울시 실시간 도착 응답의 열차 항목 해석 (스펙 6.7).

필드 이름은 스펙 6.7의 이름이다. P1 §1에서 실제 이름이 다르게 확인되면 이 파일을 고친다.
"""

import re
from datetime import datetime

from app.core.clock import KST

# 해석 순서가 중요하다: 전역 패턴을 먼저 봐야 "전역 진입"이 "진입"(30초)으로 잡히지 않는다.
_NTH_PREVIOUS = re.compile(r"\[(\d+)\]번째 전역")
_PREVIOUS_STATION = (("전역 진입", 150), ("전역 도착", 120), ("전역 출발", 90))
_MIN_SEC = re.compile(r"(\d+)분\s*(\d+)초\s*후")
_MIN = re.compile(r"(\d+)분\s*후")
_TRAIN_LINE_NM = re.compile(r"(.+?)행\s*-\s*(.+?)방면")

_SEC_PER_PREVIOUS_STATION = 120


def _parse_message(message: str | None) -> int | None:
    """안내 문구(arvlMsg2)로 남은 초를 추정한다. 해석할 수 없으면 None."""
    if not message:
        return None
    if m := _NTH_PREVIOUS.search(message):
        return int(m.group(1)) * _SEC_PER_PREVIOUS_STATION
    for pattern, sec in _PREVIOUS_STATION:
        if pattern in message:
            return sec
    if m := _MIN_SEC.search(message):
        return int(m.group(1)) * 60 + int(m.group(2))
    if m := _MIN.search(message):
        return int(m.group(1)) * 60
    if "곧 도착" in message or "진입" in message:
        return 30
    if "도착" in message:
        return 0
    return None


def _to_int(value: object) -> int | None:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _parse_recptn(value: object) -> datetime | None:
    """생성 시각 "YYYY-MM-DD HH:MM:SS"(뒤에 ".N"이 붙을 수 있음)을 한국 시간으로 읽는다."""
    try:
        return datetime.strptime(str(value)[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
    except ValueError:
        return None


def compute_remaining_sec(item: dict, now: datetime) -> int | None:
    """서버 응답 시점(now) 기준 남은 초.

    도착 예정 초(barvlDt)가 있으면 그 값에서 생성 시각 이후 지난 시간을 빼고,
    없으면 안내 문구(arvlMsg2)로 추정한다. 둘 다 안 되면 None, 음수는 0이다.
    """
    barvl = _to_int(item.get("barvlDt"))
    if barvl is not None and barvl > 0:
        generated = _parse_recptn(item.get("recptnDt"))
        elapsed = (now - generated).total_seconds() if generated is not None else 0
        sec: int | None = round(barvl - elapsed)
    else:
        sec = _parse_message(item.get("arvlMsg2"))
    if sec is None:
        return None
    return max(sec, 0)


def is_departed(message: str | None) -> bool:
    """"OO 출발"은 이미 이 역을 떠난 열차다. "전역 출발"은 아직 오는 중이다(규칙 5)."""
    return bool(message) and "출발" in message and "전역" not in message


def parse_train_line_nm(value: str | None) -> tuple[str | None, str | None]:
    """"OO행 - XX방면" → (OO, XX). 해석할 수 없으면 (None, None)."""
    if not value or not (m := _TRAIN_LINE_NM.search(value)):
        return None, None
    return m.group(1).strip(), m.group(2).strip()


def is_express(item: dict) -> bool:
    status = (item.get("btrainSttus") or "").strip()
    return bool(status) and status != "일반"


def is_last(item: dict) -> bool:
    return str(item.get("lstcarAt") or "").strip() == "1"
