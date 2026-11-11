from collections.abc import Callable
from datetime import datetime, timedelta, timezone

# 한국은 서머타임이 없어 고정 오프셋으로 정확하다(tzdata 불필요).
KST = timezone(timedelta(hours=9))


def kst_from_epoch(ts: float) -> datetime:
    """유닉스 시각을 한국 시간으로 바꾼다. 응답은 초 단위까지만 쓰므로 마이크로초는 버린다."""
    return datetime.fromtimestamp(ts, KST).replace(microsecond=0)


def now_kst(clock: Callable[[], float]) -> datetime:
    return kst_from_epoch(clock())
