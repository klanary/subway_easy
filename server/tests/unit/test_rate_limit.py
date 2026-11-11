from app.core.rate_limit import SlidingWindowRateLimiter
from tests.helpers import FakeClock


def test_allows_limit_then_blocks_then_recovers():
    clock = FakeClock(0.0)
    rl = SlidingWindowRateLimiter(60, clock=clock)
    assert all(rl.allow("a") for _ in range(60)) and not rl.allow("a")
    clock.advance(60.01)
    assert rl.allow("a")


def test_keys_are_independent():
    rl = SlidingWindowRateLimiter(1, clock=FakeClock(0.0))
    assert rl.allow("a") and rl.allow("b") and not rl.allow("a")


def test_idle_keys_are_dropped():
    clock = FakeClock(0.0)
    rl = SlidingWindowRateLimiter(5, clock=clock)
    for ip in ("a", "b", "c"):
        rl.allow(ip)
    clock.advance(61)
    rl.allow("d")
    assert rl.tracked_keys() == 1
