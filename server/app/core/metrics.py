from dataclasses import dataclass, field
from datetime import datetime


@dataclass
class Metrics:
    """/v1/metrics가 보여주는 서버 지표. 메모리에만 있어 서버가 재시작되면 초기화된다."""

    since: datetime
    requests_total: int = 0
    rate_limited: int = 0
    cache_hits: int = 0
    cache_misses: int = 0
    unmatched_station_names: set[str] = field(default_factory=set)

    def hit_rate(self) -> float:
        lookups = self.cache_hits + self.cache_misses
        if lookups == 0:
            return 0.0
        return round(self.cache_hits / lookups, 2)
