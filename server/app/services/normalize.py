"""역·호선 이름 정규화와 초성 변환 (스펙 6.5). 역 데이터 빌드 스크립트와 서버가 함께 쓴다."""

import re
from dataclasses import dataclass

# 출처마다 다른 호선 표기를 하나로 맞춘다. P1 §2·§3의 호선 표기 목록으로 보탠다.
LINE_ALIASES: dict[str, str] = {
    "공항철도1호선": "공항철도",
    "인천국제공항철도": "공항철도",
    "경의선": "경의중앙선",
    "중앙선": "경의중앙선",
    "경의·중앙선": "경의중앙선",
    "분당선": "수인분당선",
    "수인선": "수인분당선",
    "수인·분당선": "수인분당선",
    "우이신설경전철": "우이신설선",
    "신림경전철": "신림선",
}

# 서울시 실시간 도착 API의 subwayId → 정규화된 호선 이름. P1 §1 코드표로 확정한다.
SUBWAY_ID_TO_LINE: dict[str, str] = {
    **{f"100{n}": f"{n}호선" for n in range(1, 10)},
    "1061": "경의중앙선",
    "1063": "경의중앙선",
    "1065": "공항철도",
    "1067": "경춘선",
    "1075": "수인분당선",
    "1077": "신분당선",
    "1081": "경강선",
    "1092": "우이신설선",
    "1093": "서해선",
    "1032": "GTX-A",
}

CHOSUNG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

_LINE_PREFIXES = ("수도권", "서울지하철", "서울")  # 긴 것부터 확인한다("서울지하철" > "서울")
_NUMBERED_LINE = re.compile(r"^0*(\d+)호선$")
_PAREN_NAME = re.compile(r"^(.*?)\((.+)\)$")
_HANGUL_FIRST, _HANGUL_LAST = ord("가"), ord("힣")
_SYLLABLES_PER_CHOSUNG = 21 * 28  # 중성 21개 × 종성 28개


@dataclass(frozen=True)
class NormalizedName:
    id: str
    aliases: tuple[str, ...]


def _collapse_spaces(text: str) -> str:
    """규칙 1: 앞뒤 공백을 지우고 연속된 공백을 하나로 줄인다."""
    return " ".join(text.split())


def _strip_station_suffix(name: str) -> str:
    """규칙 2: 끝의 "역"을 지운다. 지운 뒤 1자 이하만 남으면 지우지 않는다."""
    if name.endswith("역") and len(name) - 1 > 1:
        return name[:-1]
    return name


def normalize_station_name(raw: str) -> NormalizedName:
    """규칙 1 → 3 → 2 순서로 정규화한다. 괄호를 먼저 떼야 "OO역(XX)"의 끝 "역"을 지울 수 있다."""
    full = _collapse_spaces(raw)
    match = _PAREN_NAME.match(full)
    base = match.group(1).strip() if match else ""
    if not base:  # 괄호가 없거나 괄호 앞이 비어 있으면 나누지 않는다(빈 id 방지)
        return NormalizedName(_strip_station_suffix(full), ())
    inner = _collapse_spaces(match.group(2))
    return NormalizedName(_strip_station_suffix(base), (_strip_station_suffix(inner), full))


def normalize_line(raw: str) -> str:
    name = "".join(raw.split())
    if name in LINE_ALIASES:
        return LINE_ALIASES[name]
    for prefix in _LINE_PREFIXES:
        if name.startswith(prefix) and len(name) > len(prefix):
            name = name[len(prefix) :]
            break
    if name in LINE_ALIASES:  # "수도권 분당선"처럼 접두어 뒤에 별칭이 오는 표기
        return LINE_ALIASES[name]
    if name.startswith("인천"):
        return name
    match = _NUMBERED_LINE.match(name)
    if match:
        return f"{int(match.group(1))}호선"
    return name


def to_chosung(text: str) -> str:
    """한글 음절(가~힣)은 초성으로 바꾸고, 그 밖의 글자는 그대로 둔다."""
    out = []
    for ch in text:
        code = ord(ch)
        if _HANGUL_FIRST <= code <= _HANGUL_LAST:
            out.append(CHOSUNG[(code - _HANGUL_FIRST) // _SYLLABLES_PER_CHOSUNG])
        else:
            out.append(ch)
    return "".join(out)


def is_chosung_query(q: str) -> bool:
    """공백을 뺀 검색어가 비어 있지 않고 모두 한글 자음(초성)이면 초성 검색이다."""
    compact = "".join(q.split())
    return bool(compact) and all(ch in CHOSUNG for ch in compact)
