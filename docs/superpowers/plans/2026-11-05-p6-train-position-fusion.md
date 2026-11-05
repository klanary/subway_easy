# P6. 위치 결합 도착 정보와 탑승 열차 추적 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 5·6호선에서 같은 열차의 실시간 위치로 도착 시간을 다시 계산하고(⑬), 탄 열차를 정해(⑭) 내릴 역 도착 시각에 맞춰 하차·환승 알림을 옮긴다(⑪).

**Architecture:** 서버는 노선 표(`line_topology.json`)를 읽은 `Topology`로 열차 위치에서 목표 역까지 남은 시간을 계산하고(`eta.py`, 순수 함수), 도착 응답의 열차를 같은 열차 번호의 위치와 이어 계산 방식을 고른다(`fuse_trains`, 순수 함수). 위치정보는 호선별 30초 캐시를 거치고, 실패해도 도착 응답은 도착 정보만으로 나간다. 새 API 두 개(근처 열차, 탄 열차의 남은 시간)는 `TrainService`가 같은 캐시로 답한다. 앱은 탑승 상태에 `Tracking`을 더해 상태 머신(`CommuteSession`)이 새 사건으로 하차 시각을 옮기고, 세션 서비스가 상태에 맞는 일(대기 조회·추적 조회·탑승 감지)을 돌린다. 탑승 감지(`BoardingDetector`)와 탄 열차 고르기(`pickBoardedTrain`)도 순수 함수다.

**Tech Stack:** Python 3.12, FastAPI, httpx, pydantic v2, openpyxl(스크립트), pytest, respx / Kotlin, kotlinx.serialization, Retrofit, FusedLocationProviderClient(`requestLocationUpdates`), 포그라운드 서비스(`dataSync|location`), JUnit, kotlinx-coroutines-test, Robolectric

**Spec:** `docs/superpowers/specs/2026-11-05-train-position-fusion-design.md` (전체). 1차 스펙에서 바뀌는 절(6.2·6.4·6.5·6.7·8.2·8.3·8.6·10.1)은 그 문서 11절대로 이미 고쳐 두었다.

**기간:** 11/6 ~ 11/22 · **순서(스펙 10절):** P2 Task 3·4·8·9 → ⑬ Task 1~5 → P2 Task 10~12·P4 Task 10·13 → ⑪ Task 6, 8~12 → ⑭ Task 13~14 → 측정과 실기기 확인(Task 7 Step 5, S6·S7), P5. Task 7의 스크립트는 언제 만들어도 되지만 측정은 마지막에 한다.

**점검일 11/20:** 끝나지 않은 기능은 스펙 3.3 최소 버전으로 마무리하고 README "알려진 한계"에 적는다 — ⑬ 위치 호출 없이 Task 2의 도착 정보 규칙만(Task 5에서 위치를 빈 목록으로 두면 같은 동작), ⑪ Task 10·11 없이 시간표 알람, ⑭ Task 13·14 없이 계획한 열차와 직접 고르기.

**선행:**

| 선행 | 2026-11-05 상태 | 할 일 |
|---|---|---|
| (직접) 원본 파일 두 개, 5·6호선 위치 응답 샘플, 위치정보 키 | 없음 | Task 4 Step 1 |
| P2 Task 1·2·5~8·13·14 | `feat/server-infra`에만 있음 | 그 브랜치를 합칠지 사용자가 정한다 |
| P2 Task 3·4·9 (역 데이터, 역 인덱스, 실시간 도착) | 없음 | Task 1 전에 끝낸다 |
| P4 Task 1~9, 11, 12 (앱 통학 세션) | `feat/via-cloud`까지 있음 | — |

**브랜치:** 서버 `feat/position-fusion`(Task 1~7, P2 Task 9가 들어간 서버 브랜치에서) → 앱 `feat/train-tracking`(8~12) → `feat/boarding-detect`(13~14)

**경로 표기:** P3·P4와 같다(`<pkg>/`, `<test>/`). 서버 경로는 `server/`부터 쓴다.

## Global Constraints

- 대상 호선은 5호선·6호선뿐이다. 다른 호선은 1차 스펙 방식 그대로 계산하고, 서버는 노선 표에 호선만 더하면 넓어지게 만든다. (머리말, 3.2)
- 위치정보 요청 `{SEOUL_REALTIME_BASE}/{키}/json/realtimePosition/0/200/{호선명}`, 키 `SEOUL_POSITION_API_KEY`, 일일 한도 `SEOUL_POSITION_DAILY_LIMIT` 기본 1,000(한국 시간 00:00 초기화), 목록 키 `realtimePositionList`, "데이터 없음"은 빈 목록. (4.1, 12)
- 위치정보 캐시는 호선별 30초, 한도 80% 이상이면 ×3, 만료 항목 보관은 P2와 같다. `recptnDt`가 180초 넘게 지난 위치는 쓰지 않는다. (6.4)
- 도착 기록은 안내 문구로 추정한 값에서도 `now − recptnDt`를 빼고, 300초 넘게 지난 기록은 버리고, 미래 `recptnDt`는 지금으로 본다. "OO 출발" 제외 규칙은 그대로다. (6.2)
- 위치정보 호출이 실패하거나 한도에 닿아도 도착 응답은 오류 없이 도착 정보로만 계산한다(경고 로그와 지표만). (6.2)
- 위치 계산 상수(측정으로 고칠 값): 전역출발 + 이전 화살표 × 0.5, 진입 + 15초, 도착은 목표가 지금 역이면 0·아니면 + `dwellSec` × 0.5, 출발은 목표가 지금 역이면 `None`·아니면 − 10초. `dwellSec`은 노선 표의 값(기본 30). (6.2)
- 추적은 30초마다 조회한다. 지금 지점의 하차 시각이 20초 넘게 달라지면 그 지점과 이후 지점을 같은 만큼 옮기고 알림 시각은 max(지금, 하차 시각 − 알림 시점)이다. 3회 연속 실패면 "실시간 추적 끊김". (3.1, 7.2)
- 탑승 감지: 높은 정확도 10초 간격, 정확도가 1,000m보다 나쁜 위치는 버림, 탈 역 300m 안 → 탈 역 500m 밖이면서 다음 역이 더 가까움 → 두 위치 사이 시속 15km 이상. (7.1)
- 세션 서비스 유형은 `dataSync|location`, 권한 `FOREGROUND_SERVICE_LOCATION`. 위치 권한은 "앱 사용 중에만"이다. 서비스는 화면에서 시작한 세션과 앱 시작 복원에서만 켜고 재부팅 복원에서는 켜지 않는다. (7.3)
- 폰 위치는 서버로 보내지 않는다. (3.2)
- 새 칸은 모두 선택 칸이다. 앱 DTO와 저장된 세션 JSON은 새 칸이 없어도 기본값으로 읽혀야 한다. (6.3, 7.4)
- 서버 작업은 P2, 앱 작업은 P3·P4의 Global Constraints를 그대로 따른다. 작업마다 커밋에 학습 노트 초안 `docs/learning/NN-주제.md`(번호는 이어서)를 넣는다.

## Review Focus

1. 위치 기록의 지금 역이나 종착역이 노선 표에 없음(데이터 누락, 새 역, 늘 "강일 도착"인 기록) → 그 열차를 빼거나 오류를 내지 말고 도착 정보로 계산한다. (Task 5 `test_unknown_station_in_position_falls_back_to_arrival`)
2. 서울시 장애 때 위치 캐시가 오래된 목록을 `stale`로 돌려줌 → 캐시 시각이 아니라 기록의 `recptnDt`로 180초를 따져 도착 정보로 계산한다. (Task 5 `test_stale_cached_positions_not_used`)
3. 위치 목록에 같은 열차가 두 번(다른 상태·시각으로) 나옴 → 가장 늦은 `recptnDt` 기록을 쓴다. (Task 5 `test_duplicate_positions_use_latest`, Task 6도 같은 함수를 쓴다)
4. 위치 권한 없이 세션 서비스가 시작됨 → Android 14에서 `location` 유형으로 시작하면 SecurityException으로 앱이 죽으므로 `dataSync`만으로 시작하고 추적은 그대로 돈다. (Task 11 `serviceTypeWithoutLocationPermission`)
5. 업데이트 전에 저장된 탑승 세션(추적 칸 없음)을 새 앱이 복원 → 기본값으로 읽혀 알람이 그대로 이어진다. (Task 9 `oldSavedRidingStateStillLoads`)

---

### Task 1: 노선 표 읽기와 경로 계산

**Files:**
- Create: `server/app/services/topology.py`, `server/tests/fixtures/topology_sample.json`, `server/tests/unit/test_topology.py`
- Modify: `server/app/core/config.py`(`topology_path: str = "data/line_topology.json"`), `server/app/core/container.py`, `server/tests/conftest.py`

**Interfaces:**
- Consumes: P2 `Settings`, `AppServices`, `tests/helpers.FIXTURES_DIR`
- Produces (`topology.py`):
  - `@dataclass(frozen=True) class TopoStation: station_id: str; name: str`
  - `@dataclass(frozen=True) class PathStop: statn_id: str; sec: int` — `sec`는 출발 역에서 그 역 도착까지 누적 초(화살표 시간 합 + 사이 정차 역마다 `dwell_sec`, 첫 칸은 0)
  - `class LineTopology`: 속성 `name`, `dwell_sec`; `knows(statn_id) -> bool`, `station(statn_id) -> TopoStation | None`, `statn_id_of(station_id) -> str | None`, `path_to_terminal(from_statn: str, terminal_statn: str) -> list[PathStop] | None`, `incoming_sec(statn_id: str, exclude: str | None = None) -> int | None`(그 역으로 들어오는 화살표 중 `exclude`에서 오는 것을 뺀 최솟값)
  - `class Topology`: `load(cls, path: str | Path) -> Topology`(파일이 없으면 빈 노선 표와 경고 로그 — 위치 결합만 꺼진다), 속성 `lines: tuple[str, ...]`(이름순), `line(name) -> LineTopology | None`
- Produces: 스펙 5절 형식에 호선별 선택 키 `loopEnds: {순환 뒤 노드 id: 원래 실시간 역 ID}`를 더한 형식. 순환 뒤 노드는 `stations`에 없고 화살표에만 나온다.
- Produces: `AppServices.topology: Topology`; `tests/helpers.py`의 `load_sample_topology() -> Topology`(아래 합성 노선 표); conftest `test_settings`에 `topology_path=str(FIXTURES_DIR / "topology_sample.json")`, 픽스처 `topology`(`load_sample_topology()`), `topo5`(`topology.line("5호선")`), `topo6`

경로 규칙: 다익스트라(화살표 가중치 `sec + dwell_sec`, 같으면 노드 id 순). 목표 노드는 `terminal_statn`에 `loopEnds` 노드가 있으면 그 노드, 아니면 `terminal_statn`이다. 순환 뒤 노드는 경로에서 원래 역 ID로 보인다. 모르는 역이거나 갈 수 없으면 `None`.

`topology_sample.json`(합성 데이터, `version` 1, `dwellSec` 30, 역 ID는 "호선 번호 + 역 이름", `stationId`·`name`은 역 이름):
- 5호선, 양방향: 광나루–천호 120, 천호–강동 90, 강동–길동 100, 길동–하남검단산 150, 강동–둔촌동 110, 둔촌동–마천 200
- 6호선: 새절–응암 90(양방향), 순환은 한 방향 응암→역촌 80, 역촌→불광 90, 불광→연신내 100, 연신내→`6응암@loop` 150, `loopEnds: {"6응암@loop": "6응암"}`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
def ids(path):
    return [s.statn_id for s in path]

def test_branch_follows_terminal(topo5):
    assert ids(topo5.path_to_terminal("5천호", "5하남검단산")) == ["5천호", "5강동", "5길동", "5하남검단산"]
    assert ids(topo5.path_to_terminal("5천호", "5마천")) == ["5천호", "5강동", "5둔촌동", "5마천"]

def test_cumulative_sec_adds_dwell_between(topo5):
    assert [s.sec for s in topo5.path_to_terminal("5천호", "5하남검단산")] == [0, 90, 220, 400]

def test_loop_is_one_way_and_ends_after_loop(topo6):
    assert ids(topo6.path_to_terminal("6새절", "6응암")) == ["6새절", "6응암", "6역촌", "6불광", "6연신내", "6응암"]
    assert topo6.path_to_terminal("6역촌", "6새절") is None

def test_unknown_or_unreachable_is_none(topo5):
    assert topo5.path_to_terminal("5천호", "5없는역") is None
    assert topo5.path_to_terminal("5없는역", "5마천") is None

def test_station_id_conversion(topo5):
    assert topo5.statn_id_of("강동") == "5강동" and topo5.station("5강동") == TopoStation("강동", "강동")
    assert topo5.statn_id_of("홍대입구") is None and not topo5.knows("6응암")

def test_incoming_sec_excludes_next_station(topo5):
    assert topo5.incoming_sec("5강동", exclude="5길동") == 90
    assert topo5.incoming_sec("5광나루", exclude="5천호") is None

def test_missing_file_gives_empty_topology(tmp_path):
    empty = Topology.load(tmp_path / "없음.json")
    assert empty.lines == () and empty.line("5호선") is None

def test_sample_lines(topology):
    assert topology.lines == ("5호선", "6호선") and topology.line("6호선").dwell_sec == 30
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_topology.py -q` / Expected: FAIL (`ModuleNotFoundError`)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 노선 표 읽기와 종착역까지 경로 계산"`

---

### Task 2: 남은 시간 계산 규칙

**Files:**
- Create: `server/app/services/eta.py`, `server/tests/unit/test_eta.py`
- Modify: `server/app/services/remaining_time.py`(`_parse_message` → `parse_message_sec`, `_parse_recptn` → `parse_recptn`로 공개하고 `compute_remaining_sec`는 지운다), `server/app/services/arrival_service.py`(`compute_remaining_sec` 대신 `eta_from_arrival`), `server/tests/unit/test_remaining_time.py`(`test_message_patterns`는 `parse_message_sec`를 직접 확인하게 바꾸고 기대값은 그대로 둔다. `barvlDt` 테스트 셋은 함수 이름만 `eta_from_arrival`로 바꿔 `test_eta.py`로 옮긴다 — 값은 그대로 맞다. 샘플 해석 테스트는 `eta_from_arrival`로)

**Interfaces:**
- Consumes: Task 1 `LineTopology`, P2 Task 8의 안내 문구·생성 시각 해석
- Produces (`eta.py`): 상수 `POSITION_FRESH_SEC = 180`, `ARRIVAL_STALE_SEC = 300`, `ENTERING_SEC = 15`, `DEPARTED_SEC = 10`
  - `elapsed_sec(recptn: object, now: datetime) -> float` — `now − recptnDt`. 해석할 수 없거나 미래면 0
  - `is_fresh_position(position: dict, now) -> bool` — `recptnDt`가 있고 경과 ≤ 180초
  - `is_stale_arrival(item: dict, now) -> bool` — 경과 > 300초(해석할 수 없으면 False)
  - `eta_from_position(topology: LineTopology, position: dict, target_statn_id: str, now) -> int | None` — 스펙 6.2 위치 규칙. 경로는 `path_to_terminal(statnId, statnTid)`, 목표는 경로에서 처음 나오는 칸이다. 전역출발의 이전 화살표 시간은 `incoming_sec(statnId, exclude=경로의 다음 역)`(없으면 0).
  - `eta_from_arrival(item: dict, now) -> int | None` — `barvlDt > 0`이면 그 값, 아니면 `parse_message_sec(arvlMsg2)`. 거기서 `elapsed_sec`를 빼고 음수는 0. 둘 다 없으면 `None`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
NOW = datetime(2026, 11, 6, 8, 0, 0, tzinfo=KST)

def pos(statn, sttus, tid="5하남검단산", recptn="2026-11-06 08:00:00"):
    return {"trainNo": "5123", "statnId": statn, "statnTid": tid, "trainSttus": sttus, "recptnDt": recptn}

@pytest.mark.parametrize("sttus, target, expected", [
    ("3", "5강동", 90 + 60),     # 천호로 오는 중: 천호→강동 90 + 광나루→천호 120 × 0.5
    ("0", "5강동", 90 + 15),
    ("1", "5강동", 90 + 15),     # 천호에 서 있음: + 30 × 0.5
    ("2", "5강동", 90 - 10),
    ("2", "5길동", 90 + 30 + 100 - 10),
    ("1", "5천호", 0), ("0", "5천호", 15), ("3", "5천호", 60),
    ("2", "5천호", None),        # 막 떠났다
])
def test_state_adjustments(topo5, sttus, target, expected):
    assert eta_from_position(topo5, pos("5천호", sttus), target, NOW) == expected

def test_elapsed_since_recptn_subtracted_and_clamped(topo5):
    assert eta_from_position(topo5, pos("5천호", "2", recptn="2026-11-06 07:59:30"), "5강동", NOW) == 50
    assert eta_from_position(topo5, pos("5천호", "2", recptn="2026-11-06 07:58:00"), "5강동", NOW) == 0

def test_future_recptn_counts_as_now(topo5):
    assert eta_from_position(topo5, pos("5천호", "2", recptn="2026-11-06 08:02:40"), "5강동", NOW) == 80

def test_passed_target_and_other_branch_are_none(topo5):
    assert eta_from_position(topo5, pos("5강동", "1"), "5천호", NOW) is None
    assert eta_from_position(topo5, pos("5천호", "2", tid="5마천"), "5길동", NOW) is None

def test_loop_target_is_after_loop(topo6):
    assert eta_from_position(topo6, pos("6불광", "2", tid="6응암"), "6응암", NOW) == 100 + 30 + 150 - 10

def item(msg="", barvl="0", recptn="2026-11-06 07:59:30"):
    return {"arvlMsg2": msg, "barvlDt": barvl, "recptnDt": recptn}

@pytest.mark.parametrize("msg, expected", [("3분 20초 후 (신촌)", 170), ("전역 도착", 90), ("홍대입구 도착", 0), ("운행중", None)])
def test_arrival_message_minus_elapsed(msg, expected):
    assert eta_from_arrival(item(msg), NOW) == expected

def test_arrival_barvl_and_future_recptn():
    assert eta_from_arrival(item(barvl="180"), NOW) == 150
    assert eta_from_arrival(item("3분 후", recptn="2026-11-06 08:02:00"), NOW) == 180

def test_stale_arrival_and_fresh_position_limits():
    assert is_stale_arrival(item(recptn="2026-11-06 07:54:59"), NOW) and not is_stale_arrival(item(recptn="2026-11-06 07:55:00"), NOW)
    assert is_fresh_position(pos("5천호", "2", recptn="2026-11-06 07:57:00"), NOW)
    assert not is_fresh_position(pos("5천호", "2", recptn="2026-11-06 07:56:59"), NOW)
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_eta.py -q` / Expected: FAIL (`ModuleNotFoundError`)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과(P2 `test_arrivals.py` 포함)
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 열차 위치와 도착 기록의 남은 시간 계산 규칙"`

---

### Task 3: 위치정보 클라이언트와 위치 캐시

**Files:**
- Create: `server/app/clients/seoul_position.py`, `server/app/services/train_service.py`, `server/tests/integration/test_positions.py`
- Modify: `server/app/clients/seoul_subway.py`, `server/app/core/config.py`, `server/.env.example`, `server/app/core/container.py`, `server/tests/conftest.py`, `server/tests/helpers.py`

**Interfaces:**
- Consumes: P2 Task 5~7·9(`TtlCache`, `QuotaGuard`, `call_upstream`, `mask_url`, `SeoulSubwayClient`의 응답 코드 처리), Task 1 `Topology`
- Produces (`seoul_subway.py`): `read_seoul_list(body: dict, list_key: str, guard: QuotaGuard) -> list[dict]` — P2 Task 9 `fetch_arrivals`의 응답 코드 처리를 꺼낸 함수. 두 클라이언트가 함께 쓴다. 정상 코드인데 `list_key`가 없으면 `UpstreamError(reason="NO_LIST")`(목록 키가 스펙 12절 기본값과 다를 때 조용히 빈 목록이 되지 않게)
- Produces: `Settings.seoul_position_api_key: str = ""`, `Settings.seoul_position_daily_limit: int = 1000`, `.env.example`의 `SEOUL_POSITION_API_KEY=`·`SEOUL_POSITION_DAILY_LIMIT=1000`, `build_quotas`의 마지막 키 `seoulPosition`(`/v1/metrics`의 `upstream`에 저절로 나온다)
- Produces (`seoul_position.py`): `class SeoulPositionClient`: `__init__(self, http, guard, settings)`, `async def fetch_positions(self, line_name: str) -> list[dict]` — URL `f"{SEOUL_REALTIME_BASE}/{키}/json/realtimePosition/0/200/{quote(line_name)}"`, `call_upstream` 경유
- Produces (`train_service.py`): `class TrainService`: `__init__(self, client: SeoulPositionClient, cache: TtlCache[list[dict]], topology: Topology, index: StationIndex, clock)`, 속성 `client`, `async def positions(self, line: str) -> CacheResult[list[dict]]`(키 = 호선 이름, 30초 × `seoulPosition` 배율, `UpstreamFailure`는 그대로 던진다), `async def positions_or_empty(self, line: str) -> list[dict]`(노선 표에 없는 호선은 호출 없이 `[]`, `UpstreamFailure`면 `logger.warning` 후 `[]`)
- Produces: `AppServices.trains: TrainService`; conftest `test_settings`에 `seoul_position_api_key="TESTPOSITIONKEY"`; `tests/helpers.py`의 `ARRIVAL_URL = re.compile(r".*/realtimeStationArrival/.*")`, `POSITION_URL = re.compile(r".*/realtimePosition/.*")`, `positions_response(*records) -> httpx.Response`(홍대입구 샘플 본문에서 `realtimeArrivalList`를 빼고 `realtimePositionList`를 넣은 응답), `position(no, statn, sttus, tid="5하남검단산", recptn="2026-11-06 08:00:00") -> dict`(Task 2 `pos`와 같은 칸)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
@pytest.fixture(autouse=True)
def at_eight(clock):
    clock.set(datetime(2026, 11, 6, 8, 0, 0, tzinfo=KST).timestamp())

async def test_fetch_reads_position_list(services, respx_mock):
    route = respx_mock.get(url__regex=POSITION_URL).mock(return_value=positions_response(position("5123", "5천호", "2")))
    assert [p["trainNo"] for p in await services.trains.client.fetch_positions("5호선")] == ["5123"]
    assert "/TESTPOSITIONKEY/json/realtimePosition/0/200/" in str(route.calls.last.request.url)

async def test_nodata_is_empty_list(services, respx_mock):
    respx_mock.get(url__regex=POSITION_URL).mock(return_value=fixture_response("seoul_realtime_nodata.json"))
    assert (await services.trains.positions("5호선")).value == []

async def test_missing_list_key_is_an_error(services, respx_mock):
    respx_mock.get(url__regex=POSITION_URL).mock(return_value=fixture_response("seoul_realtime_hongdae.json"))
    with pytest.raises(UpstreamError):
        await services.trains.positions("5호선")

async def test_quota_error_marks_only_position_quota(services, respx_mock):
    respx_mock.get(url__regex=POSITION_URL).mock(return_value=quota_exceeded_response())
    with pytest.raises(QuotaExceeded):
        await services.trains.positions("5호선")
    assert not services.quotas["seoulPosition"].can_call() and services.quotas["seoulRealtime"].can_call()

async def test_positions_cached_30_seconds_per_line(services, respx_mock, clock):
    route = respx_mock.get(url__regex=POSITION_URL).mock(return_value=positions_response())
    await services.trains.positions("5호선"); clock.advance(29)
    await services.trains.positions("5호선"); await services.trains.positions("6호선")
    assert route.call_count == 2
    clock.advance(1); await services.trains.positions("5호선")
    assert route.call_count == 3

async def test_failure_or_unsupported_line_gives_empty(services, respx_mock, caplog):
    route = respx_mock.get(url__regex=POSITION_URL).mock(side_effect=[httpx.Response(500), httpx.Response(500)])
    assert await services.trains.positions_or_empty("2호선") == [] and not route.called
    assert await services.trains.positions_or_empty("5호선") == []
    assert any(r.levelname == "WARNING" for r in caplog.records)

async def test_metrics_list_position_quota(client):
    upstream = (await client.get("/v1/metrics")).json()["upstream"]
    assert upstream["seoulPosition"] == {"callsToday": 0, "dailyLimit": 1000, "errorsToday": 0}

def test_position_key_masked():
    assert "/api/subway/***/" in mask_url(f"{SEOUL_REALTIME_BASE}/SECRET/json/realtimePosition/0/200/5호선")
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_positions.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 실시간 열차 위치정보 클라이언트와 호선별 위치 캐시"`

---

### Task 4: 노선 표 만들기 스크립트

**Files:**
- Create: `server/scripts/build_topology.py`, `server/scripts/collect_run_times.py`, `server/tests/unit/test_build_topology.py`, `server/data/line_topology.json`(생성물, 커밋)
- Modify: `server/pyproject.toml`(dev에 `openpyxl`), `docs/analysis/api-verification.md`(위치정보 절)

**Interfaces:**
- Consumes: Task 1 `Topology`(결과 확인), Task 3 `SeoulPositionClient`, P2 Task 3 `API_NAME_EXCEPTIONS`, P2 Task 4 `StationIndex.resolve`
- Produces (`build_topology.py`):
  - `@dataclass(frozen=True)`: `RealtimeStation(statn_id: str, name: str, line: str)`, `TimeRow(line: str, name: str, sec_from_prev: int | None)`, `Run(line: str, from_id: str, to_id: str, sec: int)`; `class BuildError(Exception)`
  - `REALTIME_COLUMNS`, `TIMES_COLUMNS` — 원본 파일의 열 이름. 파일을 받으면 맞춘다(예상: `{"statn_id": "STATN_ID", "name": "STATN_NM", "line": "호선이름"}`, `{"line": "호선", "name": "역명", "sec": "소요시간"}`)
  - `PREDECESSOR_OVERRIDES: dict[tuple[str, str], str]` — 원본에서 갈래가 시작되는 행의 앞 역(예상 `{("5호선", "둔촌동"): "강동"}`), `LOOPS = {"6호선": ["응암", "역촌", "불광", "독바위", "연신내", "구산", "응암"]}`(우리 역 id)
  - `read_realtime_stations(path) -> list[RealtimeStation]`(xlsx), `read_station_times(path) -> list[TimeRow]`(csv는 `utf-8-sig` 다음 `cp949`, 또는 xlsx. 시간은 `"M:SS"` 또는 초)
  - `runs_from_times(rows, stations, resolve, predecessors=PREDECESSOR_OVERRIDES) -> list[Run]` — 같은 호선의 연속 행(갈래 시작 행은 `predecessors`의 앞 역)을 양방향 `Run`으로. 두 파일의 이름은 `resolve`로 우리 역 id에 맞춰 잇는다. 못 찾은 이름은 모아서 `BuildError`
  - `build_topology(stations, runs, *, resolve: Callable[[str, str], tuple[str, str] | None], loops=LOOPS, lines=("5호선", "6호선"), dwell_sec=30) -> dict` — `resolve(역 이름, 호선)` → (우리 역 id, 표시 이름). `stations`에는 `lines` 호선의 실시간 역을 모두 넣는다. 순환 구간은 순환 방향 화살표만 두고, 마지막 화살표는 `f"{역 ID}@loop"` 노드로 보내 `loopEnds`에 적는다. 같은 화살표가 두 번이면 `BuildError`. `stations`·`edges`를 정렬해 같은 입력이면 같은 결과다.
  - `write_topology(topology: dict, out: Path) -> None`(`sort_keys=True, ensure_ascii=False, indent=1`)
  - CLI: `python -m scripts.build_topology --stations data/raw/realtime_station_info.xlsx (--times data/raw/metro_station_times.csv | --runs data/raw/observed_runs.json) --out data/line_topology.json` — `resolve`는 `API_NAME_EXCEPTIONS`를 거꾸로 본 뒤 `StationIndex.resolve`
- Produces (`collect_run_times.py`, 4.3의 대안):
  - `extract_runs(records: list[dict], line: str) -> list[Run]` — 열차별로 `recptnDt` 순서로 보아, A역 "출발"(2)이 처음 보인 시각부터 그다음 보인 역 B의 "도착"(1)이 처음 보인 시각까지. B 도착을 못 보고 B 출발이 먼저 보이면 버린다.
  - `median_runs(runs: list[Run], min_count: int = 3) -> list[tuple[Run, int]]` — (호선, A, B)별 정수 중앙값과 관찰 수. `min_count` 미만은 뺀다.
  - CLI: `python -m scripts.collect_run_times --lines 5호선 6호선 --minutes 90 --interval 30 --out data/raw/observed_runs.json` → `{"runs": [{"line", "from", "to", "sec", "count"}]}`

- [ ] **Step 1: (직접) 준비물** — `server/data/raw/`에 `realtime_station_info.xlsx`와 `metro_station_times.(csv|xlsx)`(스펙 4.3), `server/.env`에 `SEOUL_POSITION_API_KEY`. 5·6호선 위치 응답을 받아 `tests/fixtures/seoul_position_5.json`·`seoul_position_6.json`(`{"status": 200, "body": 응답}`)으로 둔다. 스펙 12절 다섯 항목을 샘플로 확인하고, 다르면 스펙을 먼저 고친다.
- [ ] **Step 2: 실패하는 테스트 작성**

```python
STATIONS = ([RealtimeStation(f"5{n}", n, "5호선") for n in ["천호", "강동", "길동", "둔촌동"]]
            + [RealtimeStation("6응암", "응암순환(상선)", "6호선")]
            + [RealtimeStation(f"6{n}", n, "6호선") for n in ["새절", "역촌", "불광", "연신내"]])

def resolve(name, line):
    real = {"응암순환(상선)": "응암"}.get(name, name)
    return None if real == "없는역" else (real, real)

def rows(line, *pairs):
    return [TimeRow(line, name, sec) for name, sec in pairs]

def test_times_make_both_directions_and_branch_uses_override():
    runs = runs_from_times(rows("5호선", ("천호", None), ("강동", 90), ("길동", 100), ("둔촌동", 110)),
                           STATIONS, resolve, predecessors={("5호선", "둔촌동"): "강동"})
    assert {Run("5호선", "5강동", "5둔촌동", 110), Run("5호선", "5둔촌동", "5강동", 110)} <= set(runs)
    assert not any({r.from_id, r.to_id} == {"5길동", "5둔촌동"} for r in runs)

def test_loop_is_one_way_with_loop_end_node():
    runs = runs_from_times(rows("6호선", ("새절", None), ("응암", 90), ("역촌", 80), ("불광", 90), ("연신내", 100), ("응암", 150)),
                           STATIONS, resolve, predecessors={})
    line = build_topology(STATIONS, runs, resolve=resolve, loops={"6호선": ["응암", "역촌", "불광", "연신내", "응암"]})["lines"]["6호선"]
    edges = {(e["from"], e["to"]) for e in line["edges"]}
    assert {("6연신내", "6응암@loop"), ("6응암", "6새절"), ("6새절", "6응암")} <= edges and ("6역촌", "6응암") not in edges
    assert line["loopEnds"] == {"6응암@loop": "6응암"} and line["stations"]["6응암"] == {"stationId": "응암", "name": "응암"}

def test_unknown_name_and_duplicate_edge_fail():
    with pytest.raises(BuildError, match="없는역"):
        runs_from_times(rows("5호선", ("천호", None), ("없는역", 90)), STATIONS, resolve, predecessors={})
    with pytest.raises(BuildError):
        build_topology(STATIONS, [Run("5호선", "5천호", "5강동", 90)] * 2, resolve=resolve, loops={})

def test_output_is_deterministic_and_loadable(tmp_path):
    runs = runs_from_times(rows("5호선", ("천호", None), ("강동", 90), ("길동", 100)), STATIONS, resolve, predecessors={})
    a, b = tmp_path / "a.json", tmp_path / "b.json"
    write_topology(build_topology(STATIONS, runs, resolve=resolve, loops={}), a)
    write_topology(build_topology(STATIONS, runs[::-1], resolve=resolve, loops={}), b)
    assert a.read_bytes() == b.read_bytes()
    assert [s.statn_id for s in Topology.load(a).line("5호선").path_to_terminal("5천호", "5길동")] == ["5천호", "5강동", "5길동"]

def rec(statn, sttus, t):
    return {"trainNo": "5001", "statnId": statn, "trainSttus": sttus, "recptnDt": f"2026-11-06 08:{t}"}

def test_extract_run_from_departure_to_next_arrival():
    recs = [rec("5천호", "2", "00:10"), rec("5강동", "3", "00:30"), rec("5강동", "1", "01:40"), rec("5강동", "1", "01:40")]
    assert extract_runs(recs, "5호선") == [Run("5호선", "5천호", "5강동", 90)]
    assert extract_runs([rec("5천호", "2", "00:10"), rec("5강동", "2", "02:10")], "5호선") == []

def test_median_needs_three_observations():
    runs = [Run("5호선", "5천호", "5강동", s) for s in (80, 90, 120)] + [Run("5호선", "5강동", "5길동", 100)] * 2
    assert median_runs(runs) == [(Run("5호선", "5천호", "5강동", 90), 3)]
```

- [ ] **Step 3: 실패 확인** — Run: `pytest tests/unit/test_build_topology.py -q` / Expected: FAIL
- [ ] **Step 4: 구현**
- [ ] **Step 5: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 6: (직접) 실제 샘플 테스트 작성과 실패 확인** — `tests/unit/test_real_topology.py`를 쓰고 Run: `pytest tests/unit/test_real_topology.py -q` / Expected: FAIL(노선 표 파일 없음)

```python
@pytest.mark.parametrize("line, sample", [("5호선", "seoul_position_5.json"), ("6호선", "seoul_position_6.json")])
def test_real_samples_fit_built_topology(line, sample):
    topo = Topology.load(Path(__file__).parents[2] / "data" / "line_topology.json").line(line)
    records = load_fixture(sample)[1]["realtimePositionList"]
    assert records and {r["trainSttus"] for r in records} <= {"0", "1", "2", "3"}
    assert [r["statnId"] for r in records if not topo.knows(r["statnId"])] == []
```

- [ ] **Step 7: (직접) 실제 노선 표 만들기** — Run: `python -m scripts.build_topology --stations data/raw/realtime_station_info.xlsx --times data/raw/metro_station_times.csv --out data/line_topology.json`. 소요시간 파일에 5·6호선이 없거나 빠진 구간이 있으면 `python -m scripts.collect_run_times …`(90분)로 받은 `--runs`를 쓴다. `BuildError`의 이름은 `PREDECESSOR_OVERRIDES`·열 이름·`API_NAME_EXCEPTIONS`를 고쳐 해결한다. 확인: Step 6 테스트 통과, 천호에서 종착 하남검단산·마천 경로가 각각 길동·둔촌동을 지나고, 6호선 새절에서 종착 응암 경로가 순환을 돈다. `api-verification.md`에 열 이름, 갈래 표, 소요시간이 정차 시간을 포함하는지(포함하면 `dwellSec` 0)와 그 근거를 적는다.
- [ ] **Step 8: Commit** — `git add server docs && git commit -m "feat(server): 노선 표 만들기 스크립트와 line_topology.json"`

---

### Task 5: 도착 정보와 위치 결합

**Files:**
- Create: `server/app/services/arrival_fusion.py`, `server/tests/unit/test_arrival_fusion.py`, `server/tests/integration/test_arrivals_fusion.py`
- Modify: `server/app/services/arrival_service.py`, `server/app/models/schemas.py`, `server/app/core/metrics.py`, `server/app/api/metrics.py`, `server/app/core/container.py`, `server/tests/helpers.py`

**Interfaces:**
- Consumes: Task 1 `Topology`, Task 2 함수, Task 3 `TrainService.positions_or_empty`, P2 `SUBWAY_ID_TO_LINE`, `ArrivalService`
- Produces (`arrival_fusion.py`):
  - `@dataclass(frozen=True) class FusedTrain: item: dict; remaining_sec: int | None; basis: Literal["position", "arrival"]; train_no: str | None`
  - `latest_positions(positions: list[dict]) -> dict[str, dict]` — 열차 번호(앞뒤 공백 제거)별로 `recptnDt`가 가장 늦은 기록
  - `fuse_trains(items: list[dict], positions: dict[str, list[dict]], topology: Topology, now: datetime) -> list[FusedTrain]` — 입력 순서 유지. 호선은 `SUBWAY_ID_TO_LINE.get(subwayId)`(없으면 노선 표 없음과 같다), 목표 역은 도착 기록의 `statnId`다. 그 호선의 노선 표가 있고, 같은 열차 번호의 위치가 `is_fresh_position`이고, 노선 표가 위치의 `statnId`·`statnTid`와 도착 기록의 `statnId`를 모두 알면 `eta_from_position`(결과가 `None`이면 뺀다). 그 밖은 `is_stale_arrival`이면 빼고, 아니면 `eta_from_arrival`.
- Produces: `Train`에 `train_no: str | None = None`, `basis: Literal["position", "arrival"] = "arrival"`(JSON `trainNo`, `basis`)
- Produces: `ArrivalService.__init__(self, client, index, cache, clock, *, topology: Topology, positions: Callable[[str], Awaitable[list[dict]]], metrics: Metrics)` — "출발" 열차를 뺀 뒤 노선 표가 있는 호선의 위치를 함께 받고(`asyncio.gather`) `fuse_trains` → P2의 묶기·정렬·2대. 응답에 실린 열차 수를 방식별로 센다.
- Produces: `Metrics.fusion_position: int = 0`, `Metrics.fusion_arrival: int = 0`, `MetricsResponse.fusion: FusionStats(position: int, arrival: int)`
- Produces (`tests/helpers.py`): `arrival_item(no, statn="5강동", msg="전역 도착", recptn="2026-11-06 08:00:00", subway="1005") -> dict`(`updnLine` "하행", `trainLineNm` "하남검단산행 - 길동방면", `barvlDt` "0"을 함께 넣음), `arrivals_response(*items) -> httpx.Response`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/unit/test_arrival_fusion.py
NOW = datetime(2026, 11, 6, 8, 0, 0, tzinfo=KST)

def fuse(items, positions):
    return fuse_trains(items, {"5호선": positions}, load_sample_topology(), NOW)

def test_matched_fresh_position_uses_position():
    [f] = fuse([arrival_item("5123")], [position("5123", "5천호", "2")])
    assert (f.basis, f.remaining_sec, f.train_no) == ("position", 80, "5123")

def test_position_older_than_180s_uses_arrival():
    [f] = fuse([arrival_item("5123")], [position("5123", "5천호", "2", recptn="2026-11-06 07:56:59")])
    assert (f.basis, f.remaining_sec) == ("arrival", 120)

def test_passed_train_dropped():
    assert fuse([arrival_item("5123", statn="5천호")], [position("5123", "5강동", "1")]) == []

def test_stale_arrival_dropped_without_position():
    assert fuse([arrival_item("5123", recptn="2026-11-06 07:54:59")], []) == []

def test_line_without_topology_uses_arrival():
    [f] = fuse([arrival_item("2001", statn="1002000239", subway="1002")], [])
    assert (f.basis, f.train_no) == ("arrival", "2001")

def test_unknown_station_in_position_falls_back_to_arrival():          # Review Focus 1
    [f] = fuse([arrival_item("5123")], [position("5123", "5없는역", "2")])
    assert f.basis == "arrival"

def test_duplicate_positions_use_latest():                              # Review Focus 3
    old = position("5123", "5광나루", "2", recptn="2026-11-06 07:59:00")
    [f] = fuse([arrival_item("5123")], [position("5123", "5천호", "2"), old])
    assert f.remaining_sec == 80

def test_real_hongdae_sample_keeps_train_numbers():                     # 스펙 9절: 실제 도착 샘플
    items = load_fixture("seoul_realtime_hongdae.json")[1]["realtimeArrivalList"]
    now = max(parse_recptn(i["recptnDt"]) for i in items)
    fused = fuse_trains(items, {}, load_sample_topology(), now)
    assert fused and all(f.basis == "arrival" and f.train_no for f in fused)

# tests/integration/test_arrivals_fusion.py
@pytest.fixture(autouse=True)
def at_eight(clock):
    clock.set(datetime(2026, 11, 6, 8, 0, 0, tzinfo=KST).timestamp())

def mock(respx_mock, positions):
    respx_mock.get(url__regex=ARRIVAL_URL).mock(return_value=arrivals_response(
        arrival_item("5123"), arrival_item("5131", msg="[3]번째 전역 (천호)")))
    return respx_mock.get(url__regex=POSITION_URL).mock(side_effect=positions)

async def test_arrivals_carry_train_no_and_basis(client, respx_mock, metrics):
    mock(respx_mock, [positions_response(position("5123", "5천호", "2"))])
    trains = (await client.get("/v1/stations/강동/arrivals")).json()["groups"][0]["trains"]
    assert [(t["trainNo"], t["basis"], t["remainingSec"]) for t in trains] == [("5123", "position", 80), ("5131", "arrival", 360)]
    assert (await client.get("/v1/metrics")).json()["fusion"] == {"position": 1, "arrival": 1}

async def test_position_failure_still_returns_arrivals(client, respx_mock):
    mock(respx_mock, [httpx.Response(500), httpx.Response(500)])
    r = await client.get("/v1/stations/강동/arrivals")
    assert r.status_code == 200 and {t["basis"] for t in r.json()["groups"][0]["trains"]} == {"arrival"}

async def test_stale_cached_positions_not_used(client, respx_mock, clock):  # Review Focus 2
    mock(respx_mock, [positions_response(position("5123", "5천호", "2")), httpx.Response(500), httpx.Response(500)])
    await client.get("/v1/stations/강동/arrivals"); clock.advance(200)
    trains = (await client.get("/v1/stations/강동/arrivals")).json()["groups"][0]["trains"]
    assert trains[0]["trainNo"] == "5123" and trains[0]["basis"] == "arrival"
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_arrival_fusion.py tests/integration/test_arrivals_fusion.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과(P2 `test_arrivals.py` 포함)
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 같은 열차의 위치로 도착 시간 다시 계산(위치 결합)"`

---

### Task 6: 근처 열차와 탄 열차 API

**Files:**
- Create: `server/app/api/trains.py`, `server/tests/integration/test_trains.py`
- Modify: `server/app/services/train_service.py`, `server/app/models/schemas.py`, `server/app/core/errors.py`, `server/app/main.py`

**Interfaces:**
- Consumes: Task 1~3, Task 5 `latest_positions`, P2 `StationIndex.resolve`, `normalize_line`, `kst_from_epoch`
- Produces (`schemas.py`): `TrainNear(train_no: str, station_id: str, station_name: str, state: Literal["APPROACHING", "ARRIVED", "DEPARTED", "LEFT_PREVIOUS"], direction: str | None, destination: str | None, is_express: bool, is_last: bool, eta_sec: int | None, stops_from_station: int)`, `LineTrainsResponse(line: str, updated_at: datetime, stale: bool, trains: list[TrainNear])`, `TrainEtaResponse(train_no: str, line: str, found: bool, station_id: str | None = None, state: str | None = None, eta_sec: int | None = None, updated_at: datetime, stale: bool)`
- Produces (`errors.py`): `("NOT_FOUND", "LINE_NOT_SUPPORTED"): "이 호선은 열차 위치 정보를 지원하지 않아요."`
- Produces (`TrainService`): `async def near(self, line: str, station: str, toward: str | None) -> LineTrainsResponse`, `async def eta(self, train_no: str, line: str, to: str) -> TrainEtaResponse`
  - 공통: 호선과 역을 먼저 확인하고 그 뒤에 위치를 받는다. `normalize_line(line)`의 노선 표가 없으면 `NotFound(reason="LINE_NOT_SUPPORTED")`. 역(`station`, `toward`, `to`)은 `index.resolve(값, 호선)` → `statn_id_of`(앱은 `toward`에 다음 정거장 이름을 보낸다), 못 찾으면 `NotFound()`. 위치는 `latest_positions` 중 `is_fresh_position`이고 노선 표가 `statnId`·`statnTid`를 아는 기록만 쓴다. `updated_at`·`stale`은 위치 캐시 항목의 것이다.
  - `stops_from_station`(이 계획이 더한 칸, 스펙 6.3에 반영): 열차의 `path_to_terminal(statnId, statnTid)`에서 역이 k번째(0부터)면 −k. 아니면 `path_to_terminal(역, statnTid)`에서 열차의 지금 역이 k번째(1 이상)면 +k. −3~+3 밖이거나 둘 다 아니면 뺀다. `toward`가 있으면 `path_to_terminal(역, statnTid)`의 두 번째 역이 `toward`인 열차만 남긴다.
  - `eta_sec`: `stops_from_station ≤ 0`이면 `eta_from_position(…, 역)`, 아니면 `None`. 정렬 `(stops_from_station, 상태 순서)` — 한 역 안의 진행 순서 LEFT_PREVIOUS, APPROACHING, ARRIVED, DEPARTED. `state`는 `trainSttus` "0"→APPROACHING, "1"→ARRIVED, "2"→DEPARTED, "3"→LEFT_PREVIOUS. `direction`은 `updnLine` "0"→"상행", "1"→"하행". `destination` = `statnTnm`, `is_express` = `directAt`이 "1"·"7", `is_last` = `lstcarAt == "1"`.
  - `eta`: 같은 열차 번호의 기록이 없거나 위 조건에 맞지 않으면 200과 `found=False`. 있으면 `station_id`(노선 표의 우리 역 id), `state`, `eta_sec = eta_from_position(…, to)`.
- Produces: `GET /v1/lines/{line}/trains?station=&toward=`(`station` 1~50자 필수), `GET /v1/trains/{train_no}?line=&to=`(둘 다 필수), `request.state.cache_status` 설정

- [ ] **Step 1: 실패하는 테스트 작성**

```python
LINE5 = "/v1/lines/5호선/trains"

@pytest.fixture(autouse=True)
def at_eight(clock):
    clock.set(datetime(2026, 11, 6, 8, 0, 0, tzinfo=KST).timestamp())

def positions(respx_mock, *records):
    return respx_mock.get(url__regex=POSITION_URL).mock(return_value=positions_response(*records))

async def test_near_trains_within_three_stops_in_order(client, respx_mock):
    positions(respx_mock, position("5001", "5광나루", "2"), position("5002", "5길동", "3"),
              position("5003", "5강동", "2"), position("5004", "5하남검단산", "1"))
    body = (await client.get(LINE5, params={"station": "강동"})).json()
    assert [(t["trainNo"], t["stopsFromStation"], t["state"], t["etaSec"]) for t in body["trains"]] == [
        ("5001", -2, "DEPARTED", 230), ("5003", 0, "DEPARTED", None),
        ("5002", 1, "LEFT_PREVIOUS", None), ("5004", 2, "ARRIVED", None)]
    assert body["stale"] is False and body["updatedAt"].endswith("+09:00")

async def test_toward_keeps_one_direction(client, respx_mock):
    positions(respx_mock, position("5003", "5강동", "2"), position("5005", "5둔촌동", "3", tid="5마천"),
              position("5006", "5천호", "3", tid="5광나루"))
    for toward, expected in [("길동", ["5003"]), ("둔촌동", ["5005"])]:
        r = await client.get(LINE5, params={"station": "강동", "toward": toward})
        assert [t["trainNo"] for t in r.json()["trains"]] == expected

async def test_far_and_stale_trains_excluded(client, respx_mock):
    positions(respx_mock, position("5007", "5광나루", "2"), position("5008", "5길동", "2", recptn="2026-11-06 07:56:59"))
    assert (await client.get(LINE5, params={"station": "하남검단산"})).json()["trains"] == []

async def test_unsupported_line_and_unknown_station(client, respx_mock):
    r = await client.get("/v1/lines/2호선/trains", params={"station": "홍대입구"})
    assert r.status_code == 404 and r.json()["error"]["reason"] == "LINE_NOT_SUPPORTED" and not respx_mock.calls
    r = await client.get(LINE5, params={"station": "없는역"})
    assert r.status_code == 404 and r.json()["error"]["code"] == "NOT_FOUND" and "reason" not in r.json()["error"]

async def test_train_eta_found_and_passed(client, respx_mock):
    positions(respx_mock, position("5123", "5천호", "2"))
    body = (await client.get("/v1/trains/5123", params={"line": "5호선", "to": "길동"})).json()
    assert (body["found"], body["stationId"], body["state"], body["etaSec"]) == (True, "천호", "DEPARTED", 210)
    assert (await client.get("/v1/trains/5123", params={"line": "5호선", "to": "광나루"})).json()["etaSec"] is None

async def test_unknown_train_is_200_not_found(client, respx_mock):
    positions(respx_mock, position("5123", "5천호", "2"))
    r = await client.get("/v1/trains/9999", params={"line": "5호선", "to": "길동"})
    assert r.status_code == 200 and (r.json()["found"], r.json()["etaSec"]) == (False, None)
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/integration/test_trains.py -q` / Expected: FAIL (404 — 경로 없음)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 근처 열차 목록과 탄 열차 남은 시간 API"`

---

### Task 7: 정확도 측정 스크립트 (S6)

**Files:**
- Create: `server/scripts/measure_accuracy.py`, `server/tests/unit/test_measure_accuracy.py`

**Interfaces:**
- Consumes: Task 2 `eta_from_arrival`·`is_stale_arrival`, Task 5 `fuse_trains`, Task 3 `SeoulPositionClient`, P2 `SeoulSubwayClient`·`StationIndex`
- Produces:
  - `@dataclass(frozen=True) class Prediction: statn_id: str; train_no: str; method: Literal["arrival", "fused"]; basis: str; made_at: datetime; predicted_at: datetime`
  - `predictions_for_round(items: list[dict], positions: dict[str, list[dict]], topology: Topology, now: datetime) -> list[Prediction]` — 열차 번호가 있는 도착 기록마다 `arrival`(`eta_from_arrival`, 오래된 기록 제외)과 `fused`(`fuse_trains`의 결과와 `basis`)를 하나씩. 남은 시간이 없으면 만들지 않는다.
  - `actual_arrivals(records: list[dict]) -> dict[tuple[str, str], datetime]` — (역 ID, 열차 번호)별로 `trainSttus == "1"`인 기록의 가장 이른 `recptnDt`
  - `@dataclass(frozen=True) class SummaryRow: method: str; bucket: str; count: int; median_abs_sec: int; p90_abs_sec: int`, `summarize(predictions, actuals) -> list[SummaryRow]` — 실제 도착이 있는 예측만. 앞둔 시간(실제 − `made_at`) 구간 "0~2분" [0, 120), "2~5분" [120, 300), "5~10분" [300, 600), 오차는 `|predicted_at − 실제|`, 상위 10%는 nearest-rank, 방식·구간 순
  - `render_report(rows: list[SummaryRow], meta: dict[str, str]) -> str` — 측정 조건(meta: 역, 시간, 간격, fused 예측 중 position·arrival 건수) 목록과 표 `| 방식 | 구간 | 건수 | 중앙값(초) | 상위 10%(초) |`
  - CLI: `python -m scripts.measure_accuracy --stations 공덕 여의도 --minutes 60 --interval 30 [--out ../docs/measurements/accuracy-YYYYMMDD.md]` — 회차마다 역별 도착정보와 노선 표 호선별 위치정보를 받아 예측과 위치 기록을 모으고, 끝나면 보고서를 쓴다. 실제 키로 데스크톱에서만 실행한다(배포 서버를 거치지 않는다).

- [ ] **Step 1: 실패하는 테스트 작성**

```python
T0 = datetime(2026, 11, 6, 8, 0, 0, tzinfo=KST)

def at(sec):
    return T0 + timedelta(seconds=sec)

def pred(method, made, predicted, no="5123"):
    return Prediction("5강동", no, method, "position" if method == "fused" else "arrival", at(made), at(predicted))

def test_round_predictions_use_both_methods(topology):
    preds = predictions_for_round([arrival_item("5123")], {"5호선": [position("5123", "5천호", "2")]}, topology, T0)
    assert {(p.method, p.basis, p.predicted_at) for p in preds} == {("arrival", "arrival", at(120)), ("fused", "position", at(80))}

def test_actual_arrival_is_first_arrived_record():
    recs = [position("5123", "5강동", "0", recptn="2026-11-06 08:01:00"), position("5123", "5강동", "1", recptn="2026-11-06 08:01:30"),
            position("5123", "5강동", "1", recptn="2026-11-06 08:02:00")]
    assert actual_arrivals(recs) == {("5강동", "5123"): at(90)}

def test_summary_by_method_and_lead_bucket():
    preds = [pred("arrival", 0, 100), pred("fused", 0, 85), pred("arrival", -200, 30), pred("fused", -200, 95),
             pred("arrival", 0, 100, no="9999")]
    rows = summarize(preds, {("5강동", "5123"): at(90)})
    assert [(r.method, r.bucket, r.count, r.median_abs_sec, r.p90_abs_sec) for r in rows] == [
        ("arrival", "0~2분", 1, 10, 10), ("arrival", "2~5분", 1, 60, 60), ("fused", "0~2분", 1, 5, 5), ("fused", "2~5분", 1, 5, 5)]

def test_report_rows():
    text = render_report([SummaryRow("fused", "0~2분", 3, 12, 30)], {"역": "공덕"})
    assert "| fused | 0~2분 | 3 | 12 | 30 |" in text and "공덕" in text
```

- [ ] **Step 2: 실패 확인** — Run: `pytest tests/unit/test_measure_accuracy.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: (직접) 측정 (S6)** — 통학하지 않는 시간(저녁·주말)에 5·6호선 역 4곳으로 60분 측정한다(도착정보 480회, 위치정보 240회). 보고서를 `docs/measurements/accuracy-YYYYMMDD.md`로 커밋한다. 기준: fused 오차 중앙값 < arrival 오차 중앙값, "0~2분" 구간도 함께 본다. 못 미치면 Task 2의 상수를 고치고 다시 잰다.
- [ ] **Step 6: Commit** — `git add server docs && git commit -m "feat(server): 도착 시간 정확도 측정 스크립트"`

---

### Task 8: 열차 API 데이터와 저장소 (앱)

**Files:**
- Create: `<pkg>/data/repository/TrainRepository.kt`, `<test>/data/remote/TrainDtoDecodingTest.kt`
- Modify: `<pkg>/data/remote/Dtos.kt`, `<pkg>/data/remote/ApiService.kt`, `<pkg>/di/AppContainer.kt`, `<pkg>/session/SessionModel.kt`, `<pkg>/session/SessionMath.kt`, `<test>/fakes/Fakes.kt`, `<test>/session/SessionMathTest.kt`

**Interfaces:**
- Produces (`Dtos.kt`): `TrainDto`에 `trainNo: String? = null`, `basis: String? = null`; `@Serializable enum class TrainState { APPROACHING, ARRIVED, DEPARTED, LEFT_PREVIOUS }`; `TrainNearDto(trainNo: String, stationId: String, stationName: String, state: TrainState, direction: String? = null, destination: String? = null, isExpress: Boolean = false, isLast: Boolean = false, etaSec: Int? = null, stopsFromStation: Int)`; `LineTrainsDto(line: String, updatedAt: String, stale: Boolean = false, trains: List<TrainNearDto>)`; `TrainEtaDto(trainNo: String, line: String, found: Boolean, stationId: String? = null, state: TrainState? = null, etaSec: Int? = null, updatedAt: String, stale: Boolean = false)`
- Produces (`ApiService`): `@GET("v1/lines/{line}/trains") suspend fun lineTrains(@Path("line") line: String, @Query("station") station: String, @Query("toward") toward: String?): LineTrainsDto`, `@GET("v1/trains/{trainNo}") suspend fun trainEta(@Path("trainNo") trainNo: String, @Query("line") line: String, @Query("to") to: String): TrainEtaDto`
- Produces: `interface TrainRepository { suspend fun near(line: String, station: String, toward: String?): ApiResult<LineTrainsDto>; suspend fun eta(trainNo: String, line: String, to: String): ApiResult<TrainEtaDto> }`, `DefaultTrainRepository(api, call)`, `AppContainer.trainRepository`; `FakeTrainRepository`(`var nearResult`, `var etaResult`, `val nearCalls: MutableList<Triple<String, String, String?>>`, `val etaCalls`)
- Produces: `TrainPlan(alertAt, trainArrivalAt, destination, trainNo: String? = null)` — `departurePlan`·`minutesBeforePlan`이 고른 열차의 `trainNo`를 담는다(⑭ 계획한 열차).

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class TrainDtoDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun arrivalTrainCarriesTrainNoAndBasis() {
        val t = json.decodeFromString<TrainDto>("""{"destination":"하남검단산","remainingSec":80,"trainNo":"5123","basis":"position"}""")
        assertEquals("5123" to "position", t.trainNo to t.basis)
    }
    @Test fun olderServerTrainHasNoTrainNo() = assertNull(json.decodeFromString<TrainDto>("""{"remainingSec":80}""").trainNo)
    @Test fun lineTrainsFromSpecExample() {
        val body = """{"line":"5호선","updatedAt":"2026-11-05T08:01:30+09:00","stale":false,"trains":[{"trainNo":"5123",
            "stationId":"공덕","stationName":"공덕","state":"DEPARTED","direction":"하행","destination":"하남검단산",
            "isExpress":false,"isLast":false,"etaSec":null,"stopsFromStation":1}]}"""
        assertEquals(TrainNearDto("5123", "공덕", "공덕", TrainState.DEPARTED, "하행", "하남검단산", stopsFromStation = 1),
            json.decodeFromString<LineTrainsDto>(body).trains.single())
    }
    @Test fun trainNotFound() {
        val dto = json.decodeFromString<TrainEtaDto>("""{"trainNo":"5123","line":"5호선","found":false,"updatedAt":"2026-11-05T08:01:30+09:00","stale":false}""")
        assertFalse(dto.found); assertNull(dto.etaSec)
    }
}

// SessionMathTest에 더한다
@Test fun planKeepsTrainNo() = assertEquals("5123",
    departurePlan(listOf(TrainDto("하남검단산", 600, trainNo = "5123")), receivedAt = 0, travelSec = 420, marginSec = 60)!!.trainNo)
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL (컴파일 오류)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 열차 위치 API 데이터와 저장소, 계획한 열차 번호"`

---

### Task 9: 추적 상태와 탄 열차 정하기 (상태 머신)

**Files:**
- Create: `<pkg>/tracking/TrackedLines.kt`, `<test>/session/TrackingFixtures.kt`, `<test>/session/TrackingSessionTest.kt`
- Modify: `<pkg>/session/SessionModel.kt`, `<pkg>/session/SessionMath.kt`, `<pkg>/session/CommuteSession.kt`, `<pkg>/session/RestorePlan.kt`, `<pkg>/session/SessionPorts.kt`, `<pkg>/session/SessionController.kt`, `<pkg>/session/AlertText.kt`, `<pkg>/session/AndroidServiceLauncher.kt`, `<pkg>/di/AppContainer.kt`, `<test>/fakes/Fakes.kt`, `<test>/session/CommuteSessionTest.kt`, `<test>/session/RestorePlanTest.kt`, `<test>/session/AlertTextTest.kt`

**Interfaces:**
- Consumes: Task 8 `TrainPlan.trainNo`
- Produces (`SessionModel.kt`, 저장하는 것은 `@Serializable`이고 새 칸은 모두 기본값이 있다):
  - `AlightPoint`에 `line: String? = null`(지하철 단계의 호선), `boardStationId: String? = null`, `alightStationId: String? = null`, `nextStop: String? = null`(탄 역 다음 정거장 이름 `stops[1]`) — `alightPoints`가 지하철 단계에서 채운다
  - `enum class TrainSource { PLANNED, AUTO, MANUAL }`
  - `data class Tracking(val pointIndex: Int, val line: String, val alightStationId: String, val trainNo: String? = null, val destination: String? = null, val source: TrainSource? = null, val failures: Int = 0, val lost: Boolean = false)` — `pointIndex`는 스펙의 "지하철 단계 번호"로, 그 단계의 하차 지점이 `Riding.points`에서 몇 번째인지다. `trainNo == null`이면 아직 열차를 못 정했다.
  - `data class DetectedTrain(val trainNo: String, val destination: String?)`(사건에만 쓴다)
- Produces (`TrackedLines.kt`): `val TRACKED_LINES = setOf("5호선", "6호선")`(서버 노선 표의 호선과 같게 둔다), `fun AlightPoint.isTrackable(): Boolean`(버스가 아니고 `line in TRACKED_LINES`이고 `alightStationId != null`), `fun trackingFrom(points: List<AlightPoint>, from: Int): Tracking?`(`from` 이상에서 처음 추적할 수 있는 지점, 열차 미정)
- Produces (`CommuteSession.kt`):
  - `Riding`에 `alightLeadSec: Int = 120`(예전 저장분의 기본값), `tracking: Tracking? = null`
  - 사건 `BoardedDetected(val train: DetectedTrain?)`(위치로 탑승을 알아냄, 열차를 못 고르면 null), `TrainIdentified(val pointIndex: Int, val trainNo: String, val source: TrainSource, val destination: String?)`
  - 알림 `AlertContent.TrainDetected(val destination: String?)`, `AlertContent.PickTrain`
  - 이름 바꾸기: 효과 `StartWaitingService`→`StartSessionService`, `StopWaitingService`→`StopSessionService`, 포트 `WaitingServiceLauncher`→`SessionServiceLauncher`(서비스 클래스 이름은 Task 11)
- Produces (`AlertText.kt`): TrainDetected(d) → `"{d}행 열차에 탄 것으로 보여요"`(d가 없으면 `"열차에 탄 것으로 보여요"`), `"내릴 역 도착 시각을 실시간으로 맞춰 드려요"`; PickTrain → `"어느 열차에 탔나요?"`, `"탄 열차를 고르면 내릴 역 도착 시각을 맞춰 드려요"` (7.1)
- Produces (`TrackingFixtures.kt`, Task 10~14가 함께 쓴다): `line5to6Route()` — 5호선 광화문→공덕(600초, stops 광화문·서대문·충정로·애오개·공덕), 도보 120초, 6호선 공덕→삼각지(300초, stops 공덕·효창공원앞·삼각지), `boardStationId`·`alightStationId`는 stops의 처음·끝; `trackedPoints()` — 그 경로를 0초에 탄 지점 두 개: 공덕(TRANSFER, alightAt 600,000, alertAt 480,000, 5호선, 탄 역 광화문, nextStop 서대문), 삼각지(FINAL, 1,020,000, 900,000, 6호선, 탄 역 공덕, nextStop 효창공원앞)

P4 Task 2 전이 표에 더하거나 바꾸는 줄(`now = clock()`):

| 상태 | 사건 | 새 상태 | 효과 |
|---|---|---|---|
| Idle | StartRiding | `Riding(points, alightLeadSec = settings.alightLeadSec, tracking = trackingFrom(points, 0))` | 지점마다 Schedule, tracking이 있으면 StartSessionService, UpdateOngoing |
| Waiting(route 있음) | Boarded | 위와 같은 tracking. 그 `pointIndex`가 0이고 `plan?.trainNo`가 있으면 그 열차(PLANNED, 행선지 `plan.destination`) | tracking이 없을 때만 맨 앞에 StopSessionService. 나머지는 지금과 같다 |
| Waiting(route 있음) | BoardedDetected(train) | Boarded와 같되 train이 있으면 0번 지점 열차가 train(AUTO) | Boarded의 효과에서 마지막 UpdateOngoing 앞에 Alert: train이 있으면 TrainDetected, tracking이 있는데 열차가 비어 있으면 PickTrain |
| Waiting(route 없음) | BoardedDetected | Boarded와 같음 | Boarded와 같음 |
| Riding(tracking 있음, 열차 없음) | BoardedDetected(train) | train이 있으면 그 열차(AUTO) | Alert(TrainDetected 또는 PickTrain), UpdateOngoing |
| Riding(tracking.pointIndex == 사건의 pointIndex) | TrainIdentified | 열차·행선지·출처를 넣고 failures 0, lost false | MANUAL이면 StartSessionService(화면에서 고르므로 시작할 수 있다), UpdateOngoing |
| Riding(tracking 있음) | Cancel, AlarmFired(SessionEnd) | Idle | 맨 앞에 StopSessionService, 나머지는 지금과 같다 |

복원(`planRestore`): Riding이고 tracking이 있고 `onBoot`가 아니면 효과 맨 앞에 StartSessionService. 재부팅 복원에서는 넣지 않는다(7.3).

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class TrackingSessionTest {
    private var now = 0L
    private val settings = AppSettings()
    private val target = WaitTarget("광화문", "광화문", "5호선", "서대문", null)
    private fun waiting(route: RouteDto = line5to6Route(), plan: TrainPlan? = TrainPlan(100_000, 400_000, "하남검단산", trainNo = "5123")) =
        CommuteSession({ now }, SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, route, 420, 60, 180, 120, 0, 1_800_000, plan = plan))
    private fun CommuteSession.tracking() = (state as SessionState.Riding).tracking

    @Test fun boardedUsesPlannedTrainAndKeepsService() {
        val s = waiting()
        val effects = s.handle(SessionEvent.Boarded)
        assertEquals(Tracking(0, "5호선", "공덕", "5123", "하남검단산", TrainSource.PLANNED), s.tracking())
        assertFalse(SessionEffect.StopSessionService in effects)
    }
    @Test fun untrackedRouteStopsServiceAsBefore() {
        val s = waiting(route = specRoute())   // 2호선
        assertEquals(SessionEffect.StopSessionService, s.handle(SessionEvent.Boarded).first()); assertNull(s.tracking())
    }
    @Test fun detectedTrainOverridesPlanAndAlerts() {
        val s = waiting()
        val effects = s.handle(SessionEvent.BoardedDetected(DetectedTrain("5127", "마천")))
        assertEquals("5127" to TrainSource.AUTO, s.tracking()!!.trainNo to s.tracking()!!.source)
        assertTrue(SessionEffect.Alert(AlertContent.TrainDetected("마천")) in effects)
    }
    @Test fun detectedWithoutTrainUsesPlanOrAsksToPick() {
        assertFalse(SessionEffect.Alert(AlertContent.PickTrain) in waiting().handle(SessionEvent.BoardedDetected(null)))
        assertTrue(SessionEffect.Alert(AlertContent.PickTrain) in waiting(plan = null).handle(SessionEvent.BoardedDetected(null)))
    }
    @Test fun manualPickReplacesTrainAndStartsService() {
        val s = waiting(); s.handle(SessionEvent.Boarded)
        val effects = s.handle(SessionEvent.TrainIdentified(0, "5131", TrainSource.MANUAL, "하남검단산"))
        assertEquals("5131" to TrainSource.MANUAL, s.tracking()!!.trainNo to s.tracking()!!.source)
        assertTrue(SessionEffect.StartSessionService in effects)
        assertTrue(s.handle(SessionEvent.TrainIdentified(1, "6201", TrainSource.MANUAL, null)).isEmpty())   // 다른 단계
    }
    @Test fun startRidingTracksFirstTrackedPointWithoutTrain() {
        val s = CommuteSession({ now })
        assertTrue(SessionEffect.StartSessionService in s.handle(SessionEvent.StartRiding(line5to6Route(), settings)))
        assertEquals(Tracking(0, "5호선", "공덕"), s.tracking())
    }
    @Test fun cancelWhileTrackingStopsService() {
        val s = waiting(); s.handle(SessionEvent.Boarded)
        assertEquals(SessionEffect.StopSessionService, s.handle(SessionEvent.Cancel).first())
    }
    @Test fun oldSavedRidingStateStillLoads() {                                        // Review Focus 5
        val old = """{"type":"riding","points":[{"name":"공덕","isBus":false,"kind":"FINAL","alightAt":600000,"alertAt":480000,"stopsToGo":4}],"nextIndex":0}"""
        val riding = Json { ignoreUnknownKeys = true }.decodeFromString<SessionState>(old) as SessionState.Riding
        assertNull(riding.tracking); assertEquals(120, riding.alightLeadSec); assertNull(riding.points.single().line)
    }
    @Test fun restoreRestartsTrackingServiceExceptOnBoot() {
        val riding = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕", "5123"))
        assertEquals(SessionEffect.StartSessionService, planRestore(riding, now = 100_000, onBoot = false).second.first())
        assertFalse(SessionEffect.StartSessionService in planRestore(riding, now = 100_000, onBoot = true).second)
    }
}

// AlertTextTest에 더한다
@Test fun trainDetected() = assertEquals("마천행 열차에 탄 것으로 보여요", alertText(AlertContent.TrainDetected("마천")).first)
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL (컴파일 오류)
- [ ] **Step 3: 구현** — 이름 바꾸기는 IDE 기능으로 하고, 기존 테스트의 기대값은 이름만 바뀐다.
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 탑승 중 추적 상태와 탄 열차 정하기"`

---

### Task 10: 하차 시각 실시간 보정 (상태 머신)

**Files:**
- Create: `<test>/session/TrackingEtaTest.kt`
- Modify: `<pkg>/session/CommuteSession.kt`, `<pkg>/session/OngoingContent.kt`, `<pkg>/ui/common/Copy.kt`(`TRACKING_LOST = "실시간 추적 끊김"`), `<test>/session/OngoingContentTest.kt`

**Interfaces:**
- Consumes: Task 9 `Tracking`, `trackingFrom`, `TrackingFixtures`
- Produces: 사건 `TrainEtaUpdated(val etaSec: Int?, val receivedAt: Long)`, `data object TrainLookupFailed`

Riding이고 tracking에 열차가 있을 때만 처리하고, 아니면 무시한다. `i = tracking.pointIndex`, `lead = alightLeadSec × 1,000`:

| 사건 | 처리 | 효과 |
|---|---|---|
| TrainEtaUpdated(null) | 내릴 역을 지났다 → 다음 단계로 넘김 | |
| TrainEtaUpdated(eta) | `delta = receivedAt + eta × 1,000 − points[i].alightAt`. delta의 절댓값이 20,000보다 크면 `j ≥ i` 지점의 `alightAt += delta`, 그중 아직 안 울린 지점(`j ≥ nextIndex`)은 `alertAt = max(now, alightAt − lead)`, `endAt`이 있으면 `max(now, endAt + delta)`. failures 0, lost false | 안 울린 지점마다 Schedule(Alight(j)), `endAt`이 바뀌었으면 Schedule(SessionEnd), 상태가 바뀌었으면 UpdateOngoing |
| TrainLookupFailed | failures + 1, 3 이상이면 lost. `now ≥ points[i].alightAt`이면 다음 단계로 넘김 | lost가 바뀌었거나 넘겼으면 UpdateOngoing |

다음 단계로 넘김(환승, 7.2): `tracking = trackingFrom(points, i + 1)`. 그 값이 null이면 StopSessionService. 끝에 UpdateOngoing.

진행 중 알림의 Riding 부제: `listOfNotNull(TRACKING_LOST(tracking?.lost일 때), MAY_BE_LATE(정확한 알람이 아닐 때)).joinToString(" · ")`, 비면 null.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class TrackingEtaTest {
    private var now = 0L
    private fun riding(nextIndex: Int = 0, endAt: Long? = null, tracking: Tracking? = Tracking(0, "5호선", "공덕", "5123")) =
        CommuteSession({ now }, SessionState.Riding(trackedPoints(), nextIndex, endAt, alightLeadSec = 120, tracking = tracking))
    private fun CommuteSession.riding() = state as SessionState.Riding

    @Test fun delayOver20sShiftsThisAndLaterPoints() {
        val s = riding(); now = 100_000
        val effects = s.handle(SessionEvent.TrainEtaUpdated(etaSec = 530, receivedAt = 100_000))
        assertEquals(listOf(630_000L, 1_050_000L), s.riding().points.map { it.alightAt })
        assertEquals(listOf(SessionEffect.Schedule(AlarmId.Alight(0), 510_000), SessionEffect.Schedule(AlarmId.Alight(1), 930_000),
            SessionEffect.UpdateOngoing), effects)
    }
    @Test fun changeWithin20sKeepsTimes() {
        val s = riding(); now = 100_000
        assertTrue(s.handle(SessionEvent.TrainEtaUpdated(515, 100_000)).isEmpty())
        assertEquals(600_000L, s.riding().points[0].alightAt)
    }
    @Test fun earlierTrainAlertsNoEarlierThanNow() {
        val s = riding(); now = 500_000
        assertTrue(SessionEffect.Schedule(AlarmId.Alight(0), 500_000) in s.handle(SessionEvent.TrainEtaUpdated(30, 500_000)))
    }
    @Test fun firedPointIsNotRescheduledButCountdownMoves() {
        val s = riding(nextIndex = 1); now = 500_000
        val effects = s.handle(SessionEvent.TrainEtaUpdated(160, 500_000))
        assertEquals(660_000L, s.riding().points[0].alightAt)
        assertFalse(effects.any { it is SessionEffect.Schedule && it.id == AlarmId.Alight(0) })
        assertTrue(SessionEffect.Schedule(AlarmId.Alight(1), 960_000) in effects)
    }
    @Test fun sessionEndMovesWithDelay() {
        val s = riding(nextIndex = 2, endAt = 1_200_000, tracking = Tracking(1, "6호선", "삼각지", "6201")); now = 1_000_000
        assertTrue(SessionEffect.Schedule(AlarmId.SessionEnd, 1_260_000) in s.handle(SessionEvent.TrainEtaUpdated(80, 1_000_000)))
    }
    @Test fun passedAlightStationMovesToNextLine() {
        val s = riding(); now = 610_000
        s.handle(SessionEvent.TrainEtaUpdated(null, 610_000))
        assertEquals(Tracking(1, "6호선", "삼각지"), s.riding().tracking)
    }
    @Test fun passingLastTrackedPointStopsService() {
        val s = riding(tracking = Tracking(1, "6호선", "삼각지", "6201"))
        assertTrue(SessionEffect.StopSessionService in s.handle(SessionEvent.TrainEtaUpdated(null, 0)))
        assertNull(s.riding().tracking)
    }
    @Test fun threeFailuresMarkLostThenEtaClearsIt() {
        val s = riding()
        repeat(3) { s.handle(SessionEvent.TrainLookupFailed) }
        assertTrue(s.riding().tracking!!.lost); assertEquals(600_000L, s.riding().points[0].alightAt)
        s.handle(SessionEvent.TrainEtaUpdated(600, 0))
        assertFalse(s.riding().tracking!!.lost)
    }
    @Test fun failureAfterAlightTimeMovesOn() {
        val s = riding(); now = 600_000
        s.handle(SessionEvent.TrainLookupFailed)
        assertEquals(1, s.riding().tracking!!.pointIndex)
    }
    @Test fun etaWithoutIdentifiedTrainIsIgnored() =
        assertTrue(riding(tracking = Tracking(0, "5호선", "공덕")).handle(SessionEvent.TrainEtaUpdated(530, 0)).isEmpty())
}

// OngoingContentTest에 더한다
@Test fun lostTrackingSubText() {
    val r = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕", "5123", lost = true))
    assertEquals("실시간 추적 끊김", ongoingContent(r, now = 0, exactAlarms = true)!!.subText)
    assertEquals("실시간 추적 끊김 · 알림이 늦을 수 있어요", ongoingContent(r, now = 0, exactAlarms = false)!!.subText)
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 탄 열차 위치로 하차·환승 알림 시각 보정"`

---

### Task 11: 세션 서비스와 추적 조회

**Files:**
- Create: `<pkg>/session/TrackingPoller.kt`, `<pkg>/session/SessionJobs.kt`, `<test>/session/TrackingPollerTest.kt`, `<test>/session/SessionJobsTest.kt`
- Modify: `<pkg>/session/WaitingService.kt` → `<pkg>/session/SessionService.kt`(이름 바꿈), `<pkg>/session/AndroidServiceLauncher.kt`, `AndroidManifest.xml`(`FOREGROUND_SERVICE_LOCATION`, `<service android:name=".session.SessionService" android:foregroundServiceType="dataSync|location" android:exported="false"/>`), `<pkg>/ui/session/SessionViewModel.kt`

**Interfaces:**
- Consumes: Task 8 `TrainRepository.eta`, Task 9·10 사건, P4 `WaitingPoller`, `LocationProvider.hasPermission`
- Produces (`TrackingPoller.kt`): `class TrackingPoller(fetch: suspend (Tracking) -> ApiResult<TrainEtaDto>, dispatch: suspend (SessionEvent) -> Unit, state: () -> SessionState, clock: () -> Long, intervalMs: Long = 30_000)`, `suspend fun run()` — 상태가 Riding이고 `tracking?.trainNo`가 있는 동안 반복: `found`면 `TrainEtaUpdated(etaSec, clock())`, `found == false`나 오류면 `TrainLookupFailed`, 그다음 `delay(intervalMs)`. 서비스는 `fetch = { trains.eta(it.trainNo!!, it.line, it.alightStationId) }`
- Produces (`SessionJobs.kt`):
  - `enum class SessionJob { NONE, WAIT, WAIT_AND_DETECT, TRACK, DETECT }`, `fun sessionJob(state: SessionState, canDetect: Boolean): SessionJob` — Waiting: `canDetect`이고 route의 첫 지하철 단계가 추적 호선이며 `boardStationId`와 `stops[1]`이 있으면 WAIT_AND_DETECT, 아니면 WAIT. Riding: tracking 없음 NONE, 열차 있음 TRACK, 열차 없음은 `canDetect`이고 그 지점에 `boardStationId`·`nextStop`이 있으면 DETECT, 아니면 NONE(직접 고르면 Task 9의 StartSessionService로 다시 켜진다). Idle: NONE
  - `fun sessionServiceType(hasLocationPermission: Boolean, sdkInt: Int): Int` — 29 미만은 0, 그 밖은 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`에 권한이 있으면 `FOREGROUND_SERVICE_TYPE_LOCATION`을 더한다
  - `suspend fun runSessionJobs(state: () -> SessionState, canDetect: () -> Boolean, wait: suspend () -> Unit, track: suspend () -> Unit, detect: suspend (withWaiting: Boolean) -> Unit)` — `sessionJob`이 NONE이 될 때까지 해당 일을 돌린다(각 일은 상태가 바뀌어야 끝난다). WAIT_AND_DETECT와 DETECT는 `detect(true)`·`detect(false)`. Task 14 전까지 서비스의 `canDetect`는 `{ false }`
- Produces: `SessionService` — `startForeground` 유형은 `sessionServiceType(location.hasPermission(), SDK_INT)`, 범위 코루틴에서 `runSessionJobs`로 WaitingPoller·TrackingPoller를 돌리고 끝나면 `stopSelf()`. `AndroidServiceLauncher : SessionServiceLauncher` — `start()`는 Android 12 이상의 `ForegroundServiceStartNotAllowedException`을 잡아 경고 로그만 남긴다(알람은 그대로 남는다)
- Produces: `SessionViewModel.runInAppLoop()`가 대기 조회 대신 같은 `runSessionJobs`(`canDetect = { false }`)를 돌린다 — 앱 안 모드에서도 화면이 열려 있는 동안 추적한다

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class TrackingPollerTest {
    private val riding = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕", "5123"))
    private fun eta(found: Boolean) = ApiResult.Ok(TrainEtaDto("5123", "5호선", found, etaSec = if (found) 200 else null, updatedAt = "t"))

    @Test fun pollsEvery30sForTrackedTrain() = runTest {
        val sent = mutableListOf<SessionEvent>(); val asked = mutableListOf<Tracking>()
        val job = launch { TrackingPoller({ asked += it; eta(true) }, { sent += it }, { riding }, { currentTime }).run() }
        advanceTimeBy(60_001); job.cancel()
        assertEquals(listOf(0L, 30_000L, 60_000L).map { SessionEvent.TrainEtaUpdated(200, it) }, sent)
        assertEquals("5123", asked.first().trainNo)
    }
    @Test fun notFoundAndErrorsAreFailures() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val results = ArrayDeque(listOf(eta(false), ApiResult.Err(ApiError.Network)))
        val job = launch { TrackingPoller({ results.removeFirst() }, { sent += it }, { riding }, { currentTime }).run() }
        advanceTimeBy(30_001); job.cancel()
        assertEquals(listOf<SessionEvent>(SessionEvent.TrainLookupFailed, SessionEvent.TrainLookupFailed), sent)
    }
    @Test fun returnsWithoutCallWhenTrainUnknown() = runTest {
        val unknown = riding.copy(tracking = riding.tracking!!.copy(trainNo = null))
        TrackingPoller({ error("호출하면 안 된다") }, {}, { unknown }, { currentTime }).run()
    }
}

class SessionJobsTest {
    private val target = WaitTarget("광화문", "광화문", "5호선", "서대문", null)
    private fun waiting(route: RouteDto?) = SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, route, 420, 60, 180, 120, 0, 1_800_000)
    private fun riding(tracking: Tracking?) = SessionState.Riding(trackedPoints(), tracking = tracking)

    @Test fun jobFromState() {
        assertEquals(SessionJob.WAIT_AND_DETECT, sessionJob(waiting(line5to6Route()), canDetect = true))
        assertEquals(SessionJob.WAIT, sessionJob(waiting(line5to6Route()), canDetect = false))
        assertEquals(SessionJob.WAIT, sessionJob(waiting(null), canDetect = true))
        assertEquals(SessionJob.TRACK, sessionJob(riding(Tracking(0, "5호선", "공덕", "5123")), true))
        assertEquals(SessionJob.DETECT, sessionJob(riding(Tracking(0, "5호선", "공덕")), true))
        assertEquals(SessionJob.NONE, sessionJob(riding(Tracking(0, "5호선", "공덕")), false))
        assertEquals(SessionJob.NONE, sessionJob(riding(null), true))
        assertEquals(SessionJob.NONE, sessionJob(SessionState.Idle, true))
    }
    @Test fun serviceTypeWithoutLocationPermission() {                                  // Review Focus 4
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, sessionServiceType(hasLocationPermission = false, sdkInt = 34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, sessionServiceType(true, 34))
        assertEquals(0, sessionServiceType(true, 28))
    }
    @Test fun jobsRunUntilNothingLeft() = runTest {
        var state: SessionState = waiting(line5to6Route())
        val ran = mutableListOf<String>()
        runSessionJobs({ state }, { false },
            wait = { ran += "wait"; state = riding(Tracking(0, "5호선", "공덕", "5123")) },
            track = { ran += "track"; state = SessionState.Idle },
            detect = { error("감지하지 않는다") })
        assertEquals(listOf("wait", "track"), ran)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 실기기 확인 (S7 일부)** — 경로 상세 [승차 전 알림] → [탑승했어요] 뒤에도 진행 중 알림이 남고 하차 시각이 위치 기준으로 바뀌는지, 위치 권한을 끈 채 시작해도 앱이 죽지 않는지(Android 14), 재부팅 뒤에는 서비스 없이 알람만 오는지 `docs/measurements/device-checklist.md`에 적는다.
- [ ] **Step 6: Commit** — `git add android docs docs/learning && git commit -m "feat(android): 세션 서비스로 넓히고 탄 열차 30초 추적"`

---

### Task 12: 직접 고르기 (세션 화면과 알림)

**Files:**
- Create: `<pkg>/ui/session/TrainPicker.kt`, `<test>/ui/session/TrainPickerTest.kt`, `<test>/ui/session/SessionViewModelTrainPickerTest.kt`
- Modify: `<pkg>/ui/session/SessionViewModel.kt`, `<pkg>/ui/session/SessionScreen.kt`, `<pkg>/session/SessionIntents.kt`, `<pkg>/session/SessionNotifier.kt`, `<pkg>/MainActivity.kt`, `<pkg>/ui/Destinations.kt`, `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/common/Copy.kt`, `<test>/session/TrackingFixtures.kt`(`nearTrain(dest: String?, station: String, no: String = "5123")` — LEFT_PREVIOUS, `stopsFromStation` 1, `etaSec` null)

**Interfaces:**
- Consumes: Task 8 `TrainRepository.near`, Task 9 `TrainIdentified`·`AlertContent.TrainDetected`·`PickTrain`
- Produces (`TrainPicker.kt`):
  - `fun trainChoiceLabel(train: TrainNearDto): String` — `"{destination}행 · 지금 {역}"`, 역 이름에는 "역"을 한 번만 붙이고 행선지가 없으면 `"열차 · 지금 {역}"` (7.1)
  - `fun trackingStatus(state: SessionState.Riding): String?` — tracking 없음 null, 열차 없음 `"탄 열차를 고르면 도착 시각을 맞춰 드려요"`, lost `"실시간 추적 끊김"`, 그 밖 `"{destination}행 열차 실시간 추적 중"`(행선지가 없으면 `"열차 실시간 추적 중"`)
  - `sealed interface PickerState { data object Hidden; data object Loading; data class Loaded(val trains: List<TrainNearDto>); data class Failed(val message: String) }`
- Produces (`SessionViewModel`): 생성자 끝에 `trains: TrainRepository`. `val picker: StateFlow<PickerState>`, `fun openTrainPicker()`(지금 tracking 지점의 `line`, `boardStationId`, `nextStop`으로 `near`, 오류는 서버 문구 또는 `Copy.SERVER_PROBLEM`), `fun chooseTrain(train: TrainNearDto)`(`TrainIdentified(pointIndex, trainNo, MANUAL, destination)` 뒤 Hidden), `fun closeTrainPicker()`
- Produces: `data class SessionDest(val pickTrain: Boolean = false)`(기존 `navigate(SessionDest)`는 `SessionDest()`로), 세션 화면은 `pickTrain`이면 처음 한 번 `openTrainPicker()`. 세션 화면에 tracking이 있으면 `trackingStatus` 줄과 [탄 열차 고르기](열차 없음) 또는 [열차 바꾸기], 목록 대화상자(빈 목록은 `"근처에 고를 수 있는 열차가 없어요"`)
- Produces: `ACTION_PICK_TRAIN = "io.github.klanary.jigeumchulbal.PICK_TRAIN"`, `pickTrainPendingIntent(context)`(요청 코드 4003). `SessionNotifier.alert`가 TrainDetected 알림에 [다른 열차였어요] 버튼으로, PickTrain 알림에는 누름 동작으로 단다. `MainActivity`의 열기 요청은 `Channel<Boolean>`(true = 열차 고르기)이고 `AppNavHost`는 `navigate(SessionDest(pickTrain = it))`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class TrainPickerTest {
    @Test fun choiceLabel() {
        assertEquals("하남검단산행 · 지금 충정로역", trainChoiceLabel(nearTrain("하남검단산", "충정로")))
        assertEquals("열차 · 지금 서울역", trainChoiceLabel(nearTrain(null, "서울역")))
    }
    @Test fun trackingStatusTexts() {
        val r = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕"))
        val known = r.tracking!!.copy(trainNo = "5123", destination = "하남검단산")
        assertEquals("탄 열차를 고르면 도착 시각을 맞춰 드려요", trackingStatus(r))
        assertEquals("하남검단산행 열차 실시간 추적 중", trackingStatus(r.copy(tracking = known)))
        assertEquals("실시간 추적 끊김", trackingStatus(r.copy(tracking = known.copy(lost = true))))
        assertNull(trackingStatus(r.copy(tracking = null)))
    }
}

class SessionViewModelTrainPickerTest {
    @get:Rule val main = MainDispatcherRule()
    private val trains = FakeTrainRepository()
    private lateinit var controller: SessionController

    private suspend fun viewModel(): SessionViewModel {
        controller = SessionController(testSessionStore(), FakeAlarmScheduler(), FakeNotifier(), FakeLauncher()) { 0L }
        controller.start(SessionEvent.StartRiding(line5to6Route(), AppSettings()), inAppOnly = false)
        return SessionViewModel(controller, { 0L }, FakeStationRepository(), trains = trains)
    }

    @Test fun pickerAsksNearTrainsAtBoardingStationTowardNextStop() = runTest {
        trains.nearResult = ApiResult.Ok(LineTrainsDto("5호선", "t", trains = listOf(nearTrain("하남검단산", "충정로"))))
        val vm = viewModel(); vm.openTrainPicker(); advanceUntilIdle()
        assertEquals(listOf(Triple("5호선", "광화문", "서대문")), trains.nearCalls)
        assertEquals(PickerState.Loaded(listOf(nearTrain("하남검단산", "충정로"))), vm.picker.value)
    }
    @Test fun choosingIdentifiesTrainManually() = runTest {
        val vm = viewModel(); vm.chooseTrain(nearTrain("하남검단산", "충정로")); advanceUntilIdle()
        val t = (controller.state.value as SessionState.Riding).tracking!!
        assertEquals(Triple("5123", TrainSource.MANUAL, "하남검단산"), Triple(t.trainNo, t.source, t.destination))
        assertEquals(PickerState.Hidden, vm.picker.value)
    }
    @Test fun nearErrorShowsServerMessage() = runTest {
        trains.nearResult = ApiResult.Err(ApiError.Server("NOT_FOUND", "LINE_NOT_SUPPORTED", "이 호선은 열차 위치 정보를 지원하지 않아요."))
        val vm = viewModel(); vm.openTrainPicker(); advanceUntilIdle()
        assertEquals(PickerState.Failed("이 호선은 열차 위치 정보를 지원하지 않아요."), vm.picker.value)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 실기기 확인** — 탑승 중 세션 화면의 [탄 열차 고르기]·[열차 바꾸기]로 근처 열차를 골라 추적 줄이 바뀌는지, TrainDetected 알림의 [다른 열차였어요]가 목록을 연 세션 화면으로 가는지 `device-checklist.md`에 적는다.
- [ ] **Step 6: Commit** — `git add android docs docs/learning && git commit -m "feat(android): 탄 열차 직접 고르기와 다른 열차였어요"`

---

### Task 13: 탑승 감지 규칙 (순수 함수)

**Files:**
- Create: `<pkg>/location/LocationFix.kt`, `<pkg>/tracking/BoardingDetector.kt`, `<test>/tracking/BoardingDetectorTest.kt`
- Modify: `<test>/session/TrackingFixtures.kt` — `BOARD = GeoPoint(37.5710, 126.9768)`, `NEXT = GeoPoint(37.5658, 126.9667)`(탈 역에서 약 1,061m), `FAR = GeoPoint(37.56806, 126.97108)`(탈 역 601m, 다음 역 461m), `sampleTarget = DetectionTarget("5호선", "광화문", "서대문", BOARD, NEXT)`, `fix(p: GeoPoint, atSec: Long, acc: Float = 20f) = LocationFix(p, acc, atSec * 1_000)` (Task 14도 쓴다)

**Interfaces:**
- Consumes: Task 8 `TrainNearDto`·`TrainState`, P3 `GeoPoint`
- Produces: `data class LocationFix(val point: GeoPoint, val accuracyM: Float, val at: Long)`
- Produces (`BoardingDetector.kt`):
  - `data class DetectionTarget(val line: String, val boardStationId: String, val nextStop: String, val board: GeoPoint, val next: GeoPoint)`
  - `fun haversineM(a: GeoPoint, b: GeoPoint): Double`(지구 반지름 6,371,000m)
  - `class BoardingDetector(target: DetectionTarget) { fun onFix(fix: LocationFix): Boolean }` — 정확도 > 1,000m는 무시한다. 탈 역 ≤ 300m면 "마지막 가까운 위치"로 기억한다. 기억한 위치가 있고, 탈 역 > 500m이면서 다음 역이 탈 역보다 가깝고, 기억한 위치에서 이 위치까지 속도가 15km/h 이상이면 true(시간 차가 0 이하면 무시)
  - `fun pickBoardedTrain(trains: List<TrainNearDto>): TrainNearDto?` — 후보는 (`stopsFromStation == 0`이고 DEPARTED) 또는 (`stopsFromStation`이 1~2이고 `etaSec == null`). 그중 `(stopsFromStation, 상태 순서 LEFT_PREVIOUS < APPROACHING < ARRIVED < DEPARTED)`가 가장 작은 열차. 없으면 null

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class BoardingDetectorTest {
    @Test fun nearThenFastTowardNextIsBoarding() {
        val d = BoardingDetector(sampleTarget)
        assertFalse(d.onFix(fix(BOARD, 0)))
        assertTrue(d.onFix(fix(FAR, 40)))                     // 약 54km/h
    }
    @Test fun walkingSpeedIsNotBoarding() {
        val d = BoardingDetector(sampleTarget); d.onFix(fix(BOARD, 0))
        assertFalse(d.onFix(fix(FAR, 360)))                   // 약 6km/h
    }
    @Test fun inaccurateFixesIgnored() {
        val d = BoardingDetector(sampleTarget)
        d.onFix(fix(BOARD, 0, acc = 1_500f))
        assertFalse(d.onFix(fix(FAR, 40)))
    }
    @Test fun movingAwayFromNextStationIsNotBoarding() {
        val d = BoardingDetector(sampleTarget); d.onFix(fix(BOARD, 0))
        assertFalse(d.onFix(fix(GeoPoint(37.57394, 126.98252), 40)))   // 탈 역 601m, 다음 역 1,662m
    }
    @Test fun speedIsMeasuredFromLatestNearFix() {
        val d = BoardingDetector(sampleTarget); d.onFix(fix(BOARD, 0)); d.onFix(fix(BOARD, 300))
        assertTrue(d.onFix(fix(FAR, 340)))
    }
    @Test fun withoutNearFixNothingIsDetected() = assertFalse(BoardingDetector(sampleTarget).onFix(fix(FAR, 40)))
}

class PickBoardedTrainTest {
    private fun t(no: String, stops: Int, state: TrainState, eta: Int? = null) =
        TrainNearDto(no, "역", "역", state, etaSec = eta, stopsFromStation = stops)

    @Test fun nearestPassedTrainWins() = assertEquals("B", pickBoardedTrain(listOf(
        t("A", 2, TrainState.ARRIVED), t("B", 1, TrainState.LEFT_PREVIOUS), t("C", -1, TrainState.DEPARTED, eta = 120)))?.trainNo)
    @Test fun departedAtStationCounts() =
        assertEquals("A", pickBoardedTrain(listOf(t("A", 0, TrainState.DEPARTED), t("B", 2, TrainState.ARRIVED)))?.trainNo)
    @Test fun noCandidate() {
        assertNull(pickBoardedTrain(listOf(t("A", 0, TrainState.ARRIVED, eta = 0), t("B", 3, TrainState.DEPARTED), t("C", -2, TrainState.DEPARTED, eta = 200))))
        assertNull(pickBoardedTrain(emptyList()))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 위치로 탑승을 알아보는 규칙과 탄 열차 고르기"`

---

### Task 14: 탑승 감지 연결 (세션 대기 중, 경로 상세)

**Files:**
- Create: `<pkg>/tracking/DetectionTargets.kt`, `<pkg>/tracking/BoardingWatch.kt`, `<pkg>/ui/routedetail/BoardingPromptViewModel.kt`, `<test>/tracking/DetectionTargetsTest.kt`, `<test>/tracking/BoardingWatchTest.kt`, `<test>/ui/routedetail/BoardingPromptViewModelTest.kt`
- Modify: `<pkg>/location/LocationProvider.kt`, `<pkg>/session/SessionService.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`, `<pkg>/ui/common/Copy.kt`, `<test>/fakes/Fakes.kt`(`FakeLocationProvider.fixes`, `FakeStationRepository.searchByQuery`), `<test>/session/TrackingFixtures.kt`(`GWANGHWAMUN = StationDto("광화문", "광화문", listOf("5호선"), 37.5710, 126.9768)`, `SEODAEMUN = StationDto("서대문", "서대문", listOf("5호선"), 37.5658, 126.9667)` — `BOARD`·`NEXT`와 같은 좌표)

**Interfaces:**
- Consumes: Task 13 전부, Task 11 `runSessionJobs`, Task 9 `BoardedDetected`·`TrainIdentified`, Task 8 `TrainRepository.near`, P3 `StationRepository.search`
- Produces (`LocationProvider`): `fun updates(intervalMs: Long = 10_000): Flow<LocationFix> = emptyFlow()`(인터페이스 기본값). `FusedLocationProvider.updates`는 권한이 있으면 `callbackFlow`로 `requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).build(), …)`를 열고 닫힐 때 `removeLocationUpdates`. 권한이 없거나 `SecurityException`이면 빈 흐름
- Produces (`DetectionTargets.kt`): `suspend fun detectionTarget(line: String, boardStationId: String, nextStop: String, search: suspend (String) -> ApiResult<List<StationDto>>): DetectionTarget?` — 탈 역은 `search(boardStationId)`에서 id가 같은 역, 다음 역은 `search(nextStop)`에서 `lines`에 호선이 있는 첫 역. 못 찾거나 오류면 null(역 이름으로만 찾고 폰 위치는 보내지 않는다)
- Produces (`BoardingWatch.kt`): `suspend fun awaitBoarding(target: DetectionTarget, fixes: Flow<LocationFix>): Boolean`(감지하면 true, 흐름이 끝나면 false), `suspend fun boardedTrain(near: suspend () -> ApiResult<LineTrainsDto>): DetectedTrain?`(`pickBoardedTrain` 결과, 오류면 null)
- Produces (`SessionService`): `canDetect = { location.hasPermission() }`, `detect(withWaiting)` — 대상 지점(대기 중이면 route의 첫 지하철 단계, 탑승 중이면 `points[tracking.pointIndex]`) → `detectionTarget` → `awaitBoarding(target, location.updates())` → `boardedTrain { trains.near(line, boardStationId, nextStop) }` → `dispatch(BoardedDetected(train))`. 대상을 못 만들었거나 흐름이 끝났으면 `awaitCancellation()`(같은 일을 되풀이하지 않는다). `withWaiting`이면 WaitingPoller와 함께 돌리고 하나가 끝나면 다른 하나를 취소한다.
- Produces (`BoardingPromptViewModel`): 생성자 `(location: LocationProvider, stations: StationRepository, trains: TrainRepository, controller: SessionController)`, `data class BoardingPrompt(val train: DetectedTrain?)`, `val prompt: StateFlow<BoardingPrompt?>`, `suspend fun watch(route: RouteDto)` — 세션이 진행 중이거나, 권한이 없거나, 첫 지하철 단계가 추적 호선이 아니거나, 이 화면에서 [아니에요]를 눌렀으면 바로 끝난다. 감지하면 `prompt`를 채운다. `fun dismiss()`, `suspend fun confirmStarted()` — 세션이 시작된 뒤 부른다. 열차가 있으면 지금 tracking 지점으로 `TrainIdentified(…, AUTO, …)`를 보내고 `prompt = null`
- 경로 상세: `repeatOnLifecycle(STARTED)` 안에서 `watch(route)`. 대화상자 `"{d}행에 타셨나요?"`(열차가 없으면 `"열차에 타셨나요?"`), [하차 알림 시작] = 기존 `startSession { StartRiding(route.withoutPaths(), it) }`이고 시작되면 `confirmStarted()` 뒤 세션 화면, [아니에요] = `dismiss()` (7.1)

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class DetectionTargetsTest {
    private val byQuery = mapOf("광화문" to listOf(GWANGHWAMUN), "서대문" to listOf(SEODAEMUN))

    @Test fun resolvesBoardAndNextStation() = runTest {
        assertEquals(sampleTarget, detectionTarget("5호선", "광화문", "서대문") { ApiResult.Ok(byQuery[it].orEmpty()) })
    }
    @Test fun nextStationMustBeOnSameLine() = runTest {
        assertNull(detectionTarget("5호선", "광화문", "서대문") { q ->
            ApiResult.Ok(if (q == "광화문") listOf(GWANGHWAMUN) else listOf(SEODAEMUN.copy(lines = listOf("2호선")))) })
    }
    @Test fun searchErrorGivesNull() = runTest {
        assertNull(detectionTarget("5호선", "광화문", "서대문") { ApiResult.Err(ApiError.Network) })
    }
}

class BoardingWatchTest {
    @Test fun detectsFromFixes() = runTest { assertTrue(awaitBoarding(sampleTarget, flowOf(fix(BOARD, 0), fix(FAR, 40)))) }
    @Test fun endedFlowIsNotBoarding() = runTest { assertFalse(awaitBoarding(sampleTarget, flowOf(fix(BOARD, 0)))) }
    @Test fun pickedTrainBecomesDetected() = runTest {
        assertEquals(DetectedTrain("5123", "하남검단산"),
            boardedTrain { ApiResult.Ok(LineTrainsDto("5호선", "t", trains = listOf(nearTrain("하남검단산", "서대문")))) })
        assertNull(boardedTrain { ApiResult.Err(ApiError.Network) })
    }
}

class BoardingPromptViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val location = FakeLocationProvider(permission = true, point = null).apply { fixes = flowOf(fix(BOARD, 0), fix(FAR, 40)) }
    private val stations = FakeStationRepository().apply { searchByQuery = mapOf("광화문" to listOf(GWANGHWAMUN), "서대문" to listOf(SEODAEMUN)) }
    private val trains = FakeTrainRepository().apply {
        nearResult = ApiResult.Ok(LineTrainsDto("5호선", "t", trains = listOf(nearTrain("하남검단산", "서대문"))))
    }
    private val controller = SessionController(testSessionStore(), FakeAlarmScheduler(), FakeNotifier(), FakeLauncher()) { 0L }
    private fun vm() = BoardingPromptViewModel(location, stations, trains, controller)

    @Test fun promptAfterBoardingThenStartIdentifiesTrain() = runTest {
        val vm = vm(); vm.watch(line5to6Route())
        assertEquals(BoardingPrompt(DetectedTrain("5123", "하남검단산")), vm.prompt.value)
        controller.start(SessionEvent.StartRiding(line5to6Route(), AppSettings()), inAppOnly = false); vm.confirmStarted()
        val t = (controller.state.value as SessionState.Riding).tracking!!
        assertEquals("5123" to TrainSource.AUTO, t.trainNo to t.source); assertNull(vm.prompt.value)
    }
    @Test fun noWatchWithoutPermissionOrDuringSession() = runTest {
        location.permission = false; vm().watch(line5to6Route())
        location.permission = true; controller.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = false)
        vm().watch(line5to6Route())
        assertTrue(trains.nearCalls.isEmpty())
    }
    @Test fun dismissHidesPromptAndStopsAsking() = runTest {
        val vm = vm(); vm.watch(line5to6Route()); vm.dismiss()
        vm.watch(line5to6Route())
        assertNull(vm.prompt.value); assertEquals(1, trains.nearCalls.size)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 실기기 확인 (S7)** — 5호선 → 6호선 통학에서 ① 세션 대기 중 탑승하면 누르지 않아도 탑승 단계로 넘어가고 "OO행 열차에 탄 것으로 보여요" 알림이 오는지(감지까지 걸린 시간), ② 공덕 환승 뒤 6호선 열차가 다시 정해지는지, ③ 경로 상세만 보던 중 탑승하면 "OO행에 타셨나요?"가 뜨는지, ④ 하차·환승 알림 시각이 위치 기준으로 바뀌었는지를 `docs/measurements/device-checklist.md`에 적는다.
- [ ] **Step 6: Commit** — `git add android docs docs/learning && git commit -m "feat(android): 위치로 탑승 자동 감지(세션 대기 중, 경로 상세)"`
