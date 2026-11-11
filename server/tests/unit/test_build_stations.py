import json
import sqlite3
from pathlib import Path

import httpx
import pytest
import respx

from scripts.build_stations import (
    BuildError,
    build,
    read_master,
    read_master_csv,
    write_sqlite,
)
from scripts.fetch_station_master import MASTER_URL, PAGE_SIZE, fetch_all

FIXTURES = Path(__file__).parents[1] / "fixtures"
SAMPLE = FIXTURES / "master_sample.csv"
REAL_SAMPLE = FIXTURES / "seoul_station_master_sample.json"
SEP = {("양평", "경의중앙선"): "양평(경의중앙선)"}


def built(separate=SEP, api_names=None):
    rows, _ = read_master_csv(SAMPLE)
    return build(rows, separate=separate, api_names=api_names or {})


def station(result, id_):
    return next(s for s in result.stations if s.id == id_)


def test_same_name_lines_grouped_and_averaged():
    r = built()
    lines = sorted(line.line_name for line in r.lines if line.station_id == "홍대입구")
    assert lines == ["2호선", "경의중앙선", "공항철도"]
    expected = (37.557192 + 37.557641 + 37.557335) / 3
    assert station(r, "홍대입구").lat == pytest.approx(expected, abs=1e-6)


def test_far_apart_same_name_split_by_table():
    r = built()
    assert station(r, "양평").api_name == station(r, "양평(경의중앙선)").api_name == "양평"
    assert station(r, "양평(경의중앙선)").display_name == "양평"


def test_unlisted_far_apart_same_name_fails():
    with pytest.raises(BuildError, match="양평"):
        built(separate={})


def test_aliases_skip_station_ids():
    aliases = dict(built().aliases)
    assert aliases["총신대입구(이수)"] == "총신대입구" and aliases["동서울터미널"] == "강변"
    assert "이수" not in aliases  # 7호선 '이수' 역의 id와 겹침


def test_api_name_exception_applied():
    assert station(built(api_names={"서울": "서울역"}), "서울").api_name == "서울역"


def test_chosung_and_display_name():
    seoul = station(built(), "서울")
    assert (seoul.display_name, seoul.chosung) == ("서울역", "ㅅㅇ")


def test_rows_without_coords_skipped():
    rows, skipped = read_master_csv(SAMPLE)
    assert skipped == 1 and all(r.name != "좌표없는역" for r in rows)


def test_reads_cp949(tmp_path):
    p = tmp_path / "m.csv"
    p.write_bytes(SAMPLE.read_text(encoding="utf-8").encode("cp949"))
    assert read_master_csv(p) == read_master_csv(SAMPLE)


def test_reads_openapi_json_with_english_columns():
    """서울시 OpenAPI의 실제 응답: 열 이름이 BLDN_ID, BLDN_NM, ROUTE, LAT, LOT다."""
    rows, skipped = read_master(REAL_SAMPLE)
    assert skipped == 0 and len(rows) == 5
    first = rows[0]
    assert (first.master_id, first.name, first.line) == ("0150", "서울역", "1호선")
    assert first.lat == pytest.approx(37.556228) and first.lng == pytest.approx(126.972135)


def test_reads_csv_with_english_columns(tmp_path):
    p = tmp_path / "m.csv"
    lines = ["BLDN_ID,BLDN_NM,ROUTE,LAT,LOT", "0150,서울역,1호선,37.556228,126.972135"]
    p.write_text("\n".join(lines) + "\n", "utf-8")
    assert read_master(p)[0] == read_master(REAL_SAMPLE)[0][:1]


def test_unknown_columns_fail_with_the_header(tmp_path):
    p = tmp_path / "m.csv"
    p.write_text("a,b\n1,2\n", "utf-8")
    with pytest.raises(BuildError, match="a"):
        read_master_csv(p)


def test_output_is_deterministic(tmp_path):
    a, b = tmp_path / "a.sqlite", tmp_path / "b.sqlite"
    write_sqlite(built(), a)
    write_sqlite(built(), b)

    def dump(path):
        con = sqlite3.connect(path)
        try:
            return "\n".join(con.iterdump())
        finally:
            con.close()

    assert dump(a) == dump(b)
    assert "홍대입구" in dump(a)


@respx.mock
def test_fetch_all_pages_until_total():
    """일반 인증키로 전체 행을 나눠 받는다. 키는 URL 경로에 들어간다."""
    sample = json.loads(REAL_SAMPLE.read_text(encoding="utf-8"))["subwayStationMaster"]
    total = PAGE_SIZE + 2

    def page(rows):
        body = {"subwayStationMaster": {**sample, "list_total_count": total, "row": rows}}
        return httpx.Response(200, json=body)

    first = respx.get(MASTER_URL.format(key="K", start=1, end=PAGE_SIZE)).mock(
        return_value=page([sample["row"][0]] * PAGE_SIZE)
    )
    second = respx.get(MASTER_URL.format(key="K", start=PAGE_SIZE + 1, end=2 * PAGE_SIZE)).mock(
        return_value=page(sample["row"][:2])
    )
    with httpx.Client() as client:
        rows = fetch_all(client, "K")
    assert first.called and second.called and len(rows) == total


@respx.mock
def test_fetch_all_raises_on_error_body():
    error = {"RESULT": {"CODE": "INFO-100", "MESSAGE": "키 오류"}}
    respx.get(MASTER_URL.format(key="K", start=1, end=PAGE_SIZE)).mock(
        return_value=httpx.Response(200, json=error)
    )
    with httpx.Client() as client, pytest.raises(RuntimeError, match="INFO-100"):
        fetch_all(client, "K")
