"""노선도 역 위치 추출(재설계 스펙 8). 조각 SVG는 실제 노선도에서 역 몇 개만 잘라 낸 것이다."""

import json

import pytest

from scripts.build_linemap import build, main
from tests.helpers import FIXTURES_DIR

SNIPPET = FIXTURES_DIR / "linemap_snippet.svg"


@pytest.fixture
def table(real_index):
    result, unmatched = build(SNIPPET, real_index)
    return {s["id"]: (s["x"], s["y"]) for s in result["stations"]}, unmatched, result


def close(a, b, tol=5):
    return abs(a[0] - b[0]) <= tol and abs(a[1] - b[1]) <= tol


def test_labels_snap_to_station_marks(table):
    points, _, _ = table
    assert close(points["을지로3가"], (3224, 2422))  # 역 눈금
    assert close(points["응봉"], (3616, 2555))
    assert close(points["시청"], (3059.8, 2483.8))  # 환승역 기호


def test_same_name_stations_split_east_west(table):
    points, _, _ = table
    assert close(points["양평"], (2484, 2798))  # 5호선(서쪽)
    assert close(points["양평(경의중앙선)"], (5460, 2278))  # 경의중앙선(동쪽)


def test_label_without_mark_keeps_its_anchor_and_same_place(table):
    points, _, _ = table
    assert points["이수"] == (4000, 4000)
    assert points["총신대입구"] == points["이수"]


def test_non_station_text_is_reported(table):
    _, unmatched, result = table
    assert "250628Nottoscale" in unmatched
    assert (result["width"], result["height"]) == (5724, 6516)


def test_same_input_same_output(tmp_path, real_index):
    out1, out2 = tmp_path / "a", tmp_path / "b"
    for out in (out1, out2):
        assert main([str(SNIPPET), "data/stations.sqlite", str(out)]) == 0
    first = (out1 / "stations.json").read_text(encoding="utf-8")
    assert first == (out2 / "stations.json").read_text(encoding="utf-8")
    assert json.loads(first)["stations"] and (out1 / "linemap.svg").exists()


def test_real_linemap_links_95_percent(tmp_path, real_index):
    """실제 노선도(data/raw)로 우리 역의 95% 이상을 잇는다(재설계 스펙 8의 판정 기준)."""
    svg = FIXTURES_DIR.parent.parent / "data/raw/Seoul_subway_linemap_ko.svg"
    result, _ = build(svg, real_index)
    assert len(result["stations"]) >= 0.95 * len(real_index.all())
