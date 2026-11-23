"""외부 API별 일일 호출 한도 보호 (스펙 6.4).

- 한도의 80% 이상: 해당 API 캐시의 유효 시간을 3배로 늘린다(ttl_multiplier).
- 한도 도달 또는 외부 API의 한도 초과 응답(mark_exhausted): 그날은 외부 호출을 멈춘다.
- 한국 시간 00:00에 초기화한다. 카운터는 메모리에 있어 서버가 재시작되면 0부터 다시 센다.
"""

from collections.abc import Callable
from datetime import date

from app.core.clock import kst_from_epoch


class QuotaGuard:
    def __init__(self, name: str, daily_limit: int, clock: Callable[[], float]):
        self.name = name
        self.daily_limit = daily_limit
        self._clock = clock
        self._day = self._today()
        self._calls = 0
        self._errors = 0
        self._exhausted = False

    def _today(self) -> date:
        return kst_from_epoch(self._clock()).date()

    def _roll_over(self) -> None:
        """한국 시간 날짜가 바뀌었으면 호출 수·오류 수·소진 표시를 초기화한다."""
        today = self._today()
        if today != self._day:
            self._day = today
            self._calls = 0
            self._errors = 0
            self._exhausted = False

    @property
    def calls_today(self) -> int:
        self._roll_over()
        return self._calls

    @property
    def errors_today(self) -> int:
        self._roll_over()
        return self._errors

    def can_call(self) -> bool:
        self._roll_over()
        return not self._exhausted and self._calls < self.daily_limit

    def record_call(self) -> None:
        self._roll_over()
        self._calls += 1

    def record_error(self) -> None:
        self._roll_over()
        self._errors += 1

    def mark_exhausted(self) -> None:
        """외부 API가 한도 초과로 응답했다.

        서버 재시작으로 카운터가 0이 되어도 이 표시로 그날의 호출을 멈출 수 있다.
        """
        self._roll_over()
        self._exhausted = True

    def ttl_multiplier(self) -> float:
        self._roll_over()
        return 3.0 if self._calls >= 0.8 * self.daily_limit else 1.0

    def snapshot(self) -> dict[str, int]:
        self._roll_over()
        return {
            "callsToday": self._calls,
            "dailyLimit": self.daily_limit,
            "errorsToday": self._errors,
        }
