"""받아 둔 서울시 공식 시간표 사본(수동 검토 2026-11-22).

서울시 시간표 API가 2026-11-22 새벽부터 1호선 102개 역을 모두 INFO-200(데이터 없음)으로 준다.
그 전날(11-21) VM이 받아 둔 캐시에서 뽑은 사본(`ml/official_snapshot.py`)을 두고,
서울시가 빈 응답을 줄 때만 대신 쓴다. 서울시가 다시 행을 주면 그쪽이 먼저다.
"""

import gzip
import json
import logging
from pathlib import Path

logger = logging.getLogger("app.timetable")


class OfficialSnapshot:
    def __init__(self, tables: dict[str, list[dict]], taken_on: str | None = None):
        self._tables = tables
        self.taken_on = taken_on

    @classmethod
    def load(cls, path: str | Path) -> "OfficialSnapshot":
        """파일이 없거나 읽지 못하면 빈 사본(서버는 그대로 뜬다)."""
        try:
            data = json.loads(gzip.decompress(Path(path).read_bytes()))
        except (OSError, ValueError) as e:
            logger.warning("공식 시간표 사본을 읽지 못했어요(%s): %s", path, e)
            return cls({})
        return cls(data.get("tables") or {}, data.get("takenOn"))

    def rows(self, station_cd: str, week_tag: int, inout_tag: int) -> list[dict]:
        return self._tables.get(f"{station_cd}|{week_tag}|{inout_tag}") or []
