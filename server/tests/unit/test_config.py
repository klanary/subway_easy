from app.core.config import Settings


def test_defaults_match_spec():
    s = Settings(_env_file=None)
    assert (
        s.seoul_realtime_daily_limit,
        s.kakao_route_daily_limit,
        s.kakao_keyword_daily_limit,
        s.kakao_coord2address_daily_limit,
        s.rate_limit_per_minute,
        s.mock_upstream,
    ) == (1000, 1000, 100000, 10000, 60, False)


def test_env_overrides(monkeypatch):
    monkeypatch.setenv("SEOUL_REALTIME_DAILY_LIMIT", "5")
    monkeypatch.setenv("MOCK_UPSTREAM", "true")
    s = Settings(_env_file=None)
    assert s.seoul_realtime_daily_limit == 5 and s.mock_upstream is True
