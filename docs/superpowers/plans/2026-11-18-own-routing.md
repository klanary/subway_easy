# 경로 직접 계산(운행표 + RAPTOR) — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** VM이 공식·실측 시간표로 열차 운행표를 매일 만들어 내보내고, 서버가 그 운행표로 RAPTOR 검색을 해 카카오 없이 지하철 경로(구간마다 실제 열차)를 준다.

**Architecture:** VM `ml/trips.py`가 시간표 캐시(1~9호선)·B1·B2로 서버용 운행표(`trips-server`)를 만들고 `ml/publish.py`가 매일 올린다(B1·B2는 수·일만). 서버는 `app/services/trips.py`(운행표 → 정수 배열 색인)와 `app/services/raptor.py`(순수 검색)를 두고, `RouteService`가 검색 결과를 기존 `RoutesResponse`로 바꾼다. 카카오 코드는 지운다.

**Tech Stack:** Python 3.12+, FastAPI, httpx, pytest/respx, `array` 표준 모듈; Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-18-own-routing-design.md`

## Global Constraints

- 요금 표시 없음: 응답 `fare`는 늘 null, 앱은 요금 문구를 지운다.
- 도보 단계 없음, 환승 걷는 시간 0: 같은 우리 역 id에서 내린 시각 이후(같은 초 포함) 출발 열차면 탄다.
- 최대 5라운드(열차 5대, 환승 4번). 결과는 환승 횟수별 가장 이른 도착(파레토), 최대 5개. 더 많은 환승이 더 늦거나 같으면 뺀다.
- 운행일 규칙은 지금 시간표와 같다: 05시 전이면 전날 운행일도 본다. 그날 못 닿으면 다음 운행일 첫차부터 한 번 더.
- 비서울 5개 노선 역(`NON_SEOUL_LINES`만 있는 역)은 `NO_ROUTE`. 운행표가 아직 없으면 503, 코드 `ROUTES_NOT_READY`, 문구 "경로 정보를 준비 중이에요. 잠시 후 다시 시도해 주세요."
- 운행표는 매일 03:30 KST에 내보내고(`OnCalendar=*-*-* 03:30:00`), B1·B2는 수·일만 새로 만든다(다른 날은 manifest가 지난 B1·B2 이름을 그대로 가리킨다).
- 같은 열차 번호가 끊기는 간격 `TRIP_GAP_SEC = 10800`(3시간)이면 운행을 나눈다. 실측 도착 = 출발 − 그 역 평소 정차(B2 `dwell` 중앙값, 없으면 30초).
- 메모리 목표: 정차 60만 규모 합성 운행표에서 서버 최대 150MB 이하, 검색 한 번 0.5초 이하(개발 PC 기준).
- 서버 테스트 `server/`에서 `.venv/Scripts/python -m pytest -q`; 앱 `android/`에서 `./gradlew.bat testDebugUnitTest lintDebug`. 커밋은 `develop`, `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 학습 노트 `docs/learning/99-…`부터.

## Review Focus

1. **자정 넘는 운행(정차 초 ≥ 86400):** 23:50 출발이 00:20 열차를 탈 수 있다. → Task 4 `test_after_midnight_trip_is_reachable`.
2. **막차 뒤 요청:** 다음 운행일 첫차로 경로가 나온다(빈 결과가 아님). → Task 4 `test_after_last_train_uses_next_service_day`.
3. **같은 역에서 같은 초에 도착·출발:** 탈 수 있다(걷는 시간 0). → Task 4 `test_transfer_same_second_is_allowed`.
4. **급행이 서지 않는 역이 도착역:** 그 급행을 타고 지나치지 않는다. → Task 4 `test_express_skipping_destination_is_not_used`.
5. **운행표를 받기 전의 첫 경로 요청:** 503 문구(앱이 오류를 보인다, 크래시 아님). → Task 5 `test_routes_503_before_trips_loaded`.

---

### Task 1: VM 운행표 만들기 (`ml/trips.py`)

**Files:**
- Create: `server/ml/trips.py`, `server/tests/fixtures/trips/`(공식 캐시 파일 몇 개, 작은 stations.sqlite는 기존 `data/stations.sqlite` 사용)
- Test: `server/tests/unit/test_ml_trips.py`

**Interfaces:**
- Consumes: `ml.b0.TimetableCache(dir).rows(code, week, inout)`, 서버용이 아닌 원본 B1·B2 dict(작업 1과 같은 형식), `data/stations.sqlite`의 `station_codes(station_id, line_name, station_cd)`.
- Produces:
  - `build_trips(cache_dir: Path, stations_db: Path, b1: dict | None, b2: dict | None, *, built_on: date) -> dict` — 서버용 운행표:
    ```
    {"kind": "trips-server", "version": 1, "built_on": "YYYY-MM-DD",
     "stations": [우리 역 id, ...],               # 정차 번호 = 이 목록의 위치
     "day_types": {"1": [pattern, ...], "2": [...], "3": [...]}}
    pattern = {"line": str, "direction": "상행|하행|내선|외선", "stops": [정차 번호, ...],
               "trips": [[train_no, express(0|1), dest_name|null, source(0 공식|1 실측),
                          estimated(0|1), [arr0, dep0, arr1, dep1, ...]], ...]}  # 첫 정차 출발 순
    ```
  - 같은 패턴 안의 운행은 첫 정차 출발 순이고 서로 앞지르지 않는다(앞지르면 다른 패턴으로 나눈다).

- [ ] **Step 1: 실패하는 테스트**

```python
def test_official_rows_group_into_trips_by_train_no(trips): ...        # 5호선 상행 열차 5101이 정차 3개, 시각 순
def test_express_trip_has_only_its_stops(trips): ...                   # 9호선 급행은 서는 역만 → 일반과 다른 패턴
def test_same_train_no_twice_a_day_splits(trips): ...                  # 2호선 같은 번호, 3시간 넘게 빈 뒤 → 운행 2개
def test_after_midnight_seconds_kept(trips): ...                       # "24:20:00" → 87600
def test_measured_train_no_line_builds_trips(trips): ...               # 경의중앙선 B1 번호 묶음, 도착 = 출발 - 정차
def test_slots_line_builds_estimated_trips_from_hops(trips): ...       # 신분당선: 첫 역 출발 + B2 구간 합, estimated 1
def test_unknown_station_code_is_skipped(trips): ...
def test_overtaking_trips_go_to_separate_patterns(): ...
```

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_ml_trips.py -q` → ModuleNotFoundError.
- [ ] **Step 3: 구현** — 공식: 캐시의 모든 파일을 열고(행의 `INOUT_TAG` 1 → 상행·내선, 2 → 하행·외선), (요일, 호선, 방향, `TRAIN_NO`)로 모아 시각 순 정렬 뒤 `TRIP_GAP_SEC`로 나눔. 정차 시각은 `ARRIVETIME`/`LEFTTIME`("00:00:00"이면 다른 쪽 값). 실측: B1 칸(원본 형식), `modes[line] == "train_no"`면 번호로, `slots`면 그 노선의 첫 역(B2 `hops`에서 들어오는 구간이 없는 역) 출발마다 B2 구간을 따라 이어 만든다. 1~9호선은 실측을 쓰지 않는다. 메모리를 위해 파일을 하나씩 읽고 바로 줄인다(VM 1GB).
- [ ] **Step 4: 통과 확인**, `ruff check`.
- [ ] **Step 5: 커밋** — `feat(ml): 공식·실측 시간표로 열차 운행표 만들기`

### Task 2: 운행표 매일 내보내기

**Files:**
- Modify: `server/ml/publish.py`, `server/ml/deploy/subway-ml-publish.timer`(매일), `server/app/services/measured_store.py`(상수 `TRIPS_PREFIX = "trips-"`), `docs/ml/collector.md`
- Test: `server/tests/unit/test_ml_publish.py`, `server/tests/unit/test_ml_deploy.py`

**Interfaces:**
- Consumes: `build_trips`(Task 1).
- Produces: manifest에 `"trips": {"name": "trips-<시각>.json.gz", "sha256": ..., "built_on": "YYYY-MM-DD"}`. 수·일이 아닌 날은 B1·B2를 새로 만들지 않고, 릴리스의 지금 manifest를 받아 그 `b1`·`b2` 항목을 그대로 옮긴다(지금 manifest가 없으면 그날도 새로 만든다). 옛 데이터 지우기는 새 manifest가 가리키지 않는 `measured_timetable-`·`segment_times-`·`trips-` 자산만.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_manifest_includes_trips(): ...                                   # trips 이름·sha256·built_on
@respx.mock
def test_weekday_reuses_previous_b1_b2_and_uploads_only_trips(): ...       # 화요일: 업로드는 trips + manifest, b1/b2는 옛 이름 유지, 옛 b1/b2 안 지움
@respx.mock
def test_wednesday_rebuilds_all(): ...
def test_publish_timer_runs_daily(): ...                                   # "OnCalendar=*-*-* 03:30:00"
```

- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현** — `main`에 `--timetable-cache`(기본 `ml_data/timetable_cache`), `--stations-db`(기본 `data/stations.sqlite`). 요일 판단은 `--day` 또는 오늘(KST), 수=2·일=6.
- [ ] **Step 4: 통과 확인**, 전체 `pytest -q`.
- [ ] **Step 5: 커밋** — `feat(ml): 운행표를 매일 내보내기(B1·B2는 수·일)`

### Task 3: 서버 운행표 색인과 받기

**Files:**
- Create: `server/app/services/trips.py`
- Modify: `server/app/services/measured_store.py`(manifest의 `trips`도 받기, `current_trips()`)
- Test: `server/tests/unit/test_trips.py`, `server/tests/unit/test_measured_store.py`

**Interfaces:**
- Produces:
  - `class TripsData`, `TripsData.from_artifact(data: dict) -> TripsData`(형식 틀리면 ValueError), `built_on: date`
  - `TripsData.day(day_type: int) -> DayTrips` — 그 요일 구분의 패턴들.
  - `DayTrips`: `patterns: list[Pattern]`, `patterns_at(stop: int) -> list[tuple[int, int]]`(그 정차를 지나는 (패턴 번호, 정차 위치)), `stop_index(station_id: str) -> int | None`, `station_id(stop: int) -> str`.
  - `Pattern`: `line, direction, stops: tuple[int, ...]`, `n_trips: int`, `arr(trip: int, pos: int) -> int`, `dep(trip, pos) -> int`, `trip_meta(trip) -> TripMeta(train_no, express, dest, source, estimated)`, `earliest_trip(pos: int, after: int) -> int | None`(이분 탐색). 시각은 패턴마다 `array('i')` 하나(운행 × 정차 × 2).
  - `MeasuredStore.current_trips() -> TripsData | None` — `current()`와 같은 규칙(백그라운드 받기, 지난 것 유지). 운행표의 신선도 경고: `built_on`이 3일 넘게 지나면 경고 로그(쓰기는 계속).

- [ ] **Step 1: 실패하는 테스트**

```python
def test_from_artifact_indexes_patterns_by_stop(): ...
def test_earliest_trip_binary_search(): ...           # after보다 같거나 늦은 첫 출발
def test_times_are_compact_arrays(): ...              # Pattern 내부가 array('i')
async def test_store_loads_trips_from_manifest(): ...
async def test_trips_bad_checksum_keeps_previous(): ...
```

- [ ] **Step 2–4:** 실패 확인 → 구현 → 통과.
- [ ] **Step 5: 커밋** — `feat(server): 운행표를 받아 정수 배열로 색인`

### Task 4: RAPTOR 검색 (`app/services/raptor.py`)

**Files:**
- Create: `server/app/services/raptor.py`
- Test: `server/tests/unit/test_raptor.py`
- Create(측정용, 커밋): `server/scripts/measure_raptor.py` — 정차 60만 합성 운행표로 최대 메모리·검색 시간 출력

**Interfaces:**
- Consumes: `TripsData`, `DayTrips`, `Pattern`(Task 3), `app.services.timetable.service_days`, `week_tag`.
- Produces:
  - `@dataclass Ride(line, direction, train_no, express, dest, source, estimated, board_station, alight_station, departs_at: datetime, arrives_at: datetime, stops: list[str])`
  - `@dataclass Journey(rides: list[Ride])` — `arrives_at`, `departs_at`, `transfers` 속성.
  - `search(trips: TripsData, origin: str, dest: str, depart: datetime, *, max_rounds: int = 5) -> list[Journey]` — 파레토(환승 적은 순, 도착 이른 것만), 최대 5개. 운행일 규칙(Global Constraints)대로 전날·다음 날을 본다.

- [ ] **Step 1: 실패하는 테스트**(손으로 만든 작은 운행표)

```python
def test_direct_trip(): ...                                  # 한 대로 도착, transfers 0
def test_pareto_keeps_faster_transfer_and_direct(): ...       # 직행 08:40 도착, 환승 1번 08:30 도착 → 둘 다
def test_more_transfers_not_faster_are_dropped(): ...
def test_transfer_same_second_is_allowed(): ...
def test_express_skipping_destination_is_not_used(): ...
def test_after_midnight_trip_is_reachable(): ...
def test_after_last_train_uses_next_service_day(): ...
def test_before_5am_considers_previous_service_day(): ...
def test_unknown_station_returns_empty(): ...
```

- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현** — 표준 RAPTOR(라운드마다 표시된 정차의 패턴을 훑어 가장 이른 운행을 타고 내린 정차의 도착을 갱신). 운행일마다 따로 돌려 결과를 합친다(운행일 0시 기준 초 ↔ `datetime` 변환). 경로 되짚기는 라운드별 (패턴, 운행, 탄 위치)를 남겨서.
- [ ] **Step 4: 통과 확인** + `python scripts/measure_raptor.py` 결과가 최대 150MB 이하, 검색 0.5초 이하인지 보고 계획 끝의 "측정"에 숫자를 적는다.
- [ ] **Step 5: 커밋** — `feat(server): 운행표 RAPTOR 경로 검색`

### Task 5: 경로 서비스 바꾸기와 카카오 지우기

**Files:**
- Modify: `server/app/services/route_service.py`(카카오 대신 `raptor.search`), `server/app/models/schemas.py`(`Step`에 `departs_at`, `arrives_at`, `train_no`, `is_express`, `destination`, `source: Literal["official","measured"] | None`, `estimated: bool = False`), `server/app/core/errors.py`(`RoutesNotReady` 503 `ROUTES_NOT_READY`), `server/app/core/container.py`, `server/app/core/config.py`(카카오 설정 삭제), `server/app/api/routes.py`
- Delete: `server/app/clients/kakao_route.py`, `server/app/services/route_convert.py`(필요한 `route_id`·경유 조합이 있으면 `route_service.py`로 옮김), 카카오 픽스처 `server/tests/fixtures/kakao_route_*.json`, 카카오 전용 테스트
- Test: `server/tests/integration/test_routes.py`(다시 씀), `server/tests/integration/test_metrics.py`(upstream에서 kakaoRoute 빠짐)

**Interfaces:**
- Consumes: `search`(Task 4), `MeasuredStore.current_trips()`(Task 3), 기존 `ArrivalService.get_arrivals`(첫 탑승역 실시간 표시용).
- Produces: `GET /v1/routes?from&to&via&departAt` — 응답 모양은 `RoutesResponse` 그대로, 단계는 `SUBWAY`만, `fare` null, `depart_at`·`arrive_at` 늘 채움, `transfers` = 구간 수 − 1, `first_boarding`(첫 구간, `sec_to_boarding` 0, `all_walk_before_boarding` true, `towards` = 두 번째 정차역 이름, `next_trains` = 지금 출발일 때만 실시간 그 방향 2대).

- [ ] **Step 1: 실패하는 테스트** — `conftest`에 작은 운행표를 넣는 `trips_loaded` 픽스처.

```python
async def test_route_ends_at_destination(client, trips_loaded): ...       # 마지막 단계 alightStationId == to
async def test_no_kakao_or_seoul_calls(client, trips_loaded, respx_mock): ...  # respx 호출 0(실시간 표시 제외: departAt 지정)
async def test_fare_is_null_and_steps_are_subway_only(client, trips_loaded): ...
async def test_steps_carry_train_times(client, trips_loaded): ...         # departsAt, arrivesAt, trainNo
async def test_via_route(client, trips_loaded): ...
async def test_non_seoul_station_is_no_route(client, trips_loaded): ...
async def test_routes_503_before_trips_loaded(client): ...                # code ROUTES_NOT_READY
async def test_same_station_is_400(client, trips_loaded): ...
```

- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인** — 전체 `pytest -q`, `ruff check`.
- [ ] **Step 5: 커밋** — `feat(server): 운행표로 경로를 직접 계산하고 카카오를 걷어 냄`

### Task 6: 앱 — 요금 지우기, 열차 시각 단계

**Files:**
- Modify: `android/.../data/remote/Dtos.kt`(`StepDto`에 `departsAt`, `arrivesAt`, `trainNo`, `isExpress`, `destination`, `source`, `estimated` — 모두 기본값), `.../ui/routes/RouteCardUi.kt`·`RoutesScreen.kt`·`.../ui/routedetail/RouteDetailScreen.kt`·`StepText.kt`(요금 문구 삭제, 지하철 단계에 "08:03 출발 → 08:17 도착 · 5101 열차", 급행이면 "급행", 어림이면 "(어림)"), `.../ui/common/Format.kt`(`formatFare` 삭제), `.../ui/common/Copy.kt`
- Test: `android/app/src/test/.../ui/routes/*Test.kt`, `.../ui/routedetail/*Test.kt`

- [ ] **Step 1: 실패하는 테스트** — 경로 카드 문구에 "원"이 없음, 단계 문구 "08:03 출발 → 08:17 도착", 급행·어림 표시.
- [ ] **Step 2–4:** 실패 확인 → 구현 → `./gradlew.bat testDebugUnitTest lintDebug` 통과(lint 오류 0).
- [ ] **Step 5: 커밋** — `feat(android): 요금 표시를 빼고 구간마다 열차 시각 표시`

### Task 7: 문서·학습 노트

**Files:** `README.md`(카카오 키·한도 삭제, 데이터 출처에서 카카오 삭제), `docs/ml/collector.md`(운행표 매일), `docs/learning/99-…`~`104-…`(Task 1~6마다).

- [ ] **Step 1:** 문서 고치기. **Step 2:** 서버·앱 전체 테스트 통과 확인. **Step 3: 커밋** — `docs: 경로 직접 계산 운영 안내와 학습 노트`

---

## 측정 (Task 4에서 채움)

- 2026-11-18 `scripts/measure_raptor.py`(개발 PC): 정차 576,460개(19노선 × 37역 × 2방향 × 요일 3), gzip 2.7MB. 올리는 동안 최대 61MB, 올린 뒤 8MB. 검색 30번 모두 경로를 찾았고 중앙 0.001초, 최대 0.024초.

## 실행 뒤 사용자에게 물을 것

1. 푸시, VM 반영(타이머가 매일로 바뀜), Render 배포(커밋 지정).
2. Render 환경 변수 `KAKAO_REST_API_KEY`는 지워도 된다(남아 있어도 무해).
