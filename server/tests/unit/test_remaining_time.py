from datetime import datetime

import pytest

from app.core.clock import KST
from app.services.remaining_time import (
    compute_remaining_sec,
    is_departed,
    is_express,
    is_last,
    is_stale,
    parse_train_line_nm,
)
from tests.helpers import FIXTURES_DIR, load_fixture

NOW = datetime(2026, 11, 3, 8, 0, 30, tzinfo=KST)
SEOUL_FIXTURES = [
    "seoul_realtime_hongdae.json",
    "seoul_realtime_cityhall.json",
    "seoul_realtime_exception.json",
]


def item(msg="", barvl="0", recptn="2026-11-03 08:00:00"):
    return {"arvlMsg2": msg, "barvlDt": barvl, "recptnDt": recptn}


@pytest.mark.parametrize(
    "msg, expected",
    [
        ("3분 20초 후 (신촌)", 200),
        ("4분 후 (아현)", 240),
        ("곧 도착", 30),
        ("홍대입구 진입", 30),
        ("홍대입구 도착", 0),
        ("전역 출발", 90),
        ("전역 도착", 120),
        ("전역 진입", 150),
        ("[5]번째 전역 (신촌)", 600),
        ("운행중", None),
    ],
)
def test_message_patterns(msg, expected):
    assert compute_remaining_sec(item(msg), NOW) == expected


def test_barvl_dt_minus_elapsed():
    assert compute_remaining_sec(item("3분 후", barvl="180"), NOW) == 150


def test_recptn_with_fraction():
    assert compute_remaining_sec(item(barvl="180", recptn="2026-11-03 08:00:00.0"), NOW) == 150


def test_negative_clamped_to_zero():
    assert compute_remaining_sec(item(barvl="10"), NOW) == 0


def test_future_recptn_is_treated_as_now():
    # 생성 시각이 지금보다 160초 뒤로 와도(실측 사례) 남은 시간을 부풀리지 않는다.
    assert compute_remaining_sec(item(barvl="120", recptn="2026-11-03 08:03:10"), NOW) == 120


def test_stale_item_is_detected():
    # 18분 전에 만든 정보(실측 사례)는 이미 지나간 열차일 수 있다. 10분이 넘으면 버린다.
    assert is_stale(item(barvl="120", recptn="2026-11-03 07:42:30"), NOW)
    assert not is_stale(item(barvl="120", recptn="2026-11-03 07:51:00"), NOW)
    assert not is_stale({"arvlMsg2": "3분 후"}, NOW)  # 생성 시각을 모르면 버리지 않는다


def test_departed_message():
    assert is_departed("홍대입구 출발") and not is_departed("전역 출발") and not is_departed(None)


@pytest.mark.parametrize(
    "value, expected",
    [("성수행 - 합정방면", ("성수", "합정")), ("", (None, None)), (None, (None, None))],
)
def test_parse_train_line_nm(value, expected):
    assert parse_train_line_nm(value) == expected


def test_express_and_last():
    assert is_express({"btrainSttus": "급행"})
    assert not is_express({"btrainSttus": "일반"}) and not is_express({})
    assert is_last({"lstcarAt": "1"}) and not is_last({"lstcarAt": "0"})


@pytest.mark.skipif(
    not all((FIXTURES_DIR / name).exists() for name in SEOUL_FIXTURES),
    reason="서울시 실시간 샘플 수집 전(P1 Task 4 Step 7)",
)
def test_all_fixture_messages_are_understood():
    # 실제 샘플의 모든 열차가 남은 시간을 갖거나 '출발' 열차여야 한다.
    # 실패하면 스펙 6.7 표를 먼저 고친다.
    for name in SEOUL_FIXTURES:
        _, body = load_fixture(name)
        for it in body["realtimeArrivalList"]:
            now = datetime.strptime(it["recptnDt"][:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
            assert compute_remaining_sec(it, now) is not None or is_departed(it["arvlMsg2"]), it[
                "arvlMsg2"
            ]


def test_arrival_code_when_message_unreadable():
    """코레일 원천은 barvlDt가 대부분 0이다(관리자 안내). 문구로 못 읽으면 arvlCd로 추정한다."""
    from datetime import datetime

    from app.core.clock import KST
    from app.services.remaining_time import compute_remaining_sec

    now = datetime(2026, 11, 22, 12, 0, tzinfo=KST)
    base = {"barvlDt": "0", "arvlMsg2": "알 수 없는 문구", "recptnDt": "2026-11-22 11:59:55"}
    assert compute_remaining_sec({**base, "arvlCd": "1"}, now) == 0  # 도착
    assert compute_remaining_sec({**base, "arvlCd": "0"}, now) == 30  # 진입
    assert compute_remaining_sec({**base, "arvlCd": "4"}, now) == 150  # 전역 진입
    assert compute_remaining_sec({**base, "arvlCd": "5"}, now) == 120  # 전역 도착
    assert compute_remaining_sec({**base, "arvlCd": "3"}, now) == 90  # 전역 출발
    assert compute_remaining_sec({**base, "arvlCd": "99"}, now) is None  # 운행중만으로는 모른다
    # 문구를 읽을 수 있으면 문구가 먼저다(안양 실제 응답: arvlCd 99, "[2]번째 전역 (금정)")
    assert (
        compute_remaining_sec({**base, "arvlCd": "99", "arvlMsg2": "[2]번째 전역 (금정)"}, now)
        == 240
    )
