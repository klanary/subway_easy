import pytest

from app.services.geo import haversine_m
from app.services.normalize import (
    SUBWAY_ID_TO_LINE,
    NormalizedName,
    is_chosung_query,
    normalize_line,
    normalize_station_name,
    to_chosung,
)


@pytest.mark.parametrize(
    "raw, id_, aliases",
    [
        ("  홍대입구역 ", "홍대입구", ()),
        ("서울역", "서울", ()),
        ("역", "역", ()),  # 제거 후 1자 이하면 그대로
        ("디지털  미디어시티", "디지털 미디어시티", ()),
        ("총신대입구(이수)", "총신대입구", ("이수", "총신대입구(이수)")),
        # 괄호를 먼저 떼야 끝의 '역'이 제거됨
        ("가나역(다라역)", "가나", ("다라", "가나역(다라역)")),
        ("(이수)", "(이수)", ()),  # 괄호 앞이 비면 나누지 않는다(빈 id 방지)
    ],
)
def test_normalize_station_name(raw, id_, aliases):
    assert normalize_station_name(raw) == NormalizedName(id_, aliases)


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("2호선", "2호선"),
        ("수도권 2호선", "2호선"),
        ("서울 2호선", "2호선"),
        ("02호선", "2호선"),
        ("공항철도1호선", "공항철도"),
        ("인천1호선", "인천1호선"),
        ("인천 1호선", "인천1호선"),
        ("분당선", "수인분당선"),
        ("신분당선", "신분당선"),
        ("서해선", "서해선"),
    ],
)
def test_normalize_line(raw, expected):
    assert normalize_line(raw) == expected


def test_subway_ids_map_to_normalized_lines():
    assert SUBWAY_ID_TO_LINE["1002"] == "2호선"
    assert all(normalize_line(v) == v for v in SUBWAY_ID_TO_LINE.values())


@pytest.mark.parametrize(
    "text, expected", [("홍대입구", "ㅎㄷㅇㄱ"), ("을지로3가", "ㅇㅈㄹ3ㄱ"), ("DMC", "DMC")]
)
def test_to_chosung(text, expected):
    assert to_chosung(text) == expected


@pytest.mark.parametrize(
    "q, expected",
    [("ㅎㄷㅇㄱ", True), ("ㅎㄷ ㅇㄱ", True), ("ㅎㄷ입구", False), ("ㄳ", False), ("  ", False)],
)
def test_is_chosung_query(q, expected):
    assert is_chosung_query(q) is expected


def test_haversine_one_hundredth_degree_latitude():
    assert abs(haversine_m(37.0, 127.0, 37.01, 127.0) - 1112) < 2
