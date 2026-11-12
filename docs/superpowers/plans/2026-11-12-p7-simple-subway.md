# P7. 간단한 지하철 앱 (노선도, 루틴, 시간표) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지도·장소·위치 결합을 걷어 내고, 노선도 홈에서 역을 골라 경로·다음 열차·루틴 알림까지 쓰는 간단한 지하철 앱으로 만든다.

**Architecture:** 서버는 역 id로 경로를 받고(지하철만, 빠른 순, 경유 합성), 실시간이 없으면 서울시 시간표로 대신한다. 앱은 홈을 노선도(공개 SVG + 역 위치표)로 바꾸고, 역 시트·경로·루틴 화면을 기존 세션 엔진(승차 전·하차 알림) 위에 얹는다.

**Tech Stack:** 서버 Python 3.13, FastAPI, httpx, pydantic v2, `holidays`(새), pytest, respx / 앱 Kotlin, Compose, Navigation, Room, AlarmManager, AndroidSVG(새)

**Spec:** `docs/superpowers/specs/2026-11-12-simple-subway-redesign.md` (이하 "재설계 스펙"). 바뀌지 않은 부분은 1차 스펙 `2026-11-02-jigeumchulbal-mvp-design.md`.

**경로 표기:** `<pkg>/` = `android/app/src/main/java/io/github/klanary/jigeumchulbal/`, `<test>/` = `android/app/src/test/java/io/github/klanary/jigeumchulbal/`. 서버 경로는 `server/`부터.

**브랜치:** 모두 `develop`에서. 작업마다 커밋, 묶음(서버 S1~S6, 앱 정리 A1~A3, 노선도 A4~A6, 경로 A7~A8, 루틴 A9~A11, 마감 F1~F2)이 끝날 때마다 푸시해도 되는지 사용자에게 묻는다.

**일정(재설계 스펙 11절):** 11/13 S1~S5·A1~A3 / 11/14 S6·A4 / 11/15 A5·A6(A4 판정) / 11/16 A7·A8 / 11/17~18 A9~A11 / 11/19 F1 / 11/20 F2.

## Global Constraints

- 출발·경유·도착은 역 id만 쓴다. 장소·좌표 입력은 없다. (D6)
- 다음 열차 출처: "지금"은 서울시 실시간 → 없으면 서울시 시간표(`source: "timetable"`) → 비서울 5개 노선은 미제공. "지금 + 30분 이후"나 시각 지정은 시간표. (5.1)
- 비서울 5개 노선(정규화 이름): `인천1호선`, `인천2호선`, `에버라인선`, `의정부선`, `김포골드라인`. (D4)
- 시간표 요일: 공휴일(대체공휴일·선거일 포함)·일요일 3, 토요일 2, 그 밖 1. 공휴일은 `holidays`의 KR. 새벽 0~4시에는 전날 요일 시간표도 본다. "25:00:00"은 다음 날 01:00. 그 역에서 끝나는 열차(`DESTSTATION`이 그 역)는 뺀다. 기준 시각은 `LEFTTIME`. (5.2)
- 시간표 캐시: 역 코드·요일·방향별 86,400초. 한도 `SEOUL_TIMETABLE_DAILY_LIMIT` 기본 1,000. 일반 인증키 `SEOUL_OPENAPI_KEY`. (6)
- 경로: 버스가 든 경로는 뺀다. `totalSec` 오름차순, 같으면 `transfers` 오름차순, 최대 5개. 경유는 구간별 지하철 경로 상위 3개 × 3개 → 빠른 순 5개, `legs` 2개. 경로 캐시 키에 경유를 넣고 `departAt`은 넣지 않는다. (5.3)
- 환승·하차 알림은 사용자가 진동 알림을 켰을 때만. 승차 전 "지금 출발하세요"는 루틴·[지금 출발 알림]에서 항상 진동. (D2, 5.4, 5.5)
- 루틴은 기기에만 저장. 다음 실행 시각 하나를 `setAlarmClock`으로 예약하고, 울리면 다음 것을 다시 예약. 진행 중 세션이 있으면 건너뛰고 "진행 중인 통학 알림이 있어 루틴을 건너뛰었어요". 루틴을 켜려면 알림 권한 필요. 걷는 시간 0~30분, 기본 5분. (5.4, 4.6)
- 노선도 확대 0.5배~4배, 역 누르기 판정 24dp. 출처 표시: 위키미디어 공용 `Seoul_subway_linemap_ko.svg`(퍼블릭 도메인, IRTC1015). (4.1, 8)
- 사용자에게 보이는 문구는 한국어이며 재설계 스펙의 문구를 그대로 쓴다. 키는 저장소에 넣지 않는다.
- 작업마다 "실패하는 테스트 → 구현 → 통과 → 커밋"과 학습 노트 초안 `docs/learning/NN-주제.md`(다음 번호 47).

## Review Focus

1. 새벽 0시 30분에 "지금" 다음 열차를 찾음 → 전날 시간표의 자정 넘은 열차("24:40:00")가 나와야 한다. (S3 `test_after_midnight_uses_previous_service_day`)
2. 루틴 시각이 오늘 이미 지났거나 오늘 요일이 아님 → 다음 해당 요일로 예약되어야 하고, 요일이 하나도 없으면 예약하지 않는다. (A9 `nextOccurrence` 테스트들)
3. 출발역 = 도착역, 또는 경유역이 출발·도착과 같음 → 서버 400 `INVALID_REQUEST`(`SAME_STATION`), 앱은 그 버튼을 막는다. (S5 `test_same_station_rejected`)
4. 카카오가 버스 경로만 줌(지하철 경로 0개) → 404 `NO_ROUTE`(`NO_SUBWAY`)와 "지하철로 갈 수 있는 경로가 없어요". (S5 `test_bus_only_is_no_subway`)
5. 루틴이 울릴 때 이미 다른 세션이 진행 중 → 기존 세션을 건드리지 않고 건너뛰는 알림만. (A10 `routineSkippedWhenSessionRunning`)

---

## 서버

### Task S1: 장소 API 삭제와 가까운 역 API

**Files:**
- Delete: `server/app/api/places.py`, `server/app/clients/kakao_local.py`, `server/app/services/place_service.py`, `server/tests/integration/test_places.py`, `server/tests/fixtures/kakao_keyword_*.json`, `server/tests/fixtures/kakao_coord2address_*.json`
- Modify: `server/app/core/container.py`(places와 `kakaoKeyword`·`kakaoCoord2Address` 한도 삭제), `server/app/main.py`, `server/app/models/schemas.py`(`Place*`, `ReverseResponse` 삭제, `NearestStation` 유지), `server/app/api/stations.py`, `server/app/core/config.py`·`.env.example`(카카오 로컬 한도 삭제), `server/scripts/capture_fixtures.py`(카카오 로컬 항목 삭제)
- Test: `server/tests/integration/test_stations_nearest.py`

**Interfaces:**
- Produces: `GET /v1/stations/nearest?lat=&lng=` → `NearestResponse(station: NearestStation | None)`. `lat`·`lng`는 필수, 범위 밖이면 400 `PARAMS`.

- [ ] **Step 1: 실패하는 테스트**

```python
async def test_nearest_station(client):
    body = (await client.get("/v1/stations/nearest", params={"lat": 37.5665, "lng": 126.9780})).json()
    assert body["station"]["id"] == "시청" and body["station"]["distanceM"] < 400

async def test_nearest_none_far_away(client):
    assert (await client.get("/v1/stations/nearest", params={"lat": 37.0, "lng": 125.0})).json() == {"station": None}

async def test_places_api_removed(client):
    assert (await client.get("/v1/places/search", params={"q": "a"})).status_code == 404
```

- [ ] **Step 2: 실패 확인** — `pytest tests/integration/test_stations_nearest.py -q` / Expected: FAIL
- [ ] **Step 3: 구현** — 삭제 목록을 지우고 `stations.py`에 엔드포인트를 더한다(`services.stations.nearest`).
- [ ] **Step 4: 통과 확인** — `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: 커밋** — `feat(server): 장소 API를 지우고 가까운 역 API를 더함`

### Task S2: 서울시 역 코드 표

**Files:**
- Create: `server/scripts/fetch_station_info.py`, `server/data/raw/station_info.json`(생성물, 커밋), `server/tests/fixtures/station_info_sample.json`
- Modify: `server/scripts/build_stations.py`, `server/app/services/station_index.py`, `server/app/services/normalize.py`
- Test: `server/tests/unit/test_station_codes.py`

**Interfaces:**
- Produces: SQLite 표 `station_codes(station_id TEXT, line_name TEXT, station_cd TEXT, fr_code TEXT, PRIMARY KEY(station_id, line_name))`
- Produces: `StationIndex.station_code(station_id: str, line: str) -> str | None`, `StationIndex.realtime_supported(line: str) -> bool`(비서울 5개 노선이면 False), 상수 `NON_SEOUL_LINES: frozenset[str]`
- Produces: `normalize_line`이 서울시 역 정보의 표기(`"05호선"`, `"경의선"`, `"인천선"`, `"용인경전철"`, `"의정부경전철"`, `"김포도시철도"`, `"우이신설경전철"`, `"공항철도"`)를 우리 호선으로 바꾼다
- `fetch_station_info.py`: `SEOUL_OPENAPI_KEY`로 `SearchInfoBySubwayNameService/1/1000/`을 받아 저장(799행). `build_stations`는 세 번째 인자로 이 파일을 받아 역 이름(`resolve(이름, 호선)`)과 호선으로 잇는다. 잇지 못한 행은 개수와 이름을 출력한다.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_line_names_from_station_info():
    assert [normalize_line(x) for x in ["05호선", "경의선", "인천선", "용인경전철", "김포도시철도"]] == \
        ["5호선", "경의중앙선", "인천1호선", "에버라인선", "김포골드라인"]

def test_station_code_lookup(real_index):   # 코드 값은 받은 station_info.json에서 확인해 맞춘다
    assert real_index.station_code("영등포구청", "5호선") == "2524"
    assert real_index.station_code("홍대입구", "2호선") == "0239"
    assert real_index.station_code("홍대입구", "9호선") is None

def test_realtime_supported():
    assert not StationIndex.realtime_supported("인천1호선") and StationIndex.realtime_supported("5호선")
```

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_station_codes.py -q` / FAIL
- [ ] **Step 3: 구현** — 스크립트 둘, 표, 조회 함수. 실제 파일을 받아 `stations.sqlite`를 다시 빌드한다. 서울 노선(비서울 5개 제외) 역-호선 쌍 중 코드가 없는 비율이 5%를 넘으면 그 목록을 `api-verification.md` §2에 적고 별칭을 더한다.
- [ ] **Step 4: 통과 확인** — `pytest -q && ruff check .` / 통과
- [ ] **Step 5: 커밋** — `feat(server): 서울시 역 코드 표를 역 데이터에 더함`

### Task S3: 시간표 해석과 시간표 API

**Files:**
- Create: `server/app/clients/seoul_timetable.py`, `server/app/services/timetable.py`, `server/app/services/timetable_service.py`, `server/tests/unit/test_timetable.py`, `server/tests/integration/test_timetable_api.py`, `server/tests/fixtures/seoul_timetable_yeongdeungpo_weekday_up.json`(실제 응답, 키 가림)
- Modify: `server/pyproject.toml`(`holidays`), `server/app/core/config.py`·`.env.example`(`seoul_openapi_key`, `seoul_timetable_daily_limit`), `server/app/core/container.py`, `server/app/api/stations.py`, `server/app/models/schemas.py`, `server/scripts/capture_fixtures.py`

**Interfaces:**
- Produces (`timetable.py`, 순수 함수):
  - `service_days(at: datetime) -> list[tuple[date, int]]` — 볼 시간표 날짜와 요일 코드. 보통 `[(오늘, 코드)]`, 0~4시면 `[(어제, 어제 코드), (오늘, 오늘 코드)]`
  - `week_tag(day: date) -> int` — 공휴일·일요일 3, 토요일 2, 그 밖 1
  - `parse_hms(value: str, service_day: date) -> datetime | None` — "25:00:00"은 다음 날 01:00, 한국 시간
  - `departures(rows: list[dict], station_cd: str, service_day: date, after: datetime, count: int) -> list[TimetableTrain]`
- Produces (`schemas.py`): `TimetableTrain(departs_at: datetime, destination: str | None, is_express: bool, train_no: str | None)`, `TimetableResponse(station_id: str, line: str, direction: str, trains: list[TimetableTrain])`
- Produces: `SeoulTimetableClient.fetch(station_cd: str, week_tag: int, inout_tag: int) -> list[dict]`(`SearchInfoBySubwayNameService`와 같은 오류 형식 처리: `INFO-200`은 빈 목록), `TimetableService.trains(station_id: str, line: str, direction: str, after: datetime, count: int) -> list[TimetableTrain]`
- 방향 → `inout_tag`: `상행`·`내선` 1, `하행`·`외선` 2. 그 밖은 400 `PARAMS`.
- Produces: `GET /v1/stations/{id}/timetable?line=&direction=&from=&count=`(`from` 생략이면 지금, `count` 1~10 기본 4). 비서울 노선이나 코드 없는 역-호선은 `trains: []`.

- [ ] **Step 1: 실패하는 테스트**

```python
from app.core.clock import KST   # tzdata가 없어 ZoneInfo("Asia/Seoul")는 쓰지 않는다

def test_week_tag():
    assert [week_tag(date(2026, 11, d)) for d in (13, 14, 15)] == [1, 2, 3]   # 금·토·일
    assert week_tag(date(2026, 12, 25)) == 3                                  # 성탄절(금)
    assert week_tag(date(2026, 3, 2)) == 3                                    # 삼일절 대체공휴일(월)

def test_parse_past_midnight():
    assert parse_hms("25:00:00", date(2026, 11, 13)) == datetime(2026, 11, 14, 1, 0, tzinfo=KST)

def test_after_midnight_uses_previous_service_day():
    days = service_days(datetime(2026, 11, 14, 0, 30, tzinfo=KST))           # 토요일 새벽
    assert days == [(date(2026, 11, 13), 1), (date(2026, 11, 14), 2)]

def test_departures_skip_terminating_and_sort(real_rows):
    trains = departures(real_rows, "2524", date(2026, 11, 13), datetime(2026, 11, 13, 8, 0, tzinfo=KST), 4)
    assert len(trains) == 4 and trains == sorted(trains, key=lambda t: t.departs_at)
    assert all(t.departs_at >= datetime(2026, 11, 13, 8, 0, tzinfo=KST) for t in trains)

async def test_timetable_endpoint(client, respx_mock):   # 실제 샘플로 응답
    r = await client.get("/v1/stations/영등포구청/timetable", params={"line": "5호선", "direction": "상행", "from": "2026-11-13T08:00:00+09:00"})
    assert r.status_code == 200 and len(r.json()["trains"]) == 4

async def test_timetable_cached_per_day(client, respx_mock): ...   # 같은 날 두 번 → 외부 호출 1번
```

- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_timetable.py tests/integration/test_timetable_api.py -q` / FAIL
- [ ] **Step 3: 구현** — 위 시그니처. 종착 열차는 `DESTSTATION == station_cd` 또는 `LEFTTIME == "00:00:00"`.
- [ ] **Step 4: 통과 확인** — `pytest -q && ruff check .` / 통과. 실제 키로 영등포구청 시간표 한 번 확인.
- [ ] **Step 5: 커밋** — `feat(server): 서울시 시간표 해석과 시간표 API`

### Task S4: 실시간 도착의 시간표 대체

**Files:**
- Modify: `server/app/services/arrival_service.py`, `server/app/models/schemas.py`, `server/app/core/container.py`
- Test: `server/tests/integration/test_arrivals.py`(추가)

**Interfaces:**
- Consumes: S2 `realtime_supported`, `station_code`; S3 `TimetableService.trains`
- Produces: `ArrivalGroup.source: Literal["realtime", "timetable", "none"]`(기본 `"realtime"`)
- 규칙: 역의 서울 노선마다 두 방향(`상행`·`하행`, 2호선은 `내선`·`외선`)을 본다. 실시간 그룹이 없는 방향(역 전체 `INFO-200` 포함)은 그 방향 시간표 2대로 그룹을 만든다(`source: "timetable"`, `towards: None`). 비서울 노선은 호선마다 `trains: []`, `direction: None`, `source: "none"` 그룹 하나. 시간표도 비면 그 방향 그룹은 넣지 않는다.

- [ ] **Step 1: 실패하는 테스트**

```python
async def test_no_realtime_falls_back_to_timetable(client, seoul, timetable_mock):
    seoul("seoul_realtime_nodata.json")
    groups = (await client.get(ARRIVALS.format("영등포구청"))).json()["groups"]
    assert groups and {g["source"] for g in groups} == {"timetable"} and all(len(g["trains"]) <= 2 for g in groups)

async def test_missing_direction_only_is_filled(client, seoul, timetable_mock):   # 실시간에 상행만 있는 샘플 변형
    ...
    assert {(g["direction"], g["source"]) for g in groups} == {("상행", "realtime"), ("하행", "timetable")}

async def test_non_seoul_line_is_none(client, seoul):
    seoul("seoul_realtime_nodata.json")
    groups = (await client.get(ARRIVALS.format("계양"))).json()["groups"]
    assert {(g["lineName"], g["source"]) for g in groups if g["source"] == "none"} == {("인천1호선", "none")}

async def test_realtime_groups_marked(client, seoul):
    seoul(SAMPLE)
    assert {g["source"] for g in (await client.get(ARRIVALS.format("서울"))).json()["groups"]} == {"realtime"}
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `pytest -q && ruff check .` 통과
- [ ] **Step 5: 커밋** — `feat(server): 실시간이 없으면 시간표로 다음 열차를 대신함`

### Task S5: 역 입력 경로, 지하철만, 빠른 순, 다음 열차

**Files:**
- Modify: `server/app/api/routes.py`, `server/app/services/route_service.py`, `server/app/services/route_convert.py`, `server/app/models/schemas.py`, `server/app/core/errors.py`(`SAME_STATION`, `NO_SUBWAY` 문구)
- Test: `server/tests/integration/test_routes.py`(다시 씀), `server/tests/unit/test_route_convert.py`(추가)

**Interfaces:**
- Produces: `GET /v1/routes?from=<역 id>&to=<역 id>&via=<역 id>&departAt=<ISO>` — 없는 역 404 `NOT_FOUND`, 같은 역 400 `SAME_STATION`
- Produces: `RouteService.search(from_id: str, to_id: str, via_id: str | None = None, depart_at: datetime | None = None) -> tuple[RoutesResponse, str]`
- Produces (`route_convert.py`): `subway_only(routes: list[Route]) -> list[Route]`, `rank(routes: list[Route], limit: int = 5) -> list[Route]`
- Produces (`schemas.py`): `Route.depart_at: datetime | None = None`, `Route.arrive_at: datetime | None = None`; `FirstBoarding.next_trains`를 채운다(S4의 "지금" 규칙, 2대)
- 카카오에 넘기는 이름은 역의 화면 이름(`s_name`, `e_name`).

- [ ] **Step 1: 실패하는 테스트**

```python
async def test_routes_by_station_ids(client, kakao_route, seoul):
    kakao_route("kakao_route_subway_only.json"); seoul(SAMPLE)
    body = (await client.get("/v1/routes", params={"from": "홍대입구", "to": "강남"})).json()
    secs = [r["totalSec"] for r in body["routes"]]
    assert secs == sorted(secs) and all(r["type"] in ("SUBWAY", "WALK") for r in body["routes"])
    assert body["routes"][0]["firstBoarding"]["nextTrains"] is not None

async def test_same_station_rejected(client):
    r = await client.get("/v1/routes", params={"from": "홍대입구", "to": "홍대입구"})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "SAME_STATION"

async def test_bus_only_is_no_subway(client, respx_mock):   # 버스 경로만 남긴 샘플 변형
    ...
    assert r.status_code == 404 and r.json()["error"]["reason"] == "NO_SUBWAY"

async def test_unknown_station_404(client):
    assert (await client.get("/v1/routes", params={"from": "없는역", "to": "강남"})).status_code == 404
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `pytest -q && ruff check .` 통과. 실제 키로 "마포 → 공덕" 확인.
- [ ] **Step 5: 커밋** — `feat(server): 역 입력 경로, 지하철만, 빠른 순, 첫 탑승 다음 열차`

### Task S6: 경유역 합성과 출발 시각 지정

**Files:**
- Modify: `server/app/services/route_service.py`, `server/app/services/route_convert.py`
- Test: `server/tests/integration/test_routes.py`(추가), `server/tests/unit/test_route_convert.py`(추가)

**Interfaces:**
- Produces (`route_convert.py`): `combine_via(first: list[Route], second: list[Route], limit: int = 5) -> list[Route]` — 각 상위 3개 조합, 합계는 두 구간의 합, 경유지 하차는 환승으로 세지 않음, `firstBoarding`은 첫 구간 기준, `legs` 2개
- 출발 시각: `depart_at`이 있으면 각 경로의 `next_trains`를 S3 시간표(`depart_at + sec_to_boarding` 이후 2대)로 바꾸고, `depart_at` = 첫 열차 출발, `arrive_at` = 그 시각 + (`total_sec` − `sec_to_boarding`). 시간표가 없으면 둘 다 `None`.

- [ ] **Step 1: 실패하는 테스트**

```python
def test_combine_via_sums_and_orders(...):
    out = combine_via(first, second)
    assert len(out) <= 5 and [r.total_sec for r in out] == sorted(r.total_sec for r in out)
    assert all(len(r.legs) == 2 for r in out) and out[0].transfers == first_best.transfers + second_best.transfers

async def test_via_calls_kakao_twice_and_caches(client, respx_mock): ...   # 같은 요청 두 번 → 카카오 2번(첫 요청만)
async def test_depart_at_uses_timetable(client, kakao_route, timetable_mock):
    r = (await client.get("/v1/routes", params={"from": "영등포구청", "to": "여의도", "departAt": "2026-11-13T08:00:00+09:00"})).json()
    first = r["routes"][0]
    assert first["departAt"] >= "2026-11-13T08:00" and first["arriveAt"] > first["departAt"]
async def test_via_equal_to_end_rejected(client): ...   # via == to → 400 SAME_STATION
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `pytest -q && ruff check .` 통과
- [ ] **Step 5: 커밋** — `feat(server): 경유역 합성과 출발 시각 지정`

---

## 앱 정리

### Task A1: 지도·장소·내 장소·즐겨찾는 역 걷어 내기

**Files:**
- Delete: `<pkg>/map/`, `<pkg>/ui/mappick/`, `<pkg>/data/repository/PlaceRepository.kt`, `<pkg>/data/repository/MyPlaceRepository.kt`, `<pkg>/data/local/MyPlaceDao.kt`, `<pkg>/data/local/FavoriteStationDao.kt`, 해당 테스트
- Modify: `android/gradle/libs.versions.toml`·`android/app/build.gradle.kts`·`android/settings.gradle.kts`(카카오 지도 SDK와 저장소, `KAKAO_NATIVE_APP_KEY` 삭제), `<pkg>/App.kt`(SDK 초기화 삭제), `<pkg>/ui/routedetail/RouteDetailScreen.kt`(지도 삭제, 단계 목록만), `<pkg>/data/local/AppDatabase.kt`(버전 2, `fallbackToDestructiveMigration(dropAllTables = true)`), `<pkg>/data/local/Entities.kt`, `<pkg>/data/repository/FavoriteRepository.kt`(역 즐겨찾기 삭제), `<pkg>/ui/settings/*`(내 장소 삭제), `<pkg>/ui/home/*`, `<pkg>/ui/search/*`(장소 결과 삭제), `<pkg>/ui/station/*`(★ 삭제), `<pkg>/di/AppContainer.kt`, `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/Destinations.kt`(`MapPickDest` 삭제), `<pkg>/ui/common/Copy.kt`, `android/app/src/test/resources/robolectric.properties`(카카오 회피 설정이 필요 없으면 정리)

- [ ] **Step 1:** 지울 화면을 가리키는 테스트(`MapPickViewModelTest`, 장소·내 장소 테스트)를 지우고, 남는 테스트 중 지울 코드를 쓰는 부분을 고친다.
- [ ] **Step 2:** 위 파일을 지우고 고친다.
- [ ] **Step 3: 통과 확인** — `cd android && ./gradlew testDebugUnitTest assembleDebug` / `BUILD SUCCESSFUL`. APK에 `libK3fAndroid.so`가 없는지 `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c K3f` = 0.
- [ ] **Step 4: 커밋** — `refactor(android): 카카오 지도·장소·내 장소·즐겨찾는 역을 걷어 냄`

### Task A2: 위치 결합·열차 추적 걷어 내기

**Files:**
- Delete: `<pkg>/tracking/`, `<pkg>/session/TrackingPoller.kt`, `<pkg>/data/repository/TrainRepository.kt`, `<pkg>/ui/routedetail/BoardingPromptViewModel.kt`, `<pkg>/ui/session/TrainPicker.kt`, 해당 테스트
- Modify: `<pkg>/session/CommuteSession.kt`(사건 `BoardedDetected`·`TrainEtaUpdated`·`TrainLookupFailed`·`TrainIdentified`, 알림 `TrainDetected`·`PickTrain`, `Riding.tracking` 삭제), `<pkg>/session/SessionModel.kt`(`Tracking`·`DetectedTrain`·`TrainSource` 삭제, `AlightPoint`의 추적용 칸은 남겨도 됨), `<pkg>/session/SessionJobs.kt`(할 일은 `NONE`·`WAIT`만), `<pkg>/session/SessionService.kt`(유형 `dataSync`만), `<pkg>/session/SessionNotifier.kt`·`AlertText.kt`·`OngoingContent.kt`, `<pkg>/session/SessionIntents.kt`(`ACTION_PICK_TRAIN` 삭제), `<pkg>/MainActivity.kt`, `<pkg>/ui/Destinations.kt`(`SessionDest`는 인자 없는 `object`로 되돌리고 모든 `navigate(SessionDest())`를 `navigate(SessionDest)`로), `<pkg>/data/remote/ApiService.kt`·`Dtos.kt`(열차 위치 API와 DTO 삭제), `AndroidManifest.xml`(`FOREGROUND_SERVICE_LOCATION` 삭제, 서비스 유형 `dataSync`)

**Interfaces:**
- Produces: `SessionJob { NONE, WAIT }`. 옛 저장 세션 JSON(추적 칸 포함)은 `ignoreUnknownKeys`로 그대로 읽힌다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun oldRidingJsonWithTrackingStillLoads() = runTest {
    val store = testSessionStore(rawJson = OLD_RIDING_WITH_TRACKING_JSON)   // 2026-11-05 형식 그대로
    assertTrue(store.load() is SessionState.Riding)
}
@Test fun sessionNavigationRoundTrips() { nav.navigate(SessionDest); assertEquals(SessionDest::class.qualifiedName, nav.currentDestination!!.route) }
```

- [ ] **Step 2~4:** 실패 확인 → 지우고 고치기 → `./gradlew testDebugUnitTest assembleDebug` 통과
- [ ] **Step 5: 커밋** — `refactor(android): 위치 결합·열차 추적을 걷어 냄`

### Task A3: 역 기반 API 계층

**Files:**
- Modify: `<pkg>/data/remote/ApiService.kt`, `<pkg>/data/remote/Dtos.kt`, `<pkg>/data/repository/RouteRepository.kt`, `<pkg>/data/repository/StationRepository.kt`, `<pkg>/data/model/Endpoint.kt`(삭제하고 아래로 대체), `<pkg>/ui/routeedit/RouteFlowViewModel.kt`, `<test>/fakes/Fakes.kt`
- Test: `<test>/data/remote/DtoParsingTest.kt`(추가), `<test>/data/repository/RouteRepositoryTest.kt`

**Interfaces:**
- Produces: `@Serializable data class StationRef(val id: String, val name: String)` (`<pkg>/data/model/StationRef.kt`)
- Produces: `RouteRepository.routes(from: StationRef, to: StationRef, via: StationRef? = null, departAt: Instant? = null): ApiResult<RoutesDto>`
- Produces: `StationRepository.timetable(stationId: String, line: String, direction: String, from: Instant?, count: Int = 4): ApiResult<TimetableDto>`, `StationRepository.nearest(lat: Double, lng: Double): ApiResult<StationDto?>`
- Produces (`Dtos.kt`): `ArrivalGroupDto.source: String = "realtime"`(`"timetable"`, `"none"`), `TimetableTrainDto(departsAt: String, destination: String?, isExpress: Boolean, trainNo: String?)`, `TimetableDto(stationId, line, direction, trains)`, `RouteDto.departAt: String? = null`, `RouteDto.arriveAt: String? = null`

- [ ] **Step 1: 실패하는 테스트** — S4·S5·S3의 실제 서버 응답 JSON(서버 테스트에서 저장한 것)을 읽어 DTO로 바뀌는지, `RouteRepository`가 `from`·`to`·`via`·`departAt` 쿼리를 보내는지(`RecordingApiService`).
- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest` 통과
- [ ] **Step 5: 커밋** — `feat(android): 역 기반 경로·시간표·가까운 역 API`

---

## 노선도

### Task A4: 노선도 역 위치 추출 (판정)

**Files:**
- Create: `server/scripts/build_linemap.py`, `server/data/raw/Seoul_subway_linemap_ko.svg`(원본, 커밋), `server/tests/unit/test_build_linemap.py`, `server/tests/fixtures/linemap_snippet.svg`(원본에서 역 3~4개 부분을 잘라 낸 것), `android/app/src/main/assets/linemap/linemap.svg`, `android/app/src/main/assets/linemap/stations.json`

**Interfaces:**
- Produces (`stations.json`): `{"width": 5724, "height": 6516, "stations": [{"id": "홍대입구", "x": 1234.5, "y": 2345.6}]}` — x·y는 SVG 좌표, 역 id는 `stations.sqlite`의 id
- Produces: CLI `python -m scripts.build_linemap data/raw/Seoul_subway_linemap_ko.svg data/stations.sqlite ../android/app/src/main/assets/linemap` → "역 N개 연결 / 우리 역 655개 중 M% / 잇지 못한 이름: …"
- 방법: `<text>`(정의 안에 있고 `<use>`로 배치된 것 포함, `transform`·`x`·`y` 반영)의 글자를 역 이름으로 보고 `StationIndex.resolve`로 잇는다. 같은 역의 글자가 여럿이면 평균 위치. 역 표시 원(`<circle>`·`<use>`)이 글자 근처에 있으면 그 중심을 쓴다.

- [ ] **Step 1: 실패하는 테스트** — `linemap_snippet.svg`에서 역 3개가 알려진 좌표(±5)로 나오는지, 같은 입력이면 같은 JSON인지.
- [ ] **Step 2~3:** 실패 확인 → 구현 → 실제 SVG로 실행.
- [ ] **Step 4: 판정** — 우리 역 655개 중 **95% 이상** 이으면 계속. 아니면 결과와 잇지 못한 목록을 사용자에게 보이고, 재설계 스펙 D8의 대안(역 좌표 + `FR_CODE` 순서로 그리기)으로 이 작업을 다시 쓸지 묻는다.
- [ ] **Step 5: 출처 표기** — README에 "노선도: 위키미디어 공용 Seoul_subway_linemap_ko.svg (퍼블릭 도메인, IRTC1015, 2025-06-29 판)". 앱 설정 화면 맨 아래에도 같은 한 줄.
- [ ] **Step 6: 커밋** — `feat: 노선도 역 위치 추출(공개 노선도 SVG)`

### Task A5: 노선도 홈 화면

**Files:**
- Create: `<pkg>/ui/linemap/LineMapData.kt`, `<pkg>/ui/linemap/LineMapGeometry.kt`, `<pkg>/ui/linemap/LineMapScreen.kt`, `<pkg>/ui/linemap/LineMapViewModel.kt`, `<test>/ui/linemap/LineMapGeometryTest.kt`, `<test>/ui/linemap/LineMapDataTest.kt`
- Modify: `android/gradle/libs.versions.toml`·`app/build.gradle.kts`(AndroidSVG), `<pkg>/ui/AppNavHost.kt`(시작 화면 = 노선도, `HomeDest` 자리), `<pkg>/ui/home/`(삭제하거나 노선도의 아래 패널로 옮김), `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Produces: `data class MapStation(val id: String, val x: Float, val y: Float)`, `fun parseLineMapStations(json: String): LineMapStations`(width, height, stations)
- Produces (`LineMapGeometry.kt`, 순수): `data class MapTransform(val scale: Float, val offsetX: Float, val offsetY: Float)`, `fun MapTransform.toMap(screenX: Float, screenY: Float): Pair<Float, Float>`, `fun MapTransform.zoomAround(focusX: Float, focusY: Float, factor: Float, min: Float = 0.5f, max: Float = 4f): MapTransform`, `fun hitStation(stations: List<MapStation>, mapX: Float, mapY: Float, radiusMap: Float): MapStation?`, `fun MapTransform.centerOn(x: Float, y: Float, viewW: Float, viewH: Float): MapTransform`
- 화면: SVG를 한 번 `Picture`로 그려 두고 `transformable`·`detectTapGestures`(두 번 누르면 2배)로 움직인다. 위에 역 검색창, 오른쪽 위 설정과 [현재 위치](S1 가까운 역으로 `centerOn`하고 역 시트 열기, 위치 권한은 이 버튼을 누를 때만 요청, 2km 안에 역이 없으면 "가까운 역이 없어요"), 아래 접히는 패널(탭 [루틴] [자주 가는 경로], 내용은 A10·A11). 누른 역은 `onStationSelected(id)`로 역 시트(A6)를 연다. 누르기 판정 반경은 24dp를 지도 좌표로 바꾼 값(`24dp / scale`).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun zoomIsClamped() { assertEquals(4f, MapTransform(3f, 0f, 0f).zoomAround(100f, 100f, 2f).scale) }
@Test fun zoomKeepsFocusPointStill() { /* 확대 전후 같은 화면 점이 같은 지도 점을 가리킴 */ }
@Test fun hitPicksNearestWithinRadius() { assertEquals("공덕", hitStation(listOf(MapStation("공덕", 10f, 10f), MapStation("마포", 40f, 10f)), 18f, 12f, 12f)?.id) }
@Test fun hitMissesOutsideRadius() { assertNull(hitStation(listOf(MapStation("공덕", 10f, 10f)), 60f, 60f, 12f)) }
@Test fun bundledMapCoversStations() { /* assets의 stations.json을 읽어 600개 이상, 좌표가 width·height 안 */ }
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest assembleDebug` 통과. 실기기에 설치해 끌기·확대·역 누르기를 확인(직접 또는 adb 화면 캡처).
- [ ] **Step 5: 커밋** — `feat(android): 노선도 홈(끌기, 확대·축소, 역 누르기)`

### Task A6: 역 시트와 출발·경유·도착 고르기

**Files:**
- Create: `<pkg>/ui/linemap/StationSheet.kt`, `<pkg>/ui/linemap/StationSheetViewModel.kt`, `<pkg>/ui/linemap/TripDraft.kt`, `<test>/ui/linemap/StationSheetViewModelTest.kt`, `<test>/ui/linemap/TripDraftTest.kt`
- Modify: `<pkg>/ui/station/`(역 상세 화면은 시트로 대체하고 삭제), `<pkg>/ui/search/`(결과를 누르면 노선도가 그 역으로 가고 시트를 연다), `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Produces: `enum class SheetTime { NOW, PLUS_1H, PLUS_2H, CUSTOM }`, `StationSheetViewModel(stationId: String, stations: StationRepository, clock: () -> Long)` — `state: StateFlow<UiState<StationSheetUi>>`, `fun select(time: SheetTime, custom: Instant? = null)`. `NOW`면 `arrivals`(30초마다 갱신), 그 밖이면 방향별 `timetable(count = 4)`
- Produces: `data class StationSheetUi(val name: String, val lines: List<String>, val groups: List<DirectionTrains>, val unsupportedLines: List<String>, val fromTimetable: Boolean)`, `DirectionTrains(line, direction, towards, trains: List<String>)`(표시용 "방화행 3분 · 그다음 11분"·"19:32 방화행"). `unsupportedLines`는 `source == "none"` 그룹의 호선.
- 열차가 하나도 없으면(비서울 노선 제외) "열차 정보가 없어요".
- 검색(`<pkg>/ui/search/`): 역만, 최근 검색 역 최대 20개(`RecentKind`는 `STATION`만 남김). 결과를 누르면 노선도가 `centerOn`하고 시트를 연다.
- Produces (`TripDraft.kt`, 순수): `data class TripDraft(val from: StationRef? = null, val via: StationRef? = null, val to: StationRef? = null)`, `fun TripDraft.set(role: TripRole, station: StationRef): TripDraft`, `val TripDraft.ready: Boolean`(from·to 있고 셋이 서로 다름), `fun TripDraft.canSet(role: TripRole, station: StationRef): Boolean`. `enum class TripRole { FROM, VIA, TO }`
- 시트 버튼: [출발로] [도착으로], 출발·도착이 정해진 뒤 [경유로]. `ready`가 되면 경로 결과(A7)로. 위 띠 "출발: 마포 · 도착: (역을 고르세요)" + [취소].
- 비서울 노선 문구 "실시간 정보를 제공하지 않는 노선이에요", 시간표 그룹이면 "시간표 기준".

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun sameStationCannotBeFromAndTo() { assertFalse(TripDraft(from = MAPO).canSet(TripRole.TO, MAPO)) }
@Test fun viaMustDifferFromEnds() { assertFalse(TripDraft(MAPO, to = GONGDEOK).canSet(TripRole.VIA, GONGDEOK)) }
@Test fun nowShowsTwoTrainsPerDirection() = runTest { /* 가짜 arrivals → groups 각 2개 */ }
@Test fun plusOneHourUsesTimetable() = runTest { /* select(PLUS_1H) → timetable(from = now+1h) 호출, fromTimetable = true, 4대 */ }
@Test fun unsupportedLineMessage() = runTest { /* source "none" 그룹(인천1호선) → unsupportedLines = ["인천1호선"] */ }
@Test fun noTrainsMessage() = runTest { /* 그룹 없음 → "열차 정보가 없어요" */ }
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest assembleDebug` 통과
- [ ] **Step 5: 커밋** — `feat(android): 역 시트(다음 열차·나중 열차)와 출발·경유·도착 고르기`

---

## 경로

### Task A7: 경로 결과와 경로 상세 개편

**Files:**
- Modify: `<pkg>/ui/routes/RoutesScreen.kt`, `<pkg>/ui/routes/RouteCardUi.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`, `<pkg>/ui/routedetail/StepText.kt`, `<pkg>/ui/routeedit/RouteFlowViewModel.kt`(→ `TripDraft` 기반, 장소·핀 제거), `<pkg>/ui/common/Copy.kt`
- Test: `<test>/ui/routes/RouteCardUiTest.kt`, `<test>/ui/routeedit/RouteFlowViewModelTest.kt`

**Interfaces:**
- Consumes: A3 `RouteRepository.routes`, A6 `TripDraft`
- Produces: `RouteFlowViewModel.setDepartAt(at: Instant?)`(null = 지금), `fun firstBoardingLine(route: RouteDto, now: Instant): String` — "5호선 마포 승차 · 다음 열차 3분" 또는 출발 시각 지정 때 "08:14 출발 · 도착 약 08:46"
- 경로 상세 단계 목록(지도 없음): 탈 역과 방향 "5호선 방화 방면", 정거장 수, 환승역과 걸어서 갈아타는 시간, 경유역(두 `legs` 사이에 "경유: 공덕"), 내릴 역, 단계별 시간
- 경로 상세 버튼: [★ 자주 가는 경로](A11), [루틴으로 만들기](A10), [지금 출발 알림](기존 승차 전 알림), [하차 알림 시작] + "환승·하차 진동 알림" 스위치(기본 끔, 끄면 버튼 비활성)
- 빈 결과 "지하철로 갈 수 있는 경로가 없어요"(서버 `NO_SUBWAY`)

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun cardShowsFirstBoardingAndNextTrain() = assertEquals("5호선 마포 승차 · 다음 열차 3분", firstBoardingLine(route, now))
@Test fun cardShowsDepartAndArriveWhenScheduled() = assertEquals("08:14 출발 · 도착 약 08:46", firstBoardingLine(scheduled, now))
@Test fun departAtIsSentAndCleared() = runTest { /* setDepartAt(t) → routes(departAt = t), setDepartAt(null) → null */ }
@Test fun noSubwayErrorMessage() = runTest { /* NO_SUBWAY → "지하철로 갈 수 있는 경로가 없어요" */ }
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest assembleDebug` 통과
- [ ] **Step 5: 커밋** — `feat(android): 경로 결과·상세(출발 시각, 첫 탑승 열차, 지도 없음)`

### Task A8: 환승·하차 진동 알림을 켰을 때만

**Files:**
- Modify: `<pkg>/session/CommuteSession.kt`, `<pkg>/session/SessionController.kt`, `<pkg>/ui/session/StartSessionFlow.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`
- Test: `<test>/session/CommuteSessionTest.kt`

**Interfaces:**
- Produces: `SessionEvent.StartWaiting(…, alightAlerts: Boolean = false)`, `SessionState.Waiting.alightAlerts: Boolean = false`(옛 JSON은 false로 읽힘)
- 규칙: 대기 중 `Boarded` → `alightAlerts`가 true면 지금처럼 탑승 단계, false면 세션 끝(`EndReason.BOARDED_AT_STATION`). `StartRiding`은 사용자가 스위치를 켠 경우에만 화면이 보낸다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun boardedWithoutAlightAlertsEndsSession() { /* StartWaiting(alightAlerts=false) → Boarded → Idle, Finished(BOARDED_AT_STATION), 하차 알람 예약 없음 */ }
@Test fun boardedWithAlightAlertsStartsRiding() { /* alightAlerts=true → Riding, Alight 알람 예약 */ }
@Test fun oldWaitingJsonDefaultsToNoAlerts() = runTest { /* alightAlerts 칸 없는 JSON → false */ }
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest` 통과
- [ ] **Step 5: 커밋** — `feat(android): 환승·하차 알림은 진동 알림을 켰을 때만`

---

## 루틴과 자주 가는 경로

### Task A9: 루틴 저장과 예약

**Files:**
- Create: `<pkg>/routine/Routine.kt`, `<pkg>/routine/RoutineSchedule.kt`, `<pkg>/routine/RoutineDao.kt`, `<pkg>/routine/RoutineRepository.kt`, `<pkg>/routine/RoutineAlarmScheduler.kt`, `<test>/routine/RoutineScheduleTest.kt`, `<test>/routine/RoutineRepositoryTest.kt`
- Modify: `<pkg>/data/local/AppDatabase.kt`(버전 3, `routines` 표), `<pkg>/data/local/Entities.kt`, `<pkg>/di/AppContainer.kt`

**Interfaces:**
- Produces: `@Entity(tableName = "routines") data class RoutineEntity(id: Long, name: String, fromId: String, fromName: String, viaId: String?, viaName: String?, toId: String, toName: String, routeJson: String, days: Int /* 월=1<<0 … 일=1<<6 */, minuteOfDay: Int, walkMin: Int = 5, alightAlerts: Boolean = false, enabled: Boolean = true)`
- Produces (`RoutineSchedule.kt`, 순수): `fun nextOccurrence(days: Int, minuteOfDay: Int, after: ZonedDateTime): ZonedDateTime?`(days == 0이면 null, `after` 이후 처음, 같은 분은 다음 주기)
- Produces: `RoutineRepository`: `routines: Flow<List<RoutineEntity>>`, `suspend fun save(r: RoutineEntity): Long`, `suspend fun setEnabled(id: Long, enabled: Boolean)`, `suspend fun delete(id: Long)`, `suspend fun rescheduleAll(now: ZonedDateTime)`
- Produces: `RoutineAlarmScheduler(context, alarmManager)`: `schedule(routineId: Long, at: Long)`(`setAlarmClock`, 요청 코드 `5000 + routineId`, 정확한 알람이 안 되면 `setAndAllowWhileIdle`), `cancel(routineId: Long)`. 저장·켜기·끄기·삭제 때 repository가 부른다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
private val KST = ZoneId.of("Asia/Seoul")
@Test fun laterTodayIfDayMatches() = assertEquals(at(2026,11,13,8,10), nextOccurrence(FRI, 490, at(2026,11,13,7,0)))
@Test fun alreadyPassedTodayGoesToNextMatchingDay() = assertEquals(at(2026,11,16,8,10), nextOccurrence(MON or FRI, 490, at(2026,11,13,9,0)))
@Test fun sameMinuteIsNextCycle() = assertEquals(at(2026,11,20,8,10), nextOccurrence(FRI, 490, at(2026,11,13,8,10)))
@Test fun noDaysNeverFires() = assertNull(nextOccurrence(0, 490, at(2026,11,13,7,0)))
@Test fun sundayNightWrapsToMonday() = assertEquals(at(2026,11,16,0,10), nextOccurrence(MON, 10, at(2026,11,15,23,55)))
@Test fun savingEnabledRoutineSchedulesIt() = runTest { /* 가짜 scheduler에 5000+id로 예약 */ }
@Test fun disablingCancels() = runTest { ... }
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest` 통과
- [ ] **Step 5: 커밋** — `feat(android): 루틴 저장과 다음 실행 예약`

### Task A10: 루틴 실행과 루틴 화면

**Files:**
- Create: `<pkg>/routine/RoutineReceiver.kt`, `<pkg>/routine/RoutineRunner.kt`, `<pkg>/ui/routine/RoutineListScreen.kt`, `<pkg>/ui/routine/RoutineEditScreen.kt`, `<pkg>/ui/routine/RoutineEditViewModel.kt`, `<test>/routine/RoutineRunnerTest.kt`, `<test>/ui/routine/RoutineEditViewModelTest.kt`
- Modify: `AndroidManifest.xml`(`RoutineReceiver`, 그리고 `BOOT_COMPLETED`·`MY_PACKAGE_REPLACED`·`TIME_SET`·`TIMEZONE_CHANGED`에서 `rescheduleAll`), `<pkg>/session/BootReceiver.kt`, `<pkg>/ui/linemap/LineMapScreen.kt`(아래 패널 [루틴] 탭), `<pkg>/ui/routedetail/RouteDetailScreen.kt`([루틴으로 만들기]), `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/Destinations.kt`(`RoutineEditDest(routineId: Long? = null, routeJson: String? = null)`)

**Interfaces:**
- Produces: `RoutineRunner(controller: SessionController, repository: RoutineRepository, notifier: SessionNotifierPort, settings: SettingsStore, clock)`: `suspend fun run(routineId: Long)` — 루틴을 읽어 다음 실행을 다시 예약하고, 세션이 Idle이 아니면 건너뜀 알림, 아니면 `StartWaiting(target = 경로의 첫 탑승, mode = DEPARTURE_TIMING, route, travelSec = walkMin*60, settings, alightAlerts)`로 `start(inAppOnly = false)`
- Produces: 알림 문구 `AlertContent.RoutineSkipped` → "진행 중인 통학 알림이 있어 루틴을 건너뛰었어요"
- 루틴 편집: 경로(A6 고르기 → A7 결과에서 선택), 요일 칩 7개, 시각, 걷는 시간(0~30분), 진동 알림 스위치, "이 시각에 타게 될 열차: 08:14, 08:22"(A3 `timetable`, 시각 + 걷는 시간 이후 2대). 켜기 전에 알림 권한이 없으면 요청하고, 거부하면 켜지 않는다.
- 목록: 시각, 요일, "마포 → 공덕", 스위치, 길게 누르면 삭제. 정확한 알람 권한이 없으면 위에 "알림이 늦을 수 있어요" 띠와 [설정 열기].
- 노선도 아래 패널이 접혀 있으면 오늘 다음 루틴 한 줄 "08:10 마포 → 공덕 · 다음 열차 3분"(다음 열차는 A3 `arrivals`의 첫 탑승 방향).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun routineStartsDepartureTimingSession() = runTest { /* run(id) → controller 상태 Waiting, mode DEPARTURE_TIMING, travelSec 300 */ }
@Test fun routineSkippedWhenSessionRunning() = runTest { /* 이미 Riding → 상태 그대로, 알림 RoutineSkipped 1번 */ }
@Test fun runReschedulesNextOccurrence() = runTest { /* run 뒤 scheduler에 다음 주기 예약 */ }
@Test fun previewShowsTrainsAfterWalk() = runTest { /* 08:10 + 5분 이후 시간표 2대 */ }
@Test fun cannotEnableWithoutNotificationPermission() = runTest { ... }
```

- [ ] **Step 2~4:** 실패 확인 → 구현 → `./gradlew testDebugUnitTest assembleDebug` 통과. 실기기: 2분 뒤 루틴을 만들어 앱을 닫은 상태에서 "지금 출발하세요"가 오는지 확인(서비스 시작이 막히면 알림만 오고 앱을 열면 세션이 이어지는지).
- [ ] **Step 5: 커밋** — `feat(android): 루틴 실행(출발 타이밍 알림)과 루틴 화면`

### Task A11: 자주 가는 경로(역 기반)

**Files:**
- Modify: `<pkg>/data/local/Entities.kt`(`FavoriteRouteEntity`를 역 id·이름으로: `fromId, fromName, viaId?, viaName?, toId, toName, createdAt`), `<pkg>/data/repository/FavoriteRepository.kt`, `<pkg>/ui/home/FavoriteRouteSort.kt`(그대로 재사용), `<pkg>/ui/linemap/LineMapScreen.kt`([자주 가는 경로] 탭), `<pkg>/ui/routedetail/RouteDetailScreen.kt`([★])
- Test: `<test>/data/repository/FavoriteRepositoryTest.kt`, `<test>/ui/home/FavoriteRouteSortTest.kt`(그대로 통과)

**Interfaces:**
- Produces: `FavoriteRepository.add(from: StationRef, via: StationRef?, to: StationRef)`, `remove(id)`, `find(from, via, to): FavoriteRouteEntity?`, `recordUsageIfFavorite(from, via, to, at)`, `routes: Flow<List<FavoriteRouteEntity>>`
- 탭 목록을 누르면 지금 출발 경로 결과(A7).

- [ ] **Step 1~4:** 같은 역 조합 중복 저장 안 됨, 사용 기록으로 시간대 정렬(기존 테스트) → 구현 → 통과
- [ ] **Step 5: 커밋** — `feat(android): 자주 가는 경로를 역 기반으로`

---

## 마감

### Task F1: 서버 배포 (P2 Task 16, 직접 포함)

- [ ] (직접) Render 계정, 웹 서비스 생성(저장소 `server/` Dockerfile), 환경 변수 `SEOUL_REALTIME_API_KEY`, `SEOUL_OPENAPI_KEY`, `KAKAO_REST_API_KEY`
- [ ] 배포 URL의 `/health`, `/v1/stations/search?q=ㅎㄷ`, `/v1/routes?from=마포&to=공덕` 확인
- [ ] `android/local.properties`의 `server.baseUrl`을 배포 URL로 바꿔 실기기에서 확인(앱 깨우기 호출 포함)
- [ ] 커밋: `docs(deploy): 배포 주소와 확인 결과`(주소만, 키 없음)

### Task F2: 11/20 점검 (실기기)

- [ ] 재설계 스펙 10절 흐름: 노선도에서 역 고르기 → 역 시트 다음 열차·+1시간 → 경로(경유 포함) → 루틴 등록(2분 뒤) → 앱 닫고 "지금 출발하세요" → [탑승했어요] → (진동 알림 켠 경우) 하차 진동
- [ ] 결과를 `docs/measurements/device-checklist.md`에 적는다. 막힌 기능은 재설계 스펙 13절의 최소 버전으로 바꿀지 사용자에게 묻는다.
- [ ] 커밋: `docs(measurements): 11/20 실기기 점검`
