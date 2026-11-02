# P2. 중계 서버 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스펙 6장의 중계 서버를 경유지 합성(⑧)과 실시간 결합(⑩)을 뺀 범위로 완성해 공개 URL에 배포한다. (⑧·⑩의 서버 부분은 P4)

**Architecture:** FastAPI 앱 하나. `clients`는 외부 API의 요청·응답 형식만 다루고 `services`가 서버 형식으로 바꾼다. 모든 외부 호출은 `call_upstream`(시간 제한·재시도·한도 보호)과 `TtlCache`(유효 시간·동시 요청 합치기·만료 데이터 보관)를 거친다. 역 기준 데이터는 빌드 스크립트가 만든 읽기 전용 SQLite를 시작할 때 메모리 인덱스로 올린다. 의존 객체는 `create_app`이 만들어 `app.state.services`(`AppServices`)에 담는다.

**Tech Stack:** Python 3.12, FastAPI, uvicorn, httpx(AsyncClient), pydantic v2, pydantic-settings, sqlite3, pytest, pytest-asyncio, respx, ruff, Docker, Render

**Spec:** `docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md` (6장 전체 — 6.6의 경유지 합성·실시간 결합 제외, 5.4, 9.1, 9.3, 10.1)

**기간:** 11/4 ~ 11/8 · **선행:** P1 완료(`tests/fixtures/` 샘플, `data/raw/station_master.csv`, `app/clients/endpoints.py`, `api-verification.md` §1~§3) · **점검(스펙 11절):** 배포 URL의 `/docs`에서 모든 엔드포인트 동작

**브랜치(스펙 12절, 기능 단위 PR):** `feat/server-stations`(Task 1~4) → `feat/server-upstream`(5~7) → `feat/server-arrivals`(8~9) → `feat/server-places`(10) → `feat/server-routes`(11~12) → `feat/server-ops`(13~16)

## Global Constraints

- 서버 명령은 `server/`에서, git 명령은 저장소 루트에서 실행한다.
- 테스트 중 실제 외부 API를 호출하지 않는다. respx로 가로채거나 `FixtureTransport`를 쓴다. 샘플은 `tests/fixtures/`의 `{"status": ..., "body": ...}` 형식이다. (9.1)
- 좌표는 요청·응답 모두 위도, 경도 순서다. 카카오의 `x=경도, y=위도` 변환은 `app/clients/kakao_*.py` 안에서만 한다. (6.2)
- 응답 JSON 키는 camelCase(`ApiModel`의 별칭 생성기)다. 시각은 한국 시간 ISO 8601(`+09:00`)이며 초 단위까지만 쓴다. 한국 시간은 고정 오프셋 `KST = timezone(timedelta(hours=9))`로 계산한다(서머타임이 없어 정확하고 tzdata가 필요 없다).
- 오류 응답은 `{"error": {"code", "reason"(있을 때만), "message"}}`이며 코드와 상태는 6.3 표를 따른다. 파라미터 검증 실패도 400 `INVALID_REQUEST`다. (6.3)
- 외부 호출은 시도마다 5초 제한, 실패하면 0.5초 뒤 1회 재시도한다. (6.3)
- 캐시 유효 시간: 실시간 도착 20초, 경로 600초, 장소 검색 86,400초, 좌표→주소 86,400초. 만료된 항목은 86,400초 더 보관해 장애 때 `stale`로 쓴다. (6.4)
- 일일 한도 기본값: 서울 실시간 1,000 / 카카오 경로 1,000 / 키워드 100,000 / 좌표→주소 10,000(환경 변수로 변경). 80% 이상이면 해당 캐시 유효 시간 ×3, 한도에 닿으면 호출 중단, 한국 시간 00:00에 초기화. (6.4)
- 요청 제한은 IP별 분당 60회, `X-Forwarded-For`의 첫 IP 기준, `/health` 제외. (6.8)
- 키는 환경 변수에만 둔다. 키와 요청 헤더는 로그에 남기지 않고, 로그 속 좌표는 소수점 3자리까지만 남긴다. (5.4, 6.8)
- `clients`는 외부 형식만 다루고 변환은 `services`가 한다. (6.9)
- 작업마다 커밋에 학습 노트 초안 `docs/learning/NN-주제.md`(12절 형식)를 넣는다.

## Review Focus

1. 운행이 끝난 심야에 서울시 API가 "데이터 없음"을 돌려줌 → 502가 아니라 200과 빈 `groups`여야 한다(앱은 "도착 정보 없음"). (Task 9 `test_nodata_returns_empty_groups`)
2. 서울시 API는 한도 초과 같은 오류도 HTTP 200 본문으로 돌려줌 → 빈 도착 정보로 캐시되면 안 되고 503 `QUOTA_EXCEEDED`와 한도 소진 처리가 돼야 한다. (Task 9 `test_seoul_quota_error_in_200_body`)
3. httpx의 기본 INFO 로그가 서울시 키가 든 URL을 그대로 남김 → 어떤 로그 기록에도 키가 없어야 한다. (Task 7 `test_seoul_key_never_logged`)
4. 막 배포한(요청이 하나도 없는) 서버의 `/v1/metrics` → 0으로 나누기 오류 없이 `hitRate` 0.0이어야 한다(부하 테스트가 맨 먼저 부른다). (Task 14 `test_metrics_on_fresh_server`)
5. 같은 키로 기다리던 요청 중 첫 요청의 클라이언트가 연결을 끊음 → 함께 기다리던 나머지 요청은 정상 응답을 받아야 한다. (Task 5 `test_cancelled_waiter_does_not_cancel_shared_fetch`)

---

### Task 1: 오류 형식, 공통 모델, 앱 조립

**Files:**
- Create: `server/app/core/clock.py`, `server/app/core/errors.py`, `server/app/core/container.py`, `server/app/models/__init__.py`, `server/app/models/schemas.py`, `server/tests/helpers.py`, `server/tests/integration/test_errors.py`
- Modify: `server/app/main.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: P1 `Settings`, `create_app`, 픽스처 `test_settings`
- Produces (`app/core/clock.py`): `KST`, `kst_from_epoch(ts: float) -> datetime`(마이크로초 버림), `now_kst(clock: Callable[[], float]) -> datetime`
- Produces (`app/core/errors.py`): `AppError(Exception)` — 클래스 속성 `status: int`, `code: str`; `__init__(self, reason: str | None = None, message: str | None = None)`, 속성 `reason`, `message`(주지 않으면 아래 표). 하위 클래스 `InvalidRequest`(400 `INVALID_REQUEST`), `NotFound`(404 `NOT_FOUND`), `NoRoute`(404 `NO_ROUTE`), `RateLimited`(429 `RATE_LIMITED`), `UpstreamFailure`(아래 셋의 부모, 직접 쓰지 않음), `UpstreamError`(502 `UPSTREAM_ERROR`), `QuotaExceeded`(503 `QUOTA_EXCEEDED`), `UpstreamTimeout`(504 `UPSTREAM_TIMEOUT`). `install_error_handlers(app: FastAPI) -> None` — `AppError`와 `RequestValidationError`(→ `InvalidRequest(reason="PARAMS")`)를 6.3 형식으로 바꾼다.
- Produces (`app/models/schemas.py`): `class ApiModel(BaseModel)` — `model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)`. 이후 모든 응답 모델의 부모.
- Produces (`app/core/container.py`): `@dataclass class AppServices` — 이 작업에서는 `settings: Settings`, `clock: Callable[[], float]`. 이후 작업이 필드를 하나씩 더한다. `build_services(settings, clock, transport: httpx.AsyncBaseTransport | None) -> AppServices`, FastAPI 의존성 `get_services(request: Request) -> AppServices`
- Produces: `create_app(settings: Settings | None = None, *, clock: Callable[[], float] = time.time, transport: httpx.AsyncBaseTransport | None = None) -> FastAPI` (서비스는 `app.state.services`)
- Produces (`tests/helpers.py` — 테스트 파일이 `from tests.helpers import ...`로 가져다 쓴다): `FakeClock(start: float)`(호출하면 현재 값, `advance(sec)`, `set(ts)`), `load_fixture(name) -> tuple[int, dict]`, `fixture_response(name, mutate: Callable[[dict], None] | None = None) -> httpx.Response`(본문을 복사해 `mutate`를 적용한 응답)
- Produces (`tests/conftest.py`): 픽스처 `clock`(2026-11-03 08:00:00 KST에서 시작하는 `FakeClock`), `app`(`create_app(test_settings, clock=clock)`), `client`(`app` 위의 AsyncClient), `services`(`app.state.services`)

사용자 문구(`message`) 표 — 앱이 그대로 보여준다:

| code | reason | message |
|---|---|---|
| INVALID_REQUEST | (그 밖) | 요청 형식이 올바르지 않아요. |
| INVALID_REQUEST | EQUAL_POINTS | 출발지와 도착지가 같아요. |
| NOT_FOUND | | 역 정보를 찾을 수 없어요. |
| NO_ROUTE | NO_START_STOP | 출발지 근처에서 탈 수 있는 대중교통이 없어요. |
| NO_ROUTE | NO_END_STOP | 도착지 근처에서 내릴 수 있는 대중교통이 없어요. |
| NO_ROUTE | NO_RESULTS | 대중교통 경로를 찾지 못했어요. |
| RATE_LIMITED | | 요청이 너무 많아요. 잠시 후 다시 시도해 주세요. |
| UPSTREAM_ERROR | | 정보를 가져오지 못했어요. 잠시 후 다시 시도해 주세요. |
| QUOTA_EXCEEDED | | 오늘 조회할 수 있는 횟수를 모두 썼어요. 내일 다시 시도해 주세요. |
| UPSTREAM_TIMEOUT | | 응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요. |

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/integration/test_errors.py
@pytest.fixture
async def probe_client(test_settings, clock):
    app = create_app(test_settings, clock=clock)

    @app.get("/_probe")
    async def probe(n: int, kind: str = "ok"):
        errors = {"notfound": NotFound(), "noroute": NoRoute("NO_START_STOP"), "quota": QuotaExceeded()}
        if kind in errors:
            raise errors[kind]
        return {"n": n}

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
        yield c

async def test_missing_param_is_400_invalid_request(probe_client):
    r = await probe_client.get("/_probe")
    assert r.status_code == 400
    assert r.json() == {"error": {"code": "INVALID_REQUEST", "reason": "PARAMS",
                                  "message": "요청 형식이 올바르지 않아요."}}

async def test_app_error_uses_spec_format(probe_client):
    r = await probe_client.get("/_probe", params={"n": 1, "kind": "noroute"})
    assert r.status_code == 404
    assert r.json()["error"] == {"code": "NO_ROUTE", "reason": "NO_START_STOP",
                                 "message": "출발지 근처에서 탈 수 있는 대중교통이 없어요."}

async def test_reason_omitted_when_absent(probe_client):
    r = await probe_client.get("/_probe", params={"n": 1, "kind": "quota"})
    assert r.status_code == 503 and "reason" not in r.json()["error"]

def test_kst_from_epoch_truncates_and_uses_plus_nine():
    t = kst_from_epoch(datetime(2026, 11, 3, 8, 0, 1, 750000, tzinfo=KST).timestamp())
    assert t.isoformat() == "2026-11-03T08:00:01+09:00"
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_errors.py -q` / Expected: FAIL (`ImportError`)
- [ ] **Step 3: 구현** — 위 Interfaces 그대로. `create_app`은 `install_error_handlers`를 부르고 `app.state.services = build_services(...)`를 둔다.
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit**

```bash
git add server docs/learning
git commit -m "feat(server): 오류 형식, 공통 모델, 앱 조립"
```

---

### Task 2: 이름·호선 정규화와 초성

**Files:**
- Create: `server/app/services/__init__.py`, `server/app/services/normalize.py`, `server/app/services/geo.py`, `server/tests/unit/test_normalize.py`

**Interfaces:**
- Produces (`normalize.py`, 빌드 스크립트와 서버가 함께 쓴다 — 6.5):
  - `@dataclass(frozen=True) class NormalizedName: id: str; aliases: tuple[str, ...]`
  - `normalize_station_name(raw: str) -> NormalizedName` — 규칙 1 → 3 → 2. 별칭은 괄호 안 이름(규칙 1·2 적용)과 원래 전체 이름(규칙 1만 적용) 순서. 괄호가 없으면 별칭 없음.
  - `normalize_line(raw: str) -> str` — 순서: 공백 모두 제거 → `LINE_ALIASES` 정확히 일치 → 앞의 `수도권`·`서울지하철`·`서울` 제거 → `인천`으로 시작하면 그대로 → `^0*(\d+)호선$`이면 `f"{n}호선"` → 그 밖은 그대로
  - `LINE_ALIASES: dict[str, str]` 초기값 `{"공항철도1호선": "공항철도", "인천국제공항철도": "공항철도", "경의선": "경의중앙선", "중앙선": "경의중앙선", "경의·중앙선": "경의중앙선", "분당선": "수인분당선", "수인선": "수인분당선", "수인·분당선": "수인분당선", "우이신설경전철": "우이신설선", "신림경전철": "신림선"}` — P1 §2·§3의 호선 표기 목록으로 보탠다.
  - `SUBWAY_ID_TO_LINE: dict[str, str]` 초기값(P1 §1 코드표로 확정): `1001`~`1009` → `1호선`~`9호선`, `1061`·`1063` → `경의중앙선`, `1065` → `공항철도`, `1067` → `경춘선`, `1075` → `수인분당선`, `1077` → `신분당선`, `1081` → `경강선`, `1092` → `우이신설선`, `1093` → `서해선`, `1032` → `GTX-A`
  - `CHOSUNG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"`, `to_chosung(text: str) -> str`(가~힣은 초성으로, 그 밖의 글자는 그대로), `is_chosung_query(q: str) -> bool`(공백을 뺀 뒤 비어 있지 않고 모든 글자가 `CHOSUNG`에 속함)
- Produces (`geo.py`): `haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float` (지구 반지름 6,371,000m)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
@pytest.mark.parametrize("raw, id_, aliases", [
    ("  홍대입구역 ", "홍대입구", ()),
    ("서울역", "서울", ()),
    ("역", "역", ()),                                   # 제거 후 1자 이하면 그대로
    ("디지털  미디어시티", "디지털 미디어시티", ()),
    ("총신대입구(이수)", "총신대입구", ("이수", "총신대입구(이수)")),
    ("가나역(다라역)", "가나", ("다라", "가나역(다라역)")),  # 괄호를 먼저 떼야 끝의 '역'이 제거됨
])
def test_normalize_station_name(raw, id_, aliases):
    assert normalize_station_name(raw) == NormalizedName(id_, aliases)

@pytest.mark.parametrize("raw, expected", [
    ("2호선", "2호선"), ("수도권 2호선", "2호선"), ("서울 2호선", "2호선"), ("02호선", "2호선"),
    ("공항철도1호선", "공항철도"), ("인천1호선", "인천1호선"), ("인천 1호선", "인천1호선"),
    ("분당선", "수인분당선"), ("신분당선", "신분당선"), ("서해선", "서해선"),
])
def test_normalize_line(raw, expected):
    assert normalize_line(raw) == expected

def test_subway_ids_map_to_normalized_lines():
    assert SUBWAY_ID_TO_LINE["1002"] == "2호선"
    assert all(normalize_line(v) == v for v in SUBWAY_ID_TO_LINE.values())

@pytest.mark.parametrize("text, expected", [("홍대입구", "ㅎㄷㅇㄱ"), ("을지로3가", "ㅇㅈㄹ3ㄱ"), ("DMC", "DMC")])
def test_to_chosung(text, expected):
    assert to_chosung(text) == expected

@pytest.mark.parametrize("q, expected", [("ㅎㄷㅇㄱ", True), ("ㅎㄷ ㅇㄱ", True), ("ㅎㄷ입구", False),
                                         ("ㄳ", False), ("  ", False)])
def test_is_chosung_query(q, expected):
    assert is_chosung_query(q) is expected

def test_haversine_one_hundredth_degree_latitude():
    assert abs(haversine_m(37.0, 127.0, 37.01, 127.0) - 1112) < 2
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_normalize.py -q` / Expected: FAIL (`ModuleNotFoundError`)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 역·호선 이름 정규화와 초성 변환"`

---

### Task 3: 역 기준 데이터 빌드

**Files:**
- Create: `server/scripts/build_stations.py`, `server/tests/fixtures/master_sample.csv`, `server/tests/unit/test_build_stations.py`, `server/data/stations.sqlite`(생성물, 커밋)

**Interfaces:**
- Consumes: Task 2의 `normalize_station_name`, `normalize_line`, `to_chosung`, `haversine_m`; P1의 `data/raw/station_master.csv`와 §2
- Produces: SQLite 스키마(6.5)

```sql
CREATE TABLE stations (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, lat REAL NOT NULL,
                       lng REAL NOT NULL, chosung TEXT NOT NULL, api_name TEXT NOT NULL);
CREATE TABLE station_lines (station_id TEXT NOT NULL, line_name TEXT NOT NULL, master_id TEXT NOT NULL,
                            PRIMARY KEY (station_id, line_name, master_id));
CREATE TABLE aliases (alias TEXT PRIMARY KEY, station_id TEXT NOT NULL);
```

- Produces (`scripts/build_stations.py`):
  - `COLUMNS = {"master_id": "역사_ID", "name": "역사명", "line": "호선", "lat": "위도", "lng": "경도"}` (P1 §2 확인값으로 맞춘다)
  - `@dataclass(frozen=True) class MasterRow: master_id: str; name: str; line: str; lat: float; lng: float` (`line`은 정규화된 호선)
  - `read_master_csv(path: Path) -> tuple[list[MasterRow], int]` — `utf-8-sig`로 읽고 실패하면 `cp949`. 좌표가 빈 행은 건너뛰고 그 수를 함께 돌려준다.
  - `API_NAME_EXCEPTIONS: dict[str, str]`(역 id → 조회 이름)과 `SEPARATE_STATIONS: dict[tuple[str, str], str]`((정규화 이름, 호선) → 나눌 역 id) — P1 §2 표의 값. 예상: `SEPARATE_STATIONS = {("양평", "경의중앙선"): "양평(경의중앙선)"}`
  - `class BuildError(Exception)`, `@dataclass class BuildResult: stations: list[StationRow]; lines: list[StationLineRow]; aliases: list[tuple[str, str]]` (`StationRow(id, display_name, lat, lng, chosung, api_name)`, `StationLineRow(station_id, line_name, master_id)`)
  - `build(rows, separate=SEPARATE_STATIONS, api_names=API_NAME_EXCEPTIONS) -> BuildResult` — 정규화 id로 묶고 `(id, 호선)`이 `separate`에 있으면 그 id로 보낸다. 한 역 안의 호선 좌표 쌍 중 하나라도 1,000m를 넘으면 `BuildError`(메시지에 이름 포함). `display_name`은 그 역 첫 행의 원래 이름(규칙 1만 적용), `lat`·`lng`는 평균(소수점 6자리), `chosung`은 정규화 이름의 초성, `api_name`은 `api_names.get(역 id, 정규화 이름)`. 별칭은 각 행의 별칭 중 어떤 역 id와도 같지 않은 것만, 먼저 나온 것이 이긴다. 결과는 id·(역, 호선, 마스터 id)·별칭 순으로 정렬한다.
  - `write_sqlite(result: BuildResult, out: Path) -> None` — 기존 파일을 지우고 새로 쓴다.
  - CLI: `python -m scripts.build_stations data/raw/station_master.csv data/stations.sqlite` → 역·호선·별칭·건너뛴 행 수 출력

`tests/fixtures/master_sample.csv` (합성 데이터, 열 이름은 `COLUMNS`와 같게):

```csv
역사_ID,역사명,호선,위도,경도
0151,홍대입구,2호선,37.557192,126.925381
1264,홍대입구,경의중앙선,37.557641,126.926804
4207,홍대입구,공항철도1호선,37.557335,126.926214
0433,총신대입구(이수),4호선,37.486263,126.981989
2734,이수,7호선,37.485196,126.981605
2523,양평,5호선,37.525706,126.886516
1219,양평,경의중앙선,37.492832,127.491814
0150,서울역,1호선,37.554648,126.972559
0228,서울대입구(관악구청),2호선,37.481247,126.952739
1314,서울숲,수인분당선,37.543617,127.044707
0214,강변(동서울터미널),2호선,37.535095,127.094681
9999,좌표없는역,2호선,,
```

- [ ] **Step 1: 실패하는 테스트 작성**

```python
SAMPLE = Path(__file__).parents[1] / "fixtures" / "master_sample.csv"
SEP = {("양평", "경의중앙선"): "양평(경의중앙선)"}

def built(separate=SEP, api_names=None):
    rows, _ = read_master_csv(SAMPLE)
    return build(rows, separate=separate, api_names=api_names or {})

def station(result, id_):
    return next(s for s in result.stations if s.id == id_)

def test_same_name_lines_grouped_and_averaged():
    r = built()
    assert sorted(l.line_name for l in r.lines if l.station_id == "홍대입구") == ["2호선", "경의중앙선", "공항철도"]
    assert station(r, "홍대입구").lat == pytest.approx((37.557192 + 37.557641 + 37.557335) / 3, abs=1e-6)

def test_far_apart_same_name_split_by_table():
    r = built()
    assert station(r, "양평").api_name == station(r, "양평(경의중앙선)").api_name == "양평"
    assert station(r, "양평(경의중앙선)").display_name == "양평"

def test_unlisted_far_apart_same_name_fails():
    with pytest.raises(BuildError, match="양평"):
        built(separate={})

def test_aliases_skip_station_ids():
    aliases = dict(built().aliases)
    assert aliases["총신대입구(이수)"] == "총신대입구" and aliases["동서울터미널"] == "강변"
    assert "이수" not in aliases            # 7호선 '이수' 역의 id와 겹침

def test_api_name_exception_applied():
    assert station(built(api_names={"서울": "서울역"}), "서울").api_name == "서울역"

def test_rows_without_coords_skipped():
    rows, skipped = read_master_csv(SAMPLE)
    assert skipped == 1 and all(r.name != "좌표없는역" for r in rows)

def test_reads_cp949(tmp_path):
    p = tmp_path / "m.csv"
    p.write_bytes(SAMPLE.read_text(encoding="utf-8").encode("cp949"))
    assert read_master_csv(p) == read_master_csv(SAMPLE)

def test_output_is_deterministic(tmp_path):
    a, b = tmp_path / "a.sqlite", tmp_path / "b.sqlite"
    write_sqlite(built(), a)
    write_sqlite(built(), b)
    dump = lambda p: "\n".join(sqlite3.connect(p).iterdump())
    assert dump(a) == dump(b)
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_build_stations.py -q` / Expected: FAIL (`ModuleNotFoundError`)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: 실제 데이터 빌드**

Run: `python -m scripts.build_stations data/raw/station_master.csv data/stations.sqlite && sqlite3 data/stations.sqlite "select id, display_name, api_name from stations where id in ('홍대입구','서울','양평','양평(경의중앙선)')"`
Expected: 개수 출력, 네 행 출력. `BuildError`가 나면 그 이름을 `SEPARATE_STATIONS`와 스펙 부록 B·`api-verification.md` §2에 더하고 다시 실행한다.

- [ ] **Step 6: Commit** — `git add server docs && git commit -m "feat(server): 역 기준 데이터 빌드 스크립트와 stations.sqlite"`

---

### Task 4: 역 인덱스와 역 검색 API

**Files:**
- Create: `server/app/services/station_index.py`, `server/app/api/stations.py`, `server/tests/unit/test_station_index.py`, `server/tests/integration/test_stations_search.py`
- Modify: `server/app/models/schemas.py`, `server/app/core/container.py`, `server/app/main.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: Task 3의 SQLite, Task 2의 정규화 함수
- Produces (`station_index.py`):
  - `@dataclass(frozen=True) class Station: id: str; name: str; lines: tuple[str, ...]; lat: float; lng: float; chosung: str; api_name: str` (`name` = `display_name`, `lines`는 숫자 호선을 번호순으로 먼저, 나머지는 이름순)
  - `class StationIndex`: `load(cls, db_path: str | Path) -> StationIndex`, `get(station_id: str) -> Station | None`, `resolve(raw_name: str, line: str | None = None) -> Station | None`, `same_station(a: str, b: str) -> bool`, `search(q: str, limit: int) -> list[Station]`, `nearest(lat: float, lng: float, max_m: float = 2000) -> tuple[Station, int] | None`(거리는 정수 m), `shares_api_name(station: Station) -> bool`
  - `resolve` 규칙: 입력을 `normalize_station_name`으로 바꾼 뒤 그 id, 이어서 각 별칭 순으로 (1) 정규화한 `display_name`이 같은 역들(나뉜 역 포함, id순) (2) 별칭 표를 찾는다. 후보가 여럿이고 `line`이 주어지면 그 호선을 가진 역, 아니면 첫 후보.
  - `same_station`: 둘 다 `resolve`되면 id 비교, 아니면 정규화 이름 비교
  - `search` 규칙(6.5): `is_chosung_query`면 공백을 뺀 검색어를 `chosung`에, 아니면 정규화한 검색어를 각 역의 검색 키(정규화 이름과 별칭들)에 비교해 가장 좋은 등급(0 완전 일치, 1 앞부분, 2 포함)을 쓴다. 정렬 키 `(등급, -len(lines), name)`.
- Produces (`schemas.py`): `StationItem(id, name, lines: list[str], lat, lng)`, `StationSearchResponse(items: list[StationItem])`
- Produces: `GET /v1/stations/search?q=&limit=` — `q`는 앞뒤 공백을 뺀 뒤 1~50자, `limit` 1~50(기본 20). 어기면 400 `INVALID_REQUEST`(reason `PARAMS`).
- Produces: `AppServices.stations: StationIndex` (`settings.stations_db_path`에서 읽음), conftest 픽스처 `sample_db`(`master_sample.csv`를 Task 3의 `SEP` 표로 `tmp_path`에 빌드한 경로), `sample_index`(그 DB의 인덱스), `sample_client`(`stations_db_path=sample_db`인 앱의 클라이언트), `real_index`(`data/stations.sqlite`)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/unit/test_station_index.py
def test_search_ranks_exact_prefix_contains(sample_index):
    assert [s.id for s in sample_index.search("서울", 20)] == ["서울", "서울대입구", "서울숲", "강변"]

def test_search_tie_breaks_by_line_count_then_name(sample_index):
    assert [s.id for s in sample_index.search("입구", 20)] == ["홍대입구", "서울대입구", "총신대입구"]

def test_chosung_search_and_limit(sample_index):
    assert [s.id for s in sample_index.search("ㅎㄷㅇㄱ", 20)] == ["홍대입구"]
    assert len(sample_index.search("ㅅ", 2)) == 2

@pytest.mark.parametrize("raw, line, expected", [
    ("홍대입구역", None, "홍대입구"), ("이수", None, "이수"), ("총신대입구(이수)", None, "총신대입구"),
    ("동서울터미널", None, "강변"), ("서울역", "1호선", "서울"),
    ("양평", "경의중앙선", "양평(경의중앙선)"), ("양평", "5호선", "양평"), ("양평", None, "양평"),
])
def test_resolve(sample_index, raw, line, expected):
    assert sample_index.resolve(raw, line).id == expected

def test_resolve_unknown_is_none(sample_index):
    assert sample_index.resolve("없는역") is None

def test_same_station(sample_index):
    assert sample_index.same_station("서울역", "서울")
    assert not sample_index.same_station("이수", "총신대입구")

def test_nearest_within_2km(sample_index):
    station, dist = sample_index.nearest(37.5575, 126.9260)
    assert station.id == "홍대입구" and dist < 200
    assert sample_index.nearest(37.0, 125.0) is None

def test_shares_api_name(sample_index):
    assert sample_index.shares_api_name(sample_index.get("양평"))
    assert not sample_index.shares_api_name(sample_index.get("홍대입구"))

# tests/integration/test_stations_search.py
async def test_search_endpoint_chosung(client):
    r = await client.get("/v1/stations/search", params={"q": "ㅎㄷㅇㄱ"})
    item = r.json()["items"][0]
    assert r.status_code == 200 and item["id"] == "홍대입구" and "2호선" in item["lines"]
    assert set(item) == {"id", "name", "lines", "lat", "lng"}

@pytest.mark.parametrize("params", [{"q": ""}, {"q": "   "}, {"q": "홍대", "limit": 0}, {"q": "홍대", "limit": 51}])
async def test_search_rejects_bad_params(client, params):
    r = await client.get("/v1/stations/search", params=params)
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"

async def test_search_default_limit_is_20(client):
    assert len((await client.get("/v1/stations/search", params={"q": "ㅅ"})).json()["items"]) == 20
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_station_index.py tests/integration/test_stations_search.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 역 인덱스와 역 검색 API"`

---

### Task 5: TTL 캐시와 지표 카운터

**Files:**
- Create: `server/app/cache/__init__.py`, `server/app/cache/ttl_cache.py`, `server/app/core/metrics.py`, `server/tests/unit/test_ttl_cache.py`
- Modify: `server/app/core/container.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: Task 1의 `UpstreamFailure`, `FakeClock`
- Produces (`metrics.py`): `@dataclass class Metrics: since: datetime; requests_total: int = 0; rate_limited: int = 0; cache_hits: int = 0; cache_misses: int = 0; unmatched_station_names: set[str] = field(default_factory=set)`, 메서드 `hit_rate() -> float`(조회가 없으면 0.0, 소수점 2자리)
- Produces (`ttl_cache.py`): `@dataclass(frozen=True) class CacheResult(Generic[V]): value: V; status: Literal["hit", "miss", "stale"]; fetched_at: float` + 속성 `stale -> bool`; `class TtlCache(Generic[V])`: `__init__(self, ttl_sec: float, *, clock: Callable[[], float], metrics: Metrics, ttl_multiplier: Callable[[], float] = lambda: 1.0, stale_keep_sec: float = 86_400)`, `async def get_or_fetch(self, key: str, fetch: Callable[[], Awaitable[V]]) -> CacheResult[V]`
- Produces: `AppServices.metrics: Metrics`(`since`는 앱을 만든 시각), conftest 픽스처 `metrics`

동작(테스트가 정하지 않는 부분):
1. 나이 < `ttl_sec × ttl_multiplier()`면 `hit`
2. 같은 키 호출이 진행 중이면 그 작업을 `asyncio.shield`로 기다린다 → `hit`
3. 아니면 `asyncio.create_task(fetch())`를 진행 중 표에 넣고(완료 콜백으로 뺀다) `shield`로 기다린 뒤 저장 → `miss`
4. 기다린 작업이 `UpstreamFailure`를 던지면, 나이 < `ttl × 배율 + stale_keep_sec`인 항목이 있을 때 그 값으로 `stale`, 없으면 다시 던진다. 그 밖의 예외(`NoRoute` 등)는 그대로 던진다.
5. 저장할 때 보관 기간이 지난 항목을 지운다.
6. 호출마다 `hit`이면 `cache_hits`, 그 밖(예외 포함)은 `cache_misses`를 1 늘린다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
@pytest.fixture
def cache(clock, metrics):
    return TtlCache(20, clock=clock, metrics=metrics)

def counting(value="v"):
    calls = []
    async def fetch():
        calls.append(1)
        return value
    return fetch, calls

async def test_hit_within_ttl(cache, clock):
    fetch, calls = counting()
    a = await cache.get_or_fetch("k", fetch); clock.advance(19.9)
    b = await cache.get_or_fetch("k", fetch)
    assert (a.status, b.status, len(calls)) == ("miss", "hit", 1)

async def test_refetch_after_ttl(cache, clock):
    fetch, calls = counting()
    await cache.get_or_fetch("k", fetch); clock.advance(20)
    assert (await cache.get_or_fetch("k", fetch)).status == "miss" and len(calls) == 2

async def test_multiplier_extends_ttl(clock, metrics):
    cache = TtlCache(20, clock=clock, metrics=metrics, ttl_multiplier=lambda: 3.0)
    fetch, calls = counting()
    await cache.get_or_fetch("k", fetch); clock.advance(59)
    assert (await cache.get_or_fetch("k", fetch)).status == "hit"
    clock.advance(1)
    assert (await cache.get_or_fetch("k", fetch)).status == "miss"

async def test_concurrent_calls_share_one_fetch(cache, metrics):
    gate, calls = asyncio.Event(), []
    async def slow():
        calls.append(1); await gate.wait(); return "v"
    tasks = [asyncio.create_task(cache.get_or_fetch("k", slow)) for _ in range(10)]
    await asyncio.sleep(0); gate.set()
    results = await asyncio.gather(*tasks)
    assert len(calls) == 1 and {r.value for r in results} == {"v"}
    assert (metrics.cache_hits, metrics.cache_misses) == (9, 1)

async def test_cancelled_waiter_does_not_cancel_shared_fetch(cache):
    gate = asyncio.Event()
    async def slow():
        await gate.wait(); return "v"
    first = asyncio.create_task(cache.get_or_fetch("k", slow)); await asyncio.sleep(0)
    second = asyncio.create_task(cache.get_or_fetch("k", slow)); await asyncio.sleep(0)
    first.cancel(); gate.set()
    assert (await second).value == "v"

async def test_stale_on_upstream_failure_after_expiry(cache, clock):
    await cache.get_or_fetch("k", counting("old")[0]); clock.advance(25)
    r = await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamError()))
    assert (r.value, r.status, r.stale) == ("old", "stale", True)

async def test_raises_without_entry(cache):
    with pytest.raises(UpstreamTimeout):
        await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamTimeout()))

async def test_stale_entry_dropped_after_keep_period(cache, clock):
    await cache.get_or_fetch("k", counting("old")[0]); clock.advance(20 + 86_400 + 1)
    with pytest.raises(UpstreamError):
        await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamError()))

async def test_non_upstream_errors_are_not_masked(cache, clock):
    await cache.get_or_fetch("k", counting("old")[0]); clock.advance(25)
    with pytest.raises(NoRoute):
        await cache.get_or_fetch("k", AsyncMock(side_effect=NoRoute("NO_RESULTS")))

async def test_failures_are_not_cached(cache):
    with pytest.raises(UpstreamError):
        await cache.get_or_fetch("k", AsyncMock(side_effect=UpstreamError()))
    assert (await cache.get_or_fetch("k", counting()[0])).status == "miss"
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_ttl_cache.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 동시 요청 합치기와 만료 데이터 보관을 갖춘 TTL 캐시"`

---

### Task 6: 호출 한도 보호

**Files:**
- Create: `server/app/cache/quota_guard.py`, `server/tests/unit/test_quota_guard.py`
- Modify: `server/app/core/container.py`

**Interfaces:**
- Produces: `class QuotaGuard`: `__init__(self, name: str, daily_limit: int, clock: Callable[[], float])`, 속성 `name`, `calls_today`, `errors_today`, 메서드 `can_call() -> bool`, `record_call()`, `record_error()`, `mark_exhausted()`, `ttl_multiplier() -> float`(`calls_today >= 0.8 × daily_limit`면 3.0, 아니면 1.0), `snapshot() -> dict[str, int]`(`{"callsToday", "dailyLimit", "errorsToday"}`). 모든 접근 때 `clock()`의 한국 시간 날짜가 바뀌었으면 호출 수·오류 수·소진 표시를 초기화한다.
- Produces: `AppServices.quotas: dict[str, QuotaGuard]` — 키 `seoulRealtime`, `kakaoRoute`, `kakaoKeyword`, `kakaoCoord2Address`(이 순서), 한도는 `Settings`에서

- [ ] **Step 1: 실패하는 테스트 작성**

```python
@pytest.fixture
def late_clock():
    return FakeClock(datetime(2026, 11, 3, 23, 59, 59, tzinfo=KST).timestamp())

def calls(guard, n):
    for _ in range(n):
        guard.record_call()

def test_multiplier_from_80_percent(late_clock):
    g = QuotaGuard("t", 1000, late_clock); calls(g, 799)
    assert g.ttl_multiplier() == 1.0
    g.record_call()
    assert g.ttl_multiplier() == 3.0

def test_blocks_at_limit(late_clock):
    g = QuotaGuard("t", 1000, late_clock); calls(g, 999)
    assert g.can_call()
    g.record_call()
    assert not g.can_call()

def test_resets_at_kst_midnight(late_clock):
    g = QuotaGuard("t", 1000, late_clock); calls(g, 1000); g.record_error(); g.mark_exhausted()
    late_clock.advance(1)
    assert (g.calls_today, g.errors_today, g.can_call(), g.ttl_multiplier()) == (0, 0, True, 1.0)

def test_mark_exhausted_blocks_until_midnight(late_clock):
    g = QuotaGuard("t", 1000, late_clock); g.mark_exhausted()
    assert not g.can_call()

def test_snapshot(late_clock):
    g = QuotaGuard("t", 1000, late_clock); g.record_call()
    assert g.snapshot() == {"callsToday": 1, "dailyLimit": 1000, "errorsToday": 0}
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_quota_guard.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 외부 API 일일 호출 한도 보호"`

---

### Task 7: 외부 호출 공통 처리와 로그 가리기

**Files:**
- Create: `server/app/clients/http.py`, `server/app/core/logging.py`, `server/tests/unit/test_upstream_http.py`
- Modify: `server/app/core/container.py`, `server/app/main.py`

**Interfaces:**
- Consumes: Task 6 `QuotaGuard`, Task 1 오류 클래스, P1 `endpoints.py`
- Produces (`logging.py`): `configure_logging() -> None` — 루트 로거에 한 줄 JSON 포매터(`ts`, `level`, `logger`, `msg`, `extra`로 넘긴 키들), `httpx`·`httpcore` 로거는 WARNING. `create_app`이 부른다.
- Produces (`http.py`):
  - `mask_url(url: str) -> str` — `/api/subway/<키>/` → `/api/subway/***/`, 쿼리의 `x`, `y`, `start_x`, `start_y`, `end_x`, `end_y`, `lat`, `lng` 값을 소수점 3자리로 반올림
  - `async def call_upstream(http: httpx.AsyncClient, guard: QuotaGuard, request: httpx.Request, *, timeout_sec: float, retry_delay_sec: float) -> httpx.Response` — 시도마다 `asyncio.timeout(timeout_sec)` 안에서 보내고, 시도마다 `logger.info("upstream", extra={"api", "url": mask_url(...), "status", "attempt", "duration_ms"})`를 남긴다.

| 상황 | 처리 |
|---|---|
| 시도 전 `guard.can_call()`이 False | `QuotaExceeded` (HTTP 호출 없음) |
| 시도 직전 | `guard.record_call()` |
| 2xx | 응답 반환 |
| 429 | `guard.mark_exhausted()` 후 `QuotaExceeded` (재시도 없음) |
| 그 밖의 4xx | `record_error()` 후 응답 반환 (해석은 각 클라이언트) |
| 5xx·연결 오류 | `record_error()`, `retry_delay_sec` 뒤 1회 재시도, 또 실패하면 `UpstreamError(reason=f"HTTP_{상태}" 또는 "NETWORK")` |
| 시간 초과(`TimeoutError`, `httpx.TimeoutException`) | `record_error()`, 1회 재시도, 마지막 실패가 시간 초과면 `UpstreamTimeout` |

- Produces: `AppServices.http: httpx.AsyncClient`(`transport`가 주어지면 그것을 씀), 앱 종료(lifespan) 때 닫는다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
SEOUL_URL = f"{SEOUL_REALTIME_BASE}/SECRETKEY123/json/realtimeStationArrival/0/50/홍대입구"

@pytest.fixture
def guard(clock):
    return QuotaGuard("seoulRealtime", 1000, clock)

async def send(guard):
    async with httpx.AsyncClient() as http:
        return await call_upstream(http, guard, http.build_request("GET", SEOUL_URL),
                                   timeout_sec=5, retry_delay_sec=0)

def mock(respx_mock, *responses):
    return respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(side_effect=list(responses))

async def test_retry_once_after_timeout(respx_mock, guard):
    route = mock(respx_mock, httpx.ReadTimeout("t"), httpx.Response(200, json={}))
    assert (await send(guard)).status_code == 200 and route.call_count == 2
    assert (guard.calls_today, guard.errors_today) == (2, 1)

async def test_two_timeouts_raise_upstream_timeout(respx_mock, guard):
    mock(respx_mock, httpx.ReadTimeout("t"), httpx.ReadTimeout("t"))
    with pytest.raises(UpstreamTimeout):
        await send(guard)

async def test_two_5xx_raise_upstream_error(respx_mock, guard):
    mock(respx_mock, httpx.Response(500), httpx.Response(503))
    with pytest.raises(UpstreamError):
        await send(guard)

async def test_429_marks_exhausted_without_retry(respx_mock, guard):
    route = mock(respx_mock, httpx.Response(429))
    with pytest.raises(QuotaExceeded):
        await send(guard)
    assert route.call_count == 1 and not guard.can_call()

async def test_exhausted_guard_sends_nothing(respx_mock, guard):
    route = mock(respx_mock, httpx.Response(200, json={}))
    guard.mark_exhausted()
    with pytest.raises(QuotaExceeded):
        await send(guard)
    assert not route.called

async def test_other_4xx_returned_without_retry(respx_mock, guard):
    route = mock(respx_mock, httpx.Response(400, json={"code": "X"}))
    assert (await send(guard)).status_code == 400 and route.call_count == 1

async def test_seoul_key_never_logged(respx_mock, guard, caplog):
    configure_logging()
    caplog.set_level(logging.INFO)
    mock(respx_mock, httpx.ReadTimeout("t"), httpx.Response(200, json={}))
    await send(guard)
    dumped = [json.dumps(r.__dict__, default=str, ensure_ascii=False) for r in caplog.records]
    assert dumped and not any("SECRETKEY123" in d for d in dumped)
    assert any("/api/subway/***/" in d for d in dumped)

def test_mask_url_rounds_coordinates():
    assert mask_url("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=126.924512&y=37.557112") == \
        "https://dapi.kakao.com/v2/local/geo/coord2address.json?x=126.925&y=37.557"
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_upstream_http.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 외부 호출 시간 제한·재시도·한도 처리와 로그 가리기"`

---

### Task 8: 남은 시간 계산과 열차 필드 해석

**Files:**
- Create: `server/app/services/remaining_time.py`, `server/tests/unit/test_remaining_time.py`

**Interfaces:**
- Consumes: Task 1 `KST`; P1 §1의 필드 이름(아래는 스펙 6.7의 이름, 다르면 §1을 따른다)
- Produces:
  - `compute_remaining_sec(item: dict, now: datetime) -> int | None` — `barvlDt > 0`이면 `barvlDt − (now − recptnDt)`(`recptnDt`는 `%Y-%m-%d %H:%M:%S` 뒤에 `.N`이 붙을 수 있고 한국 시간), 아니면 `arvlMsg2`를 아래 순서로 해석, 둘 다 안 되면 `None`, 음수는 0
  - `is_departed(message: str | None) -> bool` — "출발"을 포함하고 "전역"을 포함하지 않음 (6.7 규칙 5)
  - `parse_train_line_nm(value: str | None) -> tuple[str | None, str | None]` — `"OO행 - XX방면"` → `(OO, XX)`, 해석 불가면 `(None, None)`
  - `is_express(item: dict) -> bool` — `btrainSttus`가 비어 있지 않고 `"일반"`이 아님
  - `is_last(item: dict) -> bool` — 막차 표시 필드(예상 `lstcarAt`)가 `"1"`

`arvlMsg2` 해석 순서(전역 패턴을 먼저 봐야 "전역 진입"이 "진입"으로 잡히지 않는다):
1. `\[(\d+)\]번째 전역` → N×120
2. "전역 진입" → 150, "전역 도착" → 120, "전역 출발" → 90
3. `(\d+)분\s*(\d+)초\s*후` → N×60+M
4. `(\d+)분\s*후` → N×60
5. "곧 도착" 또는 "진입" → 30
6. "도착" → 0

- [ ] **Step 1: 실패하는 테스트 작성**

```python
NOW = datetime(2026, 11, 3, 8, 0, 30, tzinfo=KST)

def item(msg="", barvl="0", recptn="2026-11-03 08:00:00"):
    return {"arvlMsg2": msg, "barvlDt": barvl, "recptnDt": recptn}

@pytest.mark.parametrize("msg, expected", [
    ("3분 20초 후 (신촌)", 200), ("4분 후 (아현)", 240), ("곧 도착", 30), ("홍대입구 진입", 30),
    ("홍대입구 도착", 0), ("전역 출발", 90), ("전역 도착", 120), ("전역 진입", 150),
    ("[5]번째 전역 (신촌)", 600), ("운행중", None),
])
def test_message_patterns(msg, expected):
    assert compute_remaining_sec(item(msg), NOW) == expected

def test_barvl_dt_minus_elapsed():
    assert compute_remaining_sec(item("3분 후", barvl="180"), NOW) == 150

def test_recptn_with_fraction():
    assert compute_remaining_sec(item(barvl="180", recptn="2026-11-03 08:00:00.0"), NOW) == 150

def test_negative_clamped_to_zero():
    assert compute_remaining_sec(item(barvl="10"), NOW) == 0

def test_departed_message():
    assert is_departed("홍대입구 출발") and not is_departed("전역 출발") and not is_departed(None)

@pytest.mark.parametrize("value, expected", [("성수행 - 합정방면", ("성수", "합정")), ("", (None, None)), (None, (None, None))])
def test_parse_train_line_nm(value, expected):
    assert parse_train_line_nm(value) == expected

def test_express_and_last():
    assert is_express({"btrainSttus": "급행"}) and not is_express({"btrainSttus": "일반"}) and not is_express({})
    assert is_last({"lstcarAt": "1"}) and not is_last({"lstcarAt": "0"})

def test_all_fixture_messages_are_understood():
    # 실제 샘플의 모든 열차가 남은 시간을 갖거나 '출발' 열차여야 한다. 실패하면 스펙 6.7 표를 먼저 고친다.
    for name in ["seoul_realtime_hongdae.json", "seoul_realtime_cityhall.json", "seoul_realtime_exception.json"]:
        _, body = load_fixture(name)
        for it in body["realtimeArrivalList"]:
            now = datetime.strptime(it["recptnDt"][:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
            assert compute_remaining_sec(it, now) is not None or is_departed(it["arvlMsg2"]), it["arvlMsg2"]
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_remaining_time.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 열차 남은 시간 계산과 안내 문구 해석"`

---

### Task 9: 실시간 도착 API

**Files:**
- Create: `server/app/clients/seoul_subway.py`, `server/app/services/arrival_service.py`, `server/tests/integration/test_arrivals.py`
- Modify: `server/app/api/stations.py`, `server/app/models/schemas.py`, `server/app/core/container.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: Task 4 `StationIndex`, Task 5 `TtlCache`, Task 6 `quotas["seoulRealtime"]`, Task 7 `call_upstream`, Task 8 함수들, Task 2 `SUBWAY_ID_TO_LINE`·`normalize_line`
- Produces (`seoul_subway.py`): `class SeoulSubwayClient`: `__init__(self, http, guard: QuotaGuard, settings: Settings)`, `async def fetch_arrivals(self, api_name: str) -> list[dict]` — URL `f"{SEOUL_REALTIME_BASE}/{키}/json/realtimeStationArrival/0/50/{quote(api_name)}"`. 응답 코드(P1 §1, HTTP 200 본문 포함): 정상 → `realtimeArrivalList`, 데이터 없음(예상 `INFO-200`) → `[]`, 일일 한도 초과(예상 `ERROR-337`) → `guard.mark_exhausted()` 후 `QuotaExceeded`, 그 밖 → `UpstreamError(reason=코드)`
- Produces (`schemas.py`): `Train(destination: str | None, remaining_sec: int | None, message: str, is_last: bool, is_express: bool)`, `ArrivalGroup(line: str, direction: str, towards: str | None, trains: list[Train])`, `ArrivalsResponse(station_id: str, updated_at: datetime, stale: bool, groups: list[ArrivalGroup])`
- Produces (`arrival_service.py`): `class ArrivalService`: `__init__(self, client, index, cache: TtlCache[list[dict]], clock)`, `async def get_arrivals(self, station_id: str, line: str | None = None, towards: str | None = None) -> tuple[ArrivalsResponse, str]`(두 번째 값은 캐시 상태)
  - 없는 역 → `NotFound`(외부 호출 없음). 캐시 키는 `api_name`, 유효 시간 20초 × 서울 한도 배율
  - 그룹 키 `(SUBWAY_ID_TO_LINE[subwayId], updnLine, 방면)`, 처음 나온 순서 유지. `subwayId`가 표에 없으면 그 항목을 빼고 경고 로그
  - "출발" 열차(`is_departed`) 제외 → `remaining_sec` 오름차순(`None`은 뒤) → 앞 2대
  - `index.shares_api_name(station)`이면 그 역 호선의 그룹만 남긴다(6.5)
  - 필터: `line`은 `normalize_line` 후 비교, `towards`는 `index.same_station`으로 비교. `updated_at` = 캐시 항목의 `fetched_at`
- Produces: `GET /v1/stations/{station_id}/arrivals?line=&towards=`, `request.state.cache_status` 설정, `AppServices.arrivals: ArrivalService`
- Produces: conftest 픽스처 `seoul(name) -> respx.Route`(서울시 요청을 그 샘플로 응답), `tests/helpers.py`의 `quota_exceeded_response() -> httpx.Response`(nodata 샘플 본문에서 코드 값만 일일 한도 초과 코드로 바꾼 HTTP 200 응답). `test_arrivals.py`는 자동 사용 픽스처로 `clock.set(홍대입구 샘플 첫 항목의 recptnDt + 10초)`를 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
ARRIVALS = "/v1/stations/{}/arrivals"

async def test_groups_and_trains(client, seoul):
    seoul("seoul_realtime_hongdae.json")
    r = await client.get(ARRIVALS.format("홍대입구")); body = r.json()
    assert r.status_code == 200 and body["stale"] is False and body["updatedAt"].endswith("+09:00")
    assert body["groups"] and {g["line"] for g in body["groups"]} <= {"2호선", "경의중앙선", "공항철도"}
    for g in body["groups"]:
        secs = [t["remainingSec"] for t in g["trains"]]
        assert len(secs) <= 2 and secs == sorted(secs, key=lambda s: (s is None, s or 0))

async def test_filter_by_line_and_towards(client, seoul):
    seoul("seoul_realtime_hongdae.json")
    first = (await client.get(ARRIVALS.format("홍대입구"))).json()["groups"][0]
    r = await client.get(ARRIVALS.format("홍대입구"), params={"line": first["line"], "towards": first["towards"]})
    assert r.json()["groups"] and all((g["line"], g["towards"]) == (first["line"], first["towards"])
                                      for g in r.json()["groups"])

async def test_nodata_returns_empty_groups(client, seoul):
    seoul("seoul_realtime_nodata.json")
    r = await client.get(ARRIVALS.format("홍대입구"))
    assert r.status_code == 200 and r.json()["groups"] == []

async def test_seoul_quota_error_in_200_body(client, respx_mock, services):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(return_value=quota_exceeded_response())
    r = await client.get(ARRIVALS.format("홍대입구"))
    assert r.status_code == 503 and r.json()["error"]["code"] == "QUOTA_EXCEEDED"
    assert not services.quotas["seoulRealtime"].can_call()

async def test_unknown_station_404_without_call(client, respx_mock):
    r = await client.get(ARRIVALS.format("없는역"))
    assert r.status_code == 404 and r.json()["error"]["code"] == "NOT_FOUND" and not respx_mock.calls

async def test_cached_for_20_seconds(client, seoul, clock):
    route = seoul("seoul_realtime_hongdae.json")
    await client.get(ARRIVALS.format("홍대입구")); clock.advance(19)
    await client.get(ARRIVALS.format("홍대입구"))
    assert route.call_count == 1

async def test_stale_after_failure(client, respx_mock, clock):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(side_effect=[
        fixture_response("seoul_realtime_hongdae.json"), httpx.Response(500), httpx.Response(500)])
    first = (await client.get(ARRIVALS.format("홍대입구"))).json(); clock.advance(21)
    r = await client.get(ARRIVALS.format("홍대입구"))
    assert r.status_code == 200 and r.json()["stale"] is True
    assert [(g["line"], g["towards"]) for g in r.json()["groups"]] == [(g["line"], g["towards"]) for g in first["groups"]]

async def test_502_without_cache(client, respx_mock):
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(side_effect=[httpx.Response(500), httpx.Response(500)])
    assert (await client.get(ARRIVALS.format("홍대입구"))).json()["error"]["code"] == "UPSTREAM_ERROR"

async def test_departed_train_excluded(client, respx_mock):
    def depart_first(body):
        body["realtimeArrivalList"][0].update(arvlMsg2="홍대입구 출발", barvlDt="0")
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        return_value=fixture_response("seoul_realtime_hongdae.json", depart_first))
    trains = [t for g in (await client.get(ARRIVALS.format("홍대입구"))).json()["groups"] for t in g["trains"]]
    assert all(t["message"] != "홍대입구 출발" for t in trains)

async def test_separate_station_keeps_only_own_lines(sample_client, respx_mock):
    def as_yangpyeong(body):   # 같은 이름의 두 역(5호선·경의중앙선) 열차가 섞인 응답을 만든다
        for i, it in enumerate(body["realtimeArrivalList"]):
            it["subwayId"] = "1005" if i % 2 == 0 else "1063"
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(
        return_value=fixture_response("seoul_realtime_hongdae.json", as_yangpyeong))
    groups = (await sample_client.get(ARRIVALS.format("양평"))).json()["groups"]
    assert groups and {g["line"] for g in groups} == {"5호선"}
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_arrivals.py -q` / Expected: FAIL (404 — 경로 없음)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 역 실시간 도착 API"`

---

### Task 10: 장소 검색과 좌표→주소

**Files:**
- Create: `server/app/clients/kakao_local.py`, `server/app/services/place_service.py`, `server/app/api/places.py`, `server/tests/integration/test_places.py`
- Modify: `server/app/models/schemas.py`, `server/app/core/container.py`, `server/app/main.py`

**Interfaces:**
- Consumes: Task 4 `index.nearest`, Task 5·6·7
- Produces (`kakao_local.py`): `class KakaoLocalClient`: `__init__(self, http, keyword_guard, coord_guard, settings)`, `async def search_keyword(self, query: str, lat: float | None, lng: float | None) -> dict`(`GET {KAKAO_LOCAL_BASE}/search/keyword.json`, `query`, `size=15`, 좌표가 있으면 `x=경도`, `y=위도`, `sort=distance`), `async def coord_to_address(self, lat: float, lng: float) -> dict`(`GET {KAKAO_LOCAL_BASE}/geo/coord2address.json`, `x`, `y`). 헤더 `Authorization: KakaoAK {키}`. 2xx가 아니면 `UpstreamError(reason=f"HTTP_{상태}")`.
- Produces (`schemas.py`): `Place(name: str, address: str | None, lat: float, lng: float, category: str | None)`, `PlaceSearchResponse(items: list[Place])`, `NearestStation(id: str, name: str, distance_m: int)`, `ReverseResponse(address: str | None, nearest_station: NearestStation | None)`
- Produces (`place_service.py`): `class PlaceService`: `__init__(self, client, index, search_cache: TtlCache[list[Place]], reverse_cache: TtlCache[str | None])`, `async def search(self, q: str, lat: float | None, lng: float | None) -> tuple[list[Place], str]`, `async def reverse(self, lat: float, lng: float) -> tuple[ReverseResponse, str]`
  - 장소 변환: `name` = `place_name`, `address` = `road_address_name` 또는 `address_name` 또는 `None`, `lat` = `float(y)`, `lng` = `float(x)`, `category` = `category_group_name` 또는 `category_name`의 마지막 `" > "` 조각 또는 `None`
  - 장소 검색 캐시 키 `f"{q}|{lat:.2f},{lng:.2f}"`(좌표 없으면 `f"{q}|-"`), 좌표→주소 캐시 키 `f"{lat:.4f},{lng:.4f}"`, 둘 다 86,400초 × 해당 한도 배율
  - 주소: 첫 문서의 `road_address.address_name`, 없으면 `address.address_name`, 문서가 없으면 `None`. 가까운 역은 캐시하지 않고 매번 `index.nearest`로 계산
- Produces: `GET /v1/places/search?q=&lat=&lng=`(q는 공백 제거 후 1~100자, `lat`·`lng`는 둘 다 주거나 둘 다 생략, 범위 −90~90 / −180~180), `GET /v1/places/reverse?lat=&lng=`, `AppServices.places`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
KEYWORD = f"{KAKAO_LOCAL_BASE}/search/keyword.json"
COORD = f"{KAKAO_LOCAL_BASE}/geo/coord2address.json"

async def test_keyword_search_maps_fields_and_sends_xy(client, respx_mock):
    route = respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_hongik.json"))
    r = await client.get("/v1/places/search", params={"q": "홍익대학교", "lat": 37.5571, "lng": 126.9245})
    sent = route.calls.last.request
    assert (sent.url.params["x"], sent.url.params["y"], sent.url.params["sort"], sent.url.params["size"]) == \
        ("126.9245", "37.5571", "distance", "15")
    assert sent.headers["Authorization"] == "KakaoAK TESTKAKAOKEY"
    item = r.json()["items"][0]
    assert set(item) == {"name", "address", "lat", "lng", "category"} and 37 < item["lat"] < 38 and 126 < item["lng"] < 128

async def test_search_without_coords_sends_no_xy(client, respx_mock):
    route = respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_hongik.json"))
    await client.get("/v1/places/search", params={"q": "홍익대학교"})
    assert "x" not in route.calls.last.request.url.params

async def test_search_cache_rounds_coords_to_2_decimals(client, respx_mock):
    route = respx_mock.get(KEYWORD).mock(return_value=fixture_response("kakao_keyword_hongik.json"))
    for lat, lng in [(37.5571, 126.9245), (37.5609, 126.9201)]:
        await client.get("/v1/places/search", params={"q": "홍익대학교", "lat": lat, "lng": lng})
    assert route.call_count == 1

async def test_lat_without_lng_is_400(client):
    assert (await client.get("/v1/places/search", params={"q": "a", "lat": 37.5})).status_code == 400

async def test_reverse_address_and_nearest_station(client, respx_mock):
    respx_mock.get(COORD).mock(return_value=fixture_response("kakao_coord2address_hongdae.json"))
    body = (await client.get("/v1/places/reverse", params={"lat": 37.5571, "lng": 126.9245})).json()
    doc = load_fixture("kakao_coord2address_hongdae.json")[1]["documents"][0]
    expected = (doc.get("road_address") or doc["address"])["address_name"]
    assert body["address"] == expected
    assert body["nearestStation"]["id"] == "홍대입구" and body["nearestStation"]["distanceM"] < 300

async def test_reverse_in_sea_has_nulls(client, respx_mock):
    respx_mock.get(COORD).mock(return_value=fixture_response("kakao_coord2address_sea.json"))
    assert (await client.get("/v1/places/reverse", params={"lat": 37.0, "lng": 125.0})).json() == \
        {"address": None, "nearestStation": None}

async def test_kakao_401_is_upstream_error(client, respx_mock):
    respx_mock.get(KEYWORD).mock(return_value=httpx.Response(401, json={"errorType": "AccessDeniedError"}))
    r = await client.get("/v1/places/search", params={"q": "홍익대학교"})
    assert r.status_code == 502 and r.json()["error"]["code"] == "UPSTREAM_ERROR"
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_places.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 장소 검색과 좌표→주소·가까운 역 API"`

---

### Task 11: 카카오 경로 변환

**Files:**
- Create: `server/app/services/route_convert.py`, `server/tests/unit/test_route_convert.py`
- Modify: `server/app/models/schemas.py`

**Interfaces:**
- Consumes: Task 4 `StationIndex.resolve`, Task 5 `Metrics`, Task 2 `normalize_line`·`normalize_station_name`, P1 §3 경로 필드 대응표
- Produces (`schemas.py`, 6.2): `Step(mode: Literal["WALK", "SUBWAY", "BUS"], duration_sec: int, distance_m: int, guidance: str, path: list[tuple[float, float]], line_name: str | None = None, route_name: str | None = None, stops: list[str] | None = None, board_station_id: str | None = None, alight_station_id: str | None = None)`, `Leg(steps: list[Step])`, `FirstBoarding(station_id: str | None, line_name: str, towards: str | None, sec_to_boarding: int, all_walk_before_boarding: bool, next_trains: list[Train] | None = None)`, `Route(id: str, type: Literal["SUBWAY", "BUS", "MIXED", "WALK"], total_sec: int, distance_m: int, transfers: int, fare: int | None, legs: list[Leg], first_boarding: FirstBoarding | None)`, `RoutesResponse(updated_at: datetime, stale: bool, routes: list[Route])`
- Produces (`route_convert.py`):
  - `make_subway_step(*, duration_sec: int, distance_m: int, guidance: str, path: list[tuple[float, float]], line_raw: str, stops: list[str], index: StationIndex, metrics: Metrics) -> Step` — `line_name = normalize_line(line_raw)`, `board/alight_station_id` = `index.resolve(stops[0]/stops[-1], line_name)`의 id 또는 `None`. 실패한 이름은 `metrics.unmatched_station_names`에 넣고 `logger.warning("역 매칭 실패: %s (%s)", 이름, 호선)`
  - `build_first_boarding(steps: list[Step], index: StationIndex) -> FirstBoarding | None` — 6.6 규칙. `towards`는 `normalize_station_name(stops[1]).id`, 정차역이 하나면 `None`
  - `route_type(steps: list[Step]) -> str` — 스펙 6.2(부록 B 보완) 규칙
  - `route_id(legs: list[Leg]) -> str` — `"r_" + sha1(json.dumps([[s.mode, s.line_name, s.route_name, s.duration_sec, s.stops] for leg in legs for s in leg.steps], ensure_ascii=False).encode()).hexdigest()[:8]`
  - `convert_routes(raw: dict, index: StationIndex, metrics: Metrics, limit: int = 5) -> list[Route]` — 대응표를 그대로 따르고 좌표 `[x, y]`는 `(y, x)`로 바꾼다. `legs`는 1개. 지하철 단계는 `make_subway_step`으로 만든다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
SUCCESS = ["kakao_route_subway_only.json", "kakao_route_transfer.json", "kakao_route_bus_subway.json"]

@pytest.fixture(params=SUCCESS)
def converted(request, real_index, metrics):
    _, body = load_fixture(request.param)
    return convert_routes(body, real_index, metrics), body

def test_routes_present_and_limited(converted):
    assert 1 <= len(converted[0]) <= 5

def test_paths_are_lat_lng_in_korea(converted):          # [x, y] → (위도, 경도) 확인
    for r in converted[0]:
        for s in r.legs[0].steps:
            assert all(33 <= lat <= 39 and 124 <= lng <= 132 for lat, lng in s.path)

def test_step_fields_by_mode(converted):
    for s in (s for r in converted[0] for s in r.legs[0].steps):
        if s.mode == "SUBWAY":
            assert s.line_name and len(s.stops) >= 2 and s.route_name is None
        elif s.mode == "BUS":
            assert s.route_name and s.stops
        else:
            assert s.line_name is None and s.stops is None

def test_totals_match_kakao_summary(converted):
    routes, body = converted
    for i, r in enumerate(routes):
        # kakao_summary: 대응표 §3의 요약 필드를 읽는 테스트 도우미 → (총 초, 거리 m, 환승, 요금)
        assert (r.total_sec, r.distance_m, r.transfers, r.fare) == kakao_summary(body, i)

def step(mode, sec, **kw):
    return Step(mode=mode, duration_sec=sec, distance_m=0, guidance="", path=[], **kw)

def test_first_boarding_rules(real_index):
    walk, bus = step("WALK", 420), step("BUS", 300, route_name="7016", stops=["a", "b"])
    sub = step("SUBWAY", 600, line_name="2호선", stops=["홍대입구", "합정", "당산"],
               board_station_id="홍대입구", alight_station_id="당산")
    fb = build_first_boarding([walk, sub], real_index)
    assert (fb.station_id, fb.line_name, fb.towards, fb.sec_to_boarding, fb.all_walk_before_boarding) == \
        ("홍대입구", "2호선", "합정", 420, True)
    mixed = build_first_boarding([walk, bus, sub], real_index)
    assert (mixed.sec_to_boarding, mixed.all_walk_before_boarding) == (720, False)
    assert build_first_boarding([walk, bus], real_index) is None
    assert build_first_boarding([sub.model_copy(update={"stops": ["홍대입구"]})], real_index).towards is None
    to_seoul = sub.model_copy(update={"stops": ["시청", "서울역"]})
    assert build_first_boarding([to_seoul], real_index).towards == "서울"

@pytest.mark.parametrize("modes, expected", [(["WALK", "SUBWAY"], "SUBWAY"), (["BUS"], "BUS"),
                                             (["BUS", "WALK", "SUBWAY"], "MIXED"), (["WALK"], "WALK")])
def test_route_type(modes, expected):
    assert route_type([step(m, 60) for m in modes]) == expected

def test_route_id_stable_and_distinct():
    a, b = [Leg(steps=[step("WALK", 60)])], [Leg(steps=[step("WALK", 61)])]
    assert route_id(a) == route_id(a) != route_id(b) and route_id(a).startswith("r_")

def test_unmatched_station_recorded(real_index, metrics, caplog):
    s = make_subway_step(duration_sec=60, distance_m=1000, guidance="", path=[], line_raw="2호선",
                         stops=["없는역", "홍대입구"], index=real_index, metrics=metrics)
    assert (s.board_station_id, s.alight_station_id) == (None, "홍대입구")
    assert metrics.unmatched_station_names == {"없는역"} and "없는역" in caplog.text
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_route_convert.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과. 실제 샘플에서 `unmatched_station_names`가 비어 있지 않으면 그 이름을 별칭으로 더하거나 Issue로 남긴다(6.5).
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 카카오 대중교통 경로를 서버 형식으로 변환"`

---

### Task 12: 경로 API

**Files:**
- Create: `server/app/clients/kakao_route.py`, `server/app/services/route_service.py`, `server/app/api/params.py`, `server/app/api/routes.py`, `server/tests/integration/test_routes.py`
- Modify: `server/app/core/container.py`, `server/app/main.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: Task 11 `convert_routes`, Task 5·6·7, P1 `KAKAO_ROUTE_URL`와 §3 오류 표
- Produces (`params.py`): `parse_latlng(value: str) -> tuple[float, float]` — `"위도,경도"`, 위도 −90~90·경도 −180~180이 아니면 `InvalidRequest(reason="PARAMS")`
- Produces (`kakao_route.py`): `class KakaoRouteClient`: `__init__(self, http, guard, settings)`, `async def fetch(self, origin: tuple[float, float], dest: tuple[float, float], origin_name: str | None = None, dest_name: str | None = None) -> dict` — 파라미터 `start_x=출발 경도`, `start_y=출발 위도`, `end_x`, `end_y`, 이름이 있으면 `s_name`, `e_name`. 오류 코드 변환: `EQUAL_POINTS` → `InvalidRequest("EQUAL_POINTS")`, `STARTNODES_NULL` → `NoRoute("NO_START_STOP")`, `ENDNODES_NULL` → `NoRoute("NO_END_STOP")`, `NO_RESULTS` → `NoRoute("NO_RESULTS")`, 카카오 `INVALID_REQUEST`와 그 밖 → `UpstreamError(reason=코드)`
- Produces (`route_service.py`): `class RouteService`: `__init__(self, client, index, cache: TtlCache[list[Route]], metrics, clock)`, `async def search(self, origin: tuple[float, float], dest: tuple[float, float], origin_name: str | None = None, dest_name: str | None = None) -> tuple[RoutesResponse, str]` — 소수점 4자리로 반올림한 두 좌표가 같으면 카카오를 부르지 않고 `InvalidRequest("EQUAL_POINTS")`. 캐시 키 `f"{o[0]:.4f},{o[1]:.4f}|-|{d[0]:.4f},{d[1]:.4f}"`(가운데 `-`는 P4의 경유지 자리), 600초 × 경로 한도 배율
- Produces: `GET /v1/routes?from=&to=&fromName=&toName=`, `AppServices.routes`, conftest 픽스처 `kakao_route(name) -> respx.Route`, `tests/helpers.py`의 `kakao_error_response(code: str) -> httpx.Response`(§3 오류 형식, `kakao_route_error_equal_points.json`을 본뜸)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
HONGDAE, GANGNAM = "37.5571,126.9245", "37.4979,127.0276"

async def test_routes_success(client, kakao_route):
    kakao_route("kakao_route_transfer.json")
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM}); body = r.json()
    assert r.status_code == 200 and 1 <= len(body["routes"]) <= 5 and body["updatedAt"].endswith("+09:00")
    assert all(len(rt["legs"]) == 1 for rt in body["routes"]) and body["stale"] is False

async def test_kakao_gets_x_as_longitude(client, kakao_route):
    route = kakao_route("kakao_route_subway_only.json")
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM, "fromName": "출발지"})
    p = route.calls.last.request.url.params
    assert (p["start_x"], p["start_y"], p["end_x"], p["end_y"], p["s_name"]) == \
        ("126.9245", "37.5571", "127.0276", "37.4979", "출발지")

async def test_equal_points_rejected_without_calling_kakao(client, respx_mock):
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": "37.55712,126.92451"})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "EQUAL_POINTS" and not respx_mock.calls

@pytest.mark.parametrize("kakao_code, status, code, reason", [
    ("EQUAL_POINTS", 400, "INVALID_REQUEST", "EQUAL_POINTS"),
    ("STARTNODES_NULL", 404, "NO_ROUTE", "NO_START_STOP"),
    ("ENDNODES_NULL", 404, "NO_ROUTE", "NO_END_STOP"),
    ("NO_RESULTS", 404, "NO_ROUTE", "NO_RESULTS"),
    ("INVALID_REQUEST", 502, "UPSTREAM_ERROR", "INVALID_REQUEST"),
])
async def test_kakao_error_codes(client, respx_mock, kakao_code, status, code, reason):
    respx_mock.get(KAKAO_ROUTE_URL).mock(return_value=kakao_error_response(kakao_code))
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert r.status_code == status and r.json()["error"]["code"] == code
    assert r.json()["error"]["reason"] == reason

async def test_cache_key_rounds_to_4_decimals_and_lasts_10_minutes(client, kakao_route, clock):
    route = kakao_route("kakao_route_subway_only.json")
    for origin in ["37.55711,126.92451", "37.55714,126.92449"]:
        await client.get("/v1/routes", params={"from": origin, "to": GANGNAM})
    clock.advance(599)
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert route.call_count == 1
    clock.advance(1)
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert route.call_count == 2

@pytest.mark.parametrize("bad", ["abc", "37.5", "91,127", "37.5,181"])
async def test_bad_coordinates(client, bad):
    r = await client.get("/v1/routes", params={"from": bad, "to": GANGNAM})
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"

async def test_stale_route_when_kakao_down(client, respx_mock, clock):
    respx_mock.get(KAKAO_ROUTE_URL).mock(side_effect=[
        fixture_response("kakao_route_transfer.json"), httpx.Response(500), httpx.Response(500)])
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM}); clock.advance(601)
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert r.status_code == 200 and r.json()["stale"] is True
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_routes.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 대중교통 경로 API"`

---

### Task 13: 요청 제한, 요청 ID, 접근 로그

**Files:**
- Create: `server/app/core/rate_limit.py`, `server/tests/unit/test_rate_limit.py`, `server/tests/integration/test_middleware.py`
- Modify: `server/app/main.py`, `server/app/core/container.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: Task 1 `RateLimited`, Task 5 `Metrics`, Task 7 `configure_logging`
- Produces (`rate_limit.py`): `class SlidingWindowRateLimiter`: `__init__(self, limit: int, window_sec: float = 60.0, clock: Callable[[], float] = time.monotonic)`, `allow(key: str) -> bool`(키마다 시각 `deque`, 창 밖 기록 제거, 개수 < `limit`이면 기록하고 True, 빈 키는 지움); `client_ip(request: Request) -> str`(`X-Forwarded-For` 첫 값의 공백 제거, 없으면 `request.client.host`, 그것도 없으면 `"unknown"`)
- Produces: HTTP 미들웨어(`main.py`) — 요청 ID `uuid4().hex[:12]`를 응답 헤더 `X-Request-ID`로; `/health`가 아니면 `allow(client_ip)`가 False일 때 429 `RATE_LIMITED`(6.3 형식)와 `metrics.rate_limited += 1`; `/v1/`로 시작하고 `/v1/metrics`가 아닌 요청은 `metrics.requests_total += 1`; 끝나면 접근 로그 `logger.info("access", extra={"request_id", "method", "path"(쿼리 제외), "status", "duration_ms", "cache": request.state.cache_status 또는 "none"})`
- Produces: `AppServices.rate_limiter`, conftest 픽스처 `limited_client`와 `limited_services`(`rate_limit_per_minute=3`인 앱의 클라이언트와 `app.state.services`)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/unit/test_rate_limit.py
def test_allows_limit_then_blocks_then_recovers():
    clock = FakeClock(0.0); rl = SlidingWindowRateLimiter(60, clock=clock)
    assert all(rl.allow("a") for _ in range(60)) and not rl.allow("a")
    clock.advance(60.01)
    assert rl.allow("a")

def test_keys_are_independent():
    rl = SlidingWindowRateLimiter(1, clock=FakeClock(0.0))
    assert rl.allow("a") and rl.allow("b") and not rl.allow("a")

# tests/integration/test_middleware.py
SEARCH = ("/v1/stations/search", {"q": "홍대"})

async def test_rate_limited_response_and_metric(limited_client, limited_services):
    h = {"X-Forwarded-For": "1.1.1.1, 10.0.0.1"}
    codes = [(await limited_client.get(SEARCH[0], params=SEARCH[1], headers=h)).status_code for _ in range(4)]
    assert codes == [200, 200, 200, 429] and limited_services.metrics.rate_limited == 1
    r = await limited_client.get(SEARCH[0], params=SEARCH[1], headers={"X-Forwarded-For": "2.2.2.2"})
    assert r.status_code == 200

async def test_health_is_never_rate_limited(limited_client):
    assert all((await limited_client.get("/health")).status_code == 200 for _ in range(10))

async def test_request_id_and_access_log_without_query(client, caplog):
    caplog.set_level(logging.INFO)
    r = await client.get("/v1/stations/search", params={"q": "37.55712"})
    assert len(r.headers["X-Request-ID"]) == 12
    rec = next(x for x in caplog.records if getattr(x, "path", None) == "/v1/stations/search")
    assert (rec.status, rec.cache) == (200, "none")
    assert "37.55712" not in json.dumps(rec.__dict__, default=str, ensure_ascii=False)
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_rate_limit.py tests/integration/test_middleware.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): IP별 요청 제한, 요청 ID, JSON 접근 로그"`

---

### Task 14: 지표 API

**Files:**
- Create: `server/app/api/metrics.py`, `server/tests/integration/test_metrics.py`
- Modify: `server/app/models/schemas.py`, `server/app/main.py`

**Interfaces:**
- Consumes: Task 5 `Metrics`, Task 6 `quotas`
- Produces: `GET /v1/metrics` — 6.2 형식. `since` = 앱을 만든 시각(KST), `requests` = `{"total", "rateLimited"}`, `cache` = `{"hits", "misses", "hitRate": metrics.hit_rate()}`, `upstream` = `{이름: guard.snapshot()}`(Task 6 순서), `unmatchedStations` = `len(metrics.unmatched_station_names)`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
async def test_metrics_on_fresh_server(client):
    r = await client.get("/v1/metrics"); body = r.json()
    assert r.status_code == 200 and body["cache"] == {"hits": 0, "misses": 0, "hitRate": 0.0}
    assert list(body["upstream"]) == ["seoulRealtime", "kakaoRoute", "kakaoKeyword", "kakaoCoord2Address"]
    assert body["upstream"]["seoulRealtime"] == {"callsToday": 0, "dailyLimit": 1000, "errorsToday": 0}
    assert body["since"].endswith("+09:00") and body["unmatchedStations"] == 0

async def test_metrics_after_miss_and_hit(client, seoul):
    seoul("seoul_realtime_hongdae.json")
    for _ in range(2):
        await client.get("/v1/stations/홍대입구/arrivals")
    body = (await client.get("/v1/metrics")).json()
    assert body["cache"] == {"hits": 1, "misses": 1, "hitRate": 0.5}
    assert body["upstream"]["seoulRealtime"]["callsToday"] == 1 and body["requests"]["total"] == 2
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_metrics.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 운영 지표 API"`

---

### Task 15: 모의 외부 API와 부하 테스트 스크립트

**Files:**
- Create: `server/app/clients/mock_transport.py`, `server/scripts/load_test.py`, `server/tests/unit/test_mock_and_load_test.py`
- Modify: `server/app/core/container.py`

**Interfaces:**
- Consumes: Task 14 `/v1/metrics` 형식, P1 샘플
- Produces (`mock_transport.py`): `class FixtureTransport(httpx.AsyncBaseTransport)`: `__init__(self, fixtures_dir: Path, latency_sec: float = 0.1)`. 서울시 요청 → URL 끝의 역 이름이 첫 항목의 역 이름 필드(예상 `statnNm`)와 같은 `seoul_realtime_*.json`, 없으면 `seoul_realtime_nodata.json`; 키워드 → `kakao_keyword_hongik.json`; 좌표→주소 → `kakao_coord2address_hongdae.json`; 경로 → `kakao_route_transfer.json`. `latency_sec`만큼 기다린 뒤 샘플의 상태와 본문으로 응답한다. `settings.mock_upstream`이고 `transport`가 주어지지 않으면 `build_services`가 이것을 쓴다(호출 수는 `call_upstream`이 그대로 센다 — 9.3).
- Produces (`scripts/load_test.py`):
  - `@dataclass class Summary: app_requests: int; upstream_calls: int; upstream_ratio: float; cache_hit_rate: float`
  - `summarize(before: dict, after: dict) -> Summary` — 두 `/v1/metrics` 응답의 차이(비율은 소수점 3자리)
  - `assert_local(base_url: str) -> None` — 호스트가 `127.0.0.1`·`localhost`가 아니면 `SystemExit("부하 테스트는 로컬 서버에서만 실행합니다 (스펙 9.3)")`
  - `render_report(summary: Summary, params: dict) -> str` — 마크다운(조건, 앱 요청 수, 외부 API 호출 수, 외부 API 호출 비율, 캐시 적중률)
  - CLI `python -m scripts.load_test --base-url http://127.0.0.1:8000 --users 50 --minutes 10 --out <md 경로> --seed 42`: 가상 사용자 i는 [0, 30)초 안의 무작위 시각에 시작하고(사용자끼리 박자가 맞지 않게), 헤더 `X-Forwarded-For: 10.0.{i // 256}.{i % 256}`(사용자마다 다른 IP — 요청 제한 회피)를 붙여 30초마다 `STATIONS = ["홍대입구", "시청", "강남"]` 실시간 조회, 2분마다 `ROUTES`(홍대입구역→강남역, 홍대입구역→경복궁역, P1 `LANDMARKS` 좌표) 검색. 실행 전후 `/v1/metrics`로 `summarize` → `render_report` → `--out`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
async def test_mock_mode_serves_fixture_and_counts_call():
    app = create_app(Settings(_env_file=None, mock_upstream=True))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as c:
        r = await c.get("/v1/stations/홍대입구/arrivals")
        m = (await c.get("/v1/metrics")).json()
    assert r.status_code == 200 and r.json()["groups"]
    assert m["upstream"]["seoulRealtime"]["callsToday"] == 1

def test_summarize():
    before = {"requests": {"total": 10}, "cache": {"hits": 5, "misses": 5},
              "upstream": {"a": {"callsToday": 3}, "b": {"callsToday": 1}}}
    after = {"requests": {"total": 110}, "cache": {"hits": 95, "misses": 15},
             "upstream": {"a": {"callsToday": 8}, "b": {"callsToday": 1}}}
    assert summarize(before, after) == Summary(100, 5, 0.05, 0.9)

def test_refuses_remote_base_url():
    with pytest.raises(SystemExit):
        assert_local("https://example.onrender.com")
    assert_local("http://127.0.0.1:8000")
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_mock_and_load_test.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: 짧은 실행으로 확인**

Run: `MOCK_UPSTREAM=true uvicorn app.main:app --port 8000 & sleep 3; python -m scripts.load_test --users 5 --minutes 1 --out /tmp/lt.md; kill %1; cat /tmp/lt.md`
Expected: 보고서가 출력되고 외부 API 호출 비율이 0.5보다 작다. (10분·50명 본 측정은 P5 Task 1)

- [ ] **Step 6: Commit** — `git add server docs/learning && git commit -m "feat(server): 모의 외부 API 모드와 부하 테스트 스크립트"`

---

### Task 16: 배포와 깨우기 호출

**Files:**
- Modify: `README.md` (서버 절), `server/.env.example`(빠진 이름이 있으면)

**Interfaces:**
- Consumes: P1 Task 3의 Render 서비스, Task 1~15 전부
- Produces: 공개 API 주소와 `/docs` 링크(README) — P3의 `server.baseUrl`

- [ ] **Step 1: 로컬 컨테이너 확인**

Run: `docker build -t jc-server . && docker run -d --rm --name jc --env-file .env -e PORT=9000 -p 9000:9000 jc-server && sleep 3 && curl -s "localhost:9000/v1/stations/search?q=%E3%85%8E%E3%84%B7%E3%85%87%E3%84%B1"; docker stop jc`
Expected: `홍대입구`가 든 JSON

- [ ] **Step 2: (직접) Render 환경 변수와 배포**

Render 대시보드에 `SEOUL_REALTIME_API_KEY`, `KAKAO_REST_API_KEY`(필요하면 한도 변수)를 넣고 `main`을 배포한다.

- [ ] **Step 3: 공개 URL에서 엔드포인트 확인**

`/docs`를 열어 6.2의 모든 엔드포인트(`/health`, 역 검색, 실시간 도착, 장소 검색, 좌표→주소, 경로, 지표)를 한 번씩 실행해 200 응답을 확인한다. 실시간 도착이 빈 `groups`면 운행 시간에 다시 확인한다.

- [ ] **Step 4: (직접) 깨우기 호출 설정**

무료 예약 호출 서비스(예: cron-job.org)에 평일 06:30~09:30, 17:00~20:00(한국 시간) 10분마다 `GET /health` 작업을 만든다(10.1).

- [ ] **Step 5: README 서버 절**

공개 주소, `/docs` 링크, 환경 변수 표(10.1), 로컬 실행 방법(`pip install -e ".[dev]"`, `uvicorn app.main:app --reload`), 역 데이터 다시 만들기 명령, 깨우기 호출 설정을 적는다.

- [ ] **Step 6: Commit** — `git add README.md server/.env.example docs/learning && git commit -m "docs: 서버 배포 주소와 운영 방법"`
