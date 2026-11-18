"""노선도 역 위치 추출(재설계 스펙 8, 2026-11-19 노선도 교체).

작은 손 SVG로 규칙을 보고, 실제 노선도(data/raw/linemap_atlas.svg)로 2026-11-19에 눈으로 확인한
역 위치를 고정한다.
"""

import json

import pytest

from scripts.build_linemap import build, hungarian, is_pill, main, path_center
from tests.helpers import FIXTURES_DIR

ATLAS = FIXTURES_DIR.parent.parent / "data/raw/linemap_atlas.svg"
GREEN, ORANGE = "#3CB44A", "#FF7300"


def svg(body: str) -> str:
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 600" width="1000" height="600">'
        + body
        + "</svg>"
    )


def text(name: str, x: float, y: float) -> str:
    return f'<text transform="matrix(1 0 0 1 {x} {y})">{name}</text>'


def dot(x: float, y: float, color: str) -> str:
    return f'<circle cx="{x}" cy="{y}" r="5" fill="#FFFFFF" stroke="{color}"/>'


def line(x1, y1, x2, y2, color) -> str:
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="12"/>'


def pill(cx: float, cy: float) -> str:
    """세로 알약(18 × 34)."""
    return f'<path fill="#FFFFFF" d="M{cx - 9},{cy - 17}h18v34h-18z"/>'


@pytest.fixture
def points(tmp_path, real_index):
    def run(body: str) -> dict:
        path = tmp_path / "map.svg"
        path.write_text(svg(body), encoding="utf-8")
        result, _ = build(path, real_index)
        return {s["id"]: (s["x"], s["y"]) for s in result["stations"]}

    return run


def close(a, b, tol=2.0):
    return abs(a[0] - b[0]) <= tol and abs(a[1] - b[1]) <= tol


def test_labels_offset_one_step_do_not_shift_neighbours(points):
    # 2호선 신촌·이대·아현: 글자가 원보다 오른쪽 아래로 크게 밀려 있어, 가까운 것부터 차지하면
    # 신촌 글자가 이대 원을 가져간다. 최소 비용 짝짓기는 차례를 지킨다.
    body = line(100, 100, 400, 100, GREEN)
    body += dot(100, 100, GREEN) + dot(200, 100, GREEN) + dot(300, 100, GREEN)
    body += text("신촌", 160, 140) + text("이대", 260, 140) + text("아현", 360, 140)
    p = points(body)
    assert (
        close(p["신촌"], (100, 100))
        and close(p["이대"], (200, 100))
        and close(p["아현"], (300, 100))
    )


def test_dot_of_other_line_color_is_not_taken(points):
    # 3호선 압구정 글자 옆에 경의중앙선(다른 색) 원만 있으면 그 원을 쓰지 않고 3호선 선 위로 간다.
    body = line(100, 300, 500, 300, ORANGE) + line(100, 200, 500, 200, GREEN)
    body += dot(200, 300, ORANGE) + text("옥수", 210, 330)  # 3호선 색을 알려 주는 역
    body += dot(380, 200, GREEN) + text("이대", 390, 230)  # 2호선 색
    body += text("압구정", 360, 280)
    p = points(body)
    assert p["압구정"][1] == pytest.approx(300, abs=0.5)  # 3호선 선 위
    assert close(p["이대"], (380, 200))


def test_transfer_station_takes_white_pill(points):
    body = line(100, 100, 400, 100, GREEN) + line(250, 0, 250, 300, ORANGE)
    body += dot(150, 100, GREEN) + text("이대", 160, 130)
    body += dot(250, 220, ORANGE) + text("옥수", 260, 250)
    body += pill(250, 100) + text("을지로3가", 270, 90)
    assert close(points(body)["을지로3가"], (250, 100))


def test_white_glyph_in_badge_is_not_a_mark():
    assert is_pill("M0,0h18v34h-18z")  # 알약
    assert is_pill("M0,0h23v23h-23z")  # 큰 원(정사각 경계)
    assert not is_pill("M0,0h12v17h-12z")  # 이름표 안 흰 글자


def test_path_center_of_pill():
    assert path_center("M0,0l10,0l0,20l-10,0z") == (5.0, 10.0)
    assert path_center("M10,10c-2,0-4,2-4,4s2,4,4,4h6v-8z") == (11.0, 14.0)


def test_hungarian_prefers_lower_total():
    # 가까운 것부터 고르면 0→0(1), 1→1(10) = 11. 최소 합은 0→1(2), 1→0(3) = 5.
    assert hungarian([[1, 2], [3, 10]]) == [1, 0]


def test_same_input_same_output(tmp_path):
    out1, out2 = tmp_path / "a", tmp_path / "b"
    for out in (out1, out2):
        assert main([str(ATLAS), "data/stations.sqlite", str(out)]) == 0
    first = (out1 / "stations.json").read_text(encoding="utf-8")
    assert first == (out2 / "stations.json").read_text(encoding="utf-8")
    assert json.loads(first)["stations"] and (out1 / "linemap.svg").exists()


@pytest.fixture(scope="module")
def atlas():
    from app.services.station_index import StationIndex

    index = StationIndex.load("data/stations.sqlite")
    result, unmatched = build(ATLAS, index)
    return {s["id"]: (s["x"], s["y"]) for s in result["stations"]}, unmatched, index, result


def test_atlas_links_every_station(atlas):
    points, unmatched, index, result = atlas
    assert set(points) == {s.id for s in index.all()}
    assert unmatched == []
    assert (result["width"], result["height"]) == (4635, 3685)


def test_atlas_has_no_shared_points_but_same_place(atlas):
    points = atlas[0]
    by_point: dict = {}
    for station_id, p in points.items():
        by_point.setdefault(p, []).append(station_id)
    assert [sorted(ids) for ids in by_point.values() if len(ids) > 1] == [["이수", "총신대입구"]]


@pytest.mark.parametrize(
    ("station_id", "where"),
    [
        # 2026-11-19 노선도 위에 겹쳐 그려 눈으로 확인한 자리
        ("회기", (3193.1, 1095.8)),  # 흰 알약
        ("외대앞", (3304.2, 1079.8)),
        ("신이문", (3376.8, 1034.9)),
        ("광운대", (3395.6, 855.9)),  # 흰 알약(경춘선 환승)
        ("월계", (3395.6, 810.3)),
        ("태릉입구", (3609.2, 912.2)),
        ("공릉", (3609.2, 859.1)),
    ],
)
def test_atlas_checked_positions(atlas, station_id, where):
    assert close(atlas[0][station_id], where, tol=1.0), atlas[0][station_id]


def test_atlas_renamed_stations(atlas):
    points = atlas[0]
    # 서해구청(인천 2호선)과 용인중앙시장(옛 운동장·송담대)은 김량장과 다른 자리다
    assert "서구청" in points and "운동장.송담대" in points
    assert points["운동장.송담대"] != points["김량장"]
    assert points["운동장.송담대"][0] > points["김량장"][0]  # 김량장 → 용인중앙시장(동쪽)
