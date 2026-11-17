from app.core.config import Settings


def test_defaults_match_spec():
    s = Settings(_env_file=None)
    assert (
        s.seoul_realtime_daily_limit,
        s.seoul_timetable_daily_limit,
        s.kakao_route_daily_limit,
        s.rate_limit_per_minute,
        s.mock_upstream,
    ) == (0, 0, 1000, 60, False)  # 서울시 키는 한도가 없다(0 = 제한 없음, 2026-11-17)


def test_env_overrides(monkeypatch):
    monkeypatch.setenv("SEOUL_REALTIME_DAILY_LIMIT", "5")
    monkeypatch.setenv("MOCK_UPSTREAM", "true")
    s = Settings(_env_file=None)
    assert s.seoul_realtime_daily_limit == 5 and s.mock_upstream is True
