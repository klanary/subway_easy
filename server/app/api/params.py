from datetime import datetime

from app.core.clock import KST
from app.core.errors import InvalidRequest


def parse_time(value: str | None) -> datetime | None:
    """ISO 시각. 생략하면 None, 시간대가 없으면 한국 시간으로 본다. 형식이 틀리면 400(PARAMS)."""
    if value is None:
        return None
    try:
        at = datetime.fromisoformat(value)
    except ValueError as e:
        raise InvalidRequest("PARAMS") from e
    return at if at.tzinfo else at.replace(tzinfo=KST)
