"""서울시 역 정보의 역 코드를 우리 역 id·호선에 잇는다(재설계 스펙 5.2).

실제 데이터는 2026-11-12에 받은 것이다.
"""

import json

from app.services.normalize import normalize_line
from app.services.station_index import NON_SEOUL_LINES, StationIndex
from scripts.build_stations import link_station_codes
from tests.helpers import FIXTURES_DIR


def test_line_names_from_station_info():
    raw = ["05호선", "경의선", "인천선", "용인경전철", "의정부경전철", "김포도시철도"]
    assert [normalize_line(x) for x in raw] == [
        "5호선",
        "경의중앙선",
        "인천1호선",
        "에버라인선",
        "의정부선",
        "김포골드라인",
    ]
    assert normalize_line("우이신설경전철") == "우이신설선"
    assert normalize_line("공항철도") == "공항철도"


def test_station_code_lookup(real_index):
    assert real_index.station_code("영등포구청", "5호선") == "2524"
    assert real_index.station_code("홍대입구", "2호선") == "0239"
    assert real_index.station_code("홍대입구", "9호선") is None
    assert real_index.station_code("없는역", "2호선") is None


def test_realtime_supported():
    assert NON_SEOUL_LINES == {"인천1호선", "인천2호선", "에버라인선", "의정부선", "김포골드라인"}
    assert not StationIndex.realtime_supported("인천1호선")
    assert StationIndex.realtime_supported("5호선")


def test_link_skips_unknown_names(sample_index):
    info = json.loads((FIXTURES_DIR / "station_info_sample.json").read_text(encoding="utf-8"))
    rows, unmatched = link_station_codes(info, sample_index)
    linked = {(r.station_id, r.line_name): r.station_cd for r in rows}
    assert linked[("홍대입구", "2호선")] == "0239"
    assert "없는역(2호선)" in unmatched


def test_real_data_links_almost_all_seoul_lines(real_index):
    """서울 노선(비서울 5개 제외)의 역-호선 쌍 중 코드가 없는 것은 5% 이하."""
    pairs = [
        (s.id, line) for s in real_index.all() for line in s.lines if line not in NON_SEOUL_LINES
    ]
    missing = [p for p in pairs if real_index.station_code(*p) is None]
    assert len(missing) <= len(pairs) * 0.05, missing


def test_renamed_and_track_line_stations_are_linked(real_index):
    """역사마스터가 선로 이름만 적은 역(오이도 수인분당선)과 이름이 바뀐 역(자양)도 잇는다."""
    assert real_index.station_code("오이도", "수인분당선") is not None
    assert real_index.station_code("뚝섬유원지", "7호선") is not None


def test_direction_from_realtime_sweep(real_index):
    """2026-11-12 낮 실시간 전 역 조회로 모은 "방향 → 다음 역"."""
    assert real_index.direction("홍대입구", "2호선", "신촌") == "내선"
    assert real_index.direction("홍대입구", "2호선", "합정") == "외선"
    assert real_index.direction("홍대입구", "경의중앙선", "가좌") == "상행"
    assert real_index.direction("시청", "2호선", "충정로") == "외선"


def test_direction_rule_without_realtime(real_index):
    """실시간이 없는 역은 역 번호(FR_CODE) 순서로 정한다. 진접 405 → 오남 406."""
    assert real_index.direction("진접", "4호선", "오남") == "하행"
    assert real_index.direction("오남", "4호선", "진접") == "상행"


def test_direction_unknown(real_index):
    assert real_index.direction("홍대입구", "2호선", "강남") is None  # 이웃 역이 아님
    assert real_index.direction("계양", "인천1호선", "귤현") is None
