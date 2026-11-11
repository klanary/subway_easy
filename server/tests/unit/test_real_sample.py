"""서울시 실시간 도착 API의 실제 응답(공식 샘플 키, 2026-11-11)으로 해석 함수를 확인한다.

샘플 키는 '서울'역 5행까지만 준다. 본인 키로 받은 샘플은 scripts/capture_fixtures.py가 더한다.
"""

import json
from datetime import datetime
from pathlib import Path

import pytest

from app.core.clock import KST
from app.services.remaining_time import (
    compute_remaining_sec,
    is_departed,
    is_express,
    is_last,
    parse_train_line_nm,
)

FIXTURES = Path(__file__).parent.parent / "fixtures"


def _body(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))["body"]


@pytest.fixture(scope="module")
def rows() -> list[dict]:
    return _body("seoul_realtime_sample_seoul.json")["realtimeArrivalList"]


def test_normal_response_is_wrapped_and_error_is_flat():
    ok = _body("seoul_realtime_sample_seoul.json")
    assert ok["errorMessage"]["code"] == "INFO-000"
    # 오류는 HTTP 200에 errorMessage 없이 code만 온다. 클라이언트는 두 형식을 모두 읽어야 한다.
    error = _body("seoul_realtime_sample_error.json")
    assert "errorMessage" not in error and "realtimeArrivalList" not in error
    assert error["code"].startswith("ERROR-")


def test_every_row_has_the_fields_the_spec_uses(rows):
    needed = {
        "subwayId",
        "updnLine",
        "trainLineNm",
        "statnNm",
        "statnId",
        "btrainSttus",
        "barvlDt",
        "btrainNo",
        "bstatnNm",
        "recptnDt",
        "arvlMsg2",
        "arvlMsg3",
        "arvlCd",
        "lstcarAt",
    }
    for row in rows:
        assert needed <= row.keys()


def test_train_line_nm_splits_into_destination_and_towards(rows):
    for row in rows:
        destination, towards = parse_train_line_nm(row["trainLineNm"])
        assert destination and towards, row["trainLineNm"]
        assert destination == row["bstatnNm"]
        assert "행" not in destination and "방면" not in towards


def test_express_last_and_departed_flags_follow_the_raw_fields(rows):
    for row in rows:
        assert is_express(row) == (row["btrainSttus"] == "급행")
        assert is_last(row) == (row["lstcarAt"] == "1")
        message = row["arvlMsg2"]
        assert is_departed(message) == (message.endswith("출발") and "전역" not in message)


def test_remaining_time_is_computed_for_every_row(rows):
    for row in rows:
        now = datetime.strptime(row["recptnDt"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
        sec = compute_remaining_sec(row, now)
        assert sec is None or sec >= 0, row["arvlMsg2"]
        if int(row["barvlDt"]) > 0:
            assert sec == int(row["barvlDt"])
