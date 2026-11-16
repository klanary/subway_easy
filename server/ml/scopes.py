"""시간표 변경을 판단하는 단위(scope) = (요일 구분, 호선, 방향, 1시간대).

시간대(band)는 서비스 날짜 0시부터 지난 초를 3600으로 나눈 몫이다(5~24. 00:30은 24시대).
"""

from collections.abc import Iterable

Scope = tuple[int, str, int, int]  # (day_type, line, direction, band)
BAND_SEC = 3600


def band_of(secs: int | float) -> int:
    return int(secs) // BAND_SEC


def scope_key(scope: Scope) -> str:
    day_type, line, direction, band = scope
    return f"{day_type}|{line}|{direction}|{band}"


def parse_scope_key(key: str) -> Scope:
    day_type, line, direction, band = key.split("|")
    return int(day_type), line, int(direction), int(band)


def merge_bands(bands: Iterable[int]) -> list[tuple[int, int]]:
    """이어진 시간대끼리 (처음, 끝)으로 묶는다(양 끝 포함)."""
    merged: list[tuple[int, int]] = []
    for band in sorted(set(bands)):
        if merged and band == merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], band)
        else:
            merged.append((band, band))
    return merged
