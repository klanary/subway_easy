"""테스트 공용 도구. 테스트 파일은 `from tests.helpers import ...`로 가져다 쓴다."""

import copy
import json
from collections.abc import Callable
from pathlib import Path

import httpx

FIXTURES_DIR = Path(__file__).parent / "fixtures"


class FakeClock:
    """호출하면 현재 유닉스 시각을 돌려주는 시계. 테스트가 시간을 직접 움직인다."""

    def __init__(self, start: float):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, sec: float) -> None:
        self.now += sec

    def set(self, ts: float) -> None:
        self.now = ts


def load_fixture(name: str) -> tuple[int, dict]:
    """tests/fixtures/의 {"status", "body"} 샘플을 읽는다. 이름의 .json은 생략할 수 있다."""
    if not name.endswith(".json"):
        name += ".json"
    data = json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))
    return data["status"], data["body"]


def fixture_response(name: str, mutate: Callable[[dict], None] | None = None) -> httpx.Response:
    """샘플로 만든 응답. 본문을 복사한 뒤 mutate를 적용하므로 원본 샘플은 바뀌지 않는다."""
    status, body = load_fixture(name)
    body = copy.deepcopy(body)
    if mutate is not None:
        mutate(body)
    return httpx.Response(status, json=body)
