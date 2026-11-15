from datetime import date, datetime, timedelta

from app.core.clock import KST
from ml.parse import parse_rows, result_code, service_date
from tests.helpers import load_fixture

RECEIVED = datetime(2026, 11, 15, 15, 5, 0, tzinfo=KST)


def _row(**over) -> dict:
    row = {
        "subwayNm": "5호선",
        "statnId": "1005000510",
        "statnNm": "방화",
        "trainNo": "5603",
        "recptnDt": "2026-11-15 15:04:30",
        "updnLine": "1",
        "statnTid": "1005080555",
        "statnTnm": "마천",
        "trainSttus": "2",
        "directAt": "0",
        "lstcarAt": "0",
    }
    row.update(over)
    return row


def _body(*rows: dict) -> dict:
    return {"errorMessage": {"code": "INFO-000"}, "realtimePositionList": list(rows)}


def test_real_response_becomes_events():
    _, body = load_fixture("seoul_position_line5")
    events, skipped = parse_rows(body, "5호선", RECEIVED)
    assert (len(events), skipped) == (35, 0)
    first = events[0]
    assert (first.train_no, first.statn_id, first.statn_nm) == ("5603", "1005000510", "방화")
    assert (first.direction, first.status, first.line) == (1, 0, "5호선")
    assert first.event_at == datetime(2026, 11, 15, 15, 3, 41, tzinfo=KST)
    assert (first.dest_statn_id, first.dest_name) == ("1005080555", "마천")
    assert first.service_date == date(2026, 11, 15)
    assert first.day_type == 3  # 일요일
    assert first.flag is None


def test_service_date_before_one_is_previous_day():
    assert service_date(datetime(2026, 11, 16, 0, 59, 59, tzinfo=KST)) == date(2026, 11, 15)
    assert service_date(datetime(2026, 11, 16, 1, 0, 0, tzinfo=KST)) == date(2026, 11, 16)


def test_day_type_follows_service_date():
    # 월요일 00:30의 이벤트는 일요일(휴일) 운행이다.
    events, _ = parse_rows(
        _body(_row(recptnDt="2026-11-16 00:30:00")),
        "5호선",
        datetime(2026, 11, 16, 0, 30, 20, tzinfo=KST),
    )
    assert (events[0].service_date, events[0].day_type) == (date(2026, 11, 15), 3)


def test_flags_future_and_stale_times():
    def flag(delta: int) -> str | None:
        at = (RECEIVED + timedelta(seconds=delta)).strftime("%Y-%m-%d %H:%M:%S")
        return parse_rows(_body(_row(recptnDt=at)), "5호선", RECEIVED)[0][0].flag

    assert flag(30) is None
    assert flag(31) == "future"
    assert flag(-600) is None
    assert flag(-601) == "stale"


def test_bad_rows_are_skipped_and_counted():
    body = _body(
        _row(),
        _row(recptnDt=None),
        _row(recptnDt="어제"),
        _row(trainNo=""),
        _row(statnId=None),
        _row(trainSttus="9"),
        _row(updnLine="상행"),
        "행이 아님",
    )
    events, skipped = parse_rows(body, "5호선", RECEIVED)
    assert (len(events), skipped) == (1, 7)


def test_body_without_list_is_empty():
    assert parse_rows({"code": "INFO-200"}, "5호선", RECEIVED) == ([], 0)
    assert parse_rows({"realtimePositionList": None}, "5호선", RECEIVED) == ([], 0)


def test_express_and_last_train_are_kept_as_numbers():
    events, _ = parse_rows(_body(_row(directAt="7", lstcarAt="1")), "5호선", RECEIVED)
    assert (events[0].express, events[0].last_train) == (7, 1)


def test_result_code_reads_both_shapes():
    assert result_code({"errorMessage": {"code": "INFO-000"}}) == "INFO-000"
    assert result_code({"status": 500, "code": "ERROR-337"}) == "ERROR-337"
    assert result_code([]) is None
    assert result_code({"errorMessage": "?"}) is None
