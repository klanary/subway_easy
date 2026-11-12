from datetime import datetime

import pytest

from app.cache.quota_guard import QuotaGuard
from app.core.clock import KST
from tests.helpers import FakeClock


@pytest.fixture
def late_clock():
    return FakeClock(datetime(2026, 11, 3, 23, 59, 59, tzinfo=KST).timestamp())


def calls(guard, n):
    for _ in range(n):
        guard.record_call()


def test_multiplier_from_80_percent(late_clock):
    g = QuotaGuard("t", 1000, late_clock)
    calls(g, 799)
    assert g.ttl_multiplier() == 1.0
    g.record_call()
    assert g.ttl_multiplier() == 3.0


def test_blocks_at_limit(late_clock):
    g = QuotaGuard("t", 1000, late_clock)
    calls(g, 999)
    assert g.can_call()
    g.record_call()
    assert not g.can_call()


def test_resets_at_kst_midnight(late_clock):
    g = QuotaGuard("t", 1000, late_clock)
    calls(g, 1000)
    g.record_error()
    g.mark_exhausted()
    late_clock.advance(1)
    assert (g.calls_today, g.errors_today, g.can_call(), g.ttl_multiplier()) == (0, 0, True, 1.0)


def test_mark_exhausted_blocks_until_midnight(late_clock):
    g = QuotaGuard("t", 1000, late_clock)
    g.mark_exhausted()
    assert not g.can_call()


def test_snapshot(late_clock):
    g = QuotaGuard("t", 1000, late_clock)
    g.record_call()
    assert g.snapshot() == {"callsToday": 1, "dailyLimit": 1000, "errorsToday": 0}


def test_app_has_guards_in_spec_order_with_settings_limits(services):
    assert list(services.quotas) == [
        "seoulRealtime",
        "seoulTimetable",
        "kakaoRoute",
    ]
    assert services.quotas["kakaoRoute"].daily_limit == 1000
