"""수집기가 쓸 수 있는 키(설계 §2.2). 이름 허용 목록을 코드에 고정한다.

서버가 쓰는 SEOUL_REALTIME_API_KEY와 SEOUL_OPENAPI_KEY는 여기서 읽지도 않는다.
키 값은 이 모듈 밖으로 이름과 함께만 나가고, 로그·DB에는 이름만 남긴다.
"""

from collections.abc import Mapping
from datetime import date
from pathlib import Path

from dotenv import dotenv_values

# 순서가 곧 쓰는 순서다: 위치 키 → 예비 1 → 예비 2.
KEY_ENV: dict[str, str] = {
    "position": "SEOUL_POSITION_API_KEY",
    "spare1": "SEOUL_SPARE_KEY_1",
    "spare2": "SEOUL_SPARE_KEY_2",
}


def load_keys(env_file: str | Path | None, environ: Mapping[str, str]) -> dict[str, str]:
    """값이 있는 키만 쓰는 순서대로. 환경 변수가 `.env`보다 먼저다."""
    from_file = dotenv_values(env_file) if env_file else {}
    keys: dict[str, str] = {}
    for name, env_name in KEY_ENV.items():
        value = (environ.get(env_name) or from_file.get(env_name) or "").strip()
        if value:
            keys[name] = value
    return keys


class KeyRing:
    """지금 쓸 키를 고른다. 일일 한도(ERROR-337)에 걸린 키는 그날(KST) 쉰다."""

    def __init__(self, keys: Mapping[str, str], blocked: Mapping[str, date] | None = None):
        unknown = set(keys) - set(KEY_ENV)
        if unknown:
            raise ValueError(f"수집기가 쓸 수 없는 키 이름: {sorted(unknown)}")
        self._keys = {name: keys[name] for name in KEY_ENV if name in keys}
        self._blocked: dict[str, date] = dict(blocked or {})

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(self._keys)

    def current(self, today: date) -> tuple[str, str] | None:
        """(이름, 값). 오늘 쓸 수 있는 키가 없으면 None."""
        for name, value in self._keys.items():
            if self._blocked.get(name) != today:
                return name, value
        return None

    def block(self, name: str, today: date) -> None:
        self._blocked[name] = today
