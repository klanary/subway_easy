from dataclasses import replace

from ml.analyze import analyze, main
from ml.b0 import B0, TimetableCache
from tests.ml_helpers import STATIONS, trip, weekdays, write_db

H8 = 8 * 3600
HEADINGS = (
    "## 1. 열차 번호가 날마다 같은가",
    "## 2. 정차 시간과 역 사이 이동 시간",
    "## 3. recptnDt 오류와 늘 이상한 역",
    "## 4. 실측과 공식 시간표의 차이",
    "## 5. 노선별 관측과 시간대별 결측",
)


def _db(tmp_path, n_days: int = 3):
    events = []
    for day in weekdays(n_days):
        events += trip(day, H8, train_no="100", statuses=(0, 1, 2, 3))
        events += trip(day, H8 + 2 * 3600, train_no="101")
    events[7] = replace(events[7], flag="stale")  # 첫날 B역의 전역출발 하나
    return write_db(tmp_path / "c.sqlite", events)


def test_report_has_the_five_sections(tmp_path):
    text = analyze(_db(tmp_path))
    for heading in HEADINGS:
        assert heading in text
    assert "9호선" in text
    assert "stale" in text
    assert "시간표 캐시가 없어" in text


def test_report_shows_stability_dwell_and_gaps(tmp_path):
    text = analyze(_db(tmp_path))
    assert "| 9호선 | 6 | 100.0% |" in text  # (역, 열차 번호) 6쌍이 3일 모두 같은 시각
    assert "| 9호선 | 30 (30~30, n=24) |" in text  # 정차 30초
    assert "9시" in text  # 8시와 10시 사이 빈 시간대


def test_one_day_cannot_judge_stability(tmp_path):
    text = analyze(_db(tmp_path, n_days=1))
    assert "판단할 수 없다" in text


def test_empty_db_does_not_crash(tmp_path):
    text = analyze(write_db(tmp_path / "c.sqlite", []))
    for heading in HEADINGS:
        assert heading in text
    assert "이벤트가 없다" in text


def test_delay_against_official_timetable(tmp_path):
    cache = TimetableCache(tmp_path / "tt")
    for i, station in enumerate(STATIONS):
        row = {
            "TRAIN_NO": "100",
            "ARRIVETIME": "00:00:00",
            "LEFTTIME": f"08:{2 * i:02d}:00",  # 실제 출발은 이보다 30초 늦다
            "DESTSTATION": "cd-D",
        }
        cache.save(f"cd-{station}", 1, 1, [row])
    b0 = B0(cache, {("9호선", s): f"cd-{s}" for s in STATIONS})
    text = analyze(_db(tmp_path), b0=b0)
    assert "| 9호선 | 9 | 100.0% | 30 |" in text  # 3일 × 출발역 3곳, 번호 일치, 지연 중앙값 30초


def test_cli_writes_report(tmp_path, capsys):
    out = tmp_path / "out" / "analysis.md"
    assert main(["--db", str(_db(tmp_path)), "--out", str(out)]) == 0
    assert HEADINGS[0] in out.read_text(encoding="utf-8")
    assert main(["--db", str(tmp_path / "없음.sqlite"), "--out", str(out)]) == 2
