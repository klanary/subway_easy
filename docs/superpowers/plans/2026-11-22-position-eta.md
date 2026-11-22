# 열차 위치로 이 역 도착 시각 계산하기 — 구현 계획

**Goal:** 서울시 실시간 위치 API의 열차 위치 + VM 실측 구간 시간(B2)으로 이 역 도착 시각을 계산해, [지금]의 코레일 노선 어림을 대신하고 [시각 선택] 60분 안에 쓴다. 노선별로 평가해 이기는 노선에서만 켠다.

**Architecture:** 서버에 위치 받기(`SeoulPositionClient` + 20초 캐시 `PositionService`)와 순수 계산기(`position_eta.estimate_arrival`)를 더하고, `ArrivalService`가 노선별 켜기 목록에 따라 합친다. 계산기는 경로를 실측 운행 패턴(`TripsData`)으로, 구간 시간을 `MeasuredData.hop_seconds`로 얻는다. 같은 계산기로 VM 데이터에서 되돌려 보기(`ml/position_eval.py`)를 돌려 켜기 목록을 정한다. 앱은 열차마다 근거를 표시하고 [시각 선택] 60분 안이면 `arrivals?at=`을 부른다.

**Tech Stack:** Python 3.13, FastAPI, pydantic-settings, pytest·respx(서버). Kotlin, Jetpack Compose, Robolectric·Roborazzi(앱).

**Spec:** `docs/superpowers/specs/2026-11-22-position-eta-design.md`

## Global Constraints

- 위치 API 주소: `{SEOUL_REALTIME_BASE}/{seoul_realtime_api_key}/json/realtimePosition/0/200/{quote(호선)}`. 정상 `errorMessage.code == "INFO-000"`, 데이터 없음 `INFO-200`(빈 목록). 응답 목록 키 `realtimePositionList`.
- 위치 행의 `statnTid`·`statnTnm`은 **종착역**이다. `trainSttus` 0 진입·1 도착·2 출발·3 전역출발. `updnLine` "0" 상행·내선, "1" 하행·외선. `directAt` 0 일반·1 급행·7 특급·9 ITX(0이 아니면 급행 구간 시간).
- 노선별 캐시 20초(`ARRIVALS_TTL_SEC`와 같은 값).
- `recptnDt`가 지금보다 미래면 지금으로 본다. 600초 넘게 오래된 행은 쓰지 않는다.
- 위치 API 실패·빈 응답은 오류로 올리지 않는다(그 노선은 위치 계산 없이 지금처럼).
- [시각 선택] 위치 계산은 지금부터 60분 안만. 한 방향 최대 4대.
- 시간표 채우기는 마지막 위치 계산 열차보다 60초 넘게 뒤인 것만.
- `Train.basis` 값은 `"realtime" | "position" | "timetable" | "measured"`.
- 켜기 기준: [지금] 위치 계산 MAE < 문구 어림 MAE 이고 ±60초 비율 ≥ 문구 어림. [시각 선택] 위치 계산 MAE < 시간표 MAE(지평 5·15·30·60분별).
- 코드 주석·문서·커밋 메시지는 한국어, 쉬운 말.
- 서버 명령은 `server/`에서 `.venv/Scripts/python.exe -m pytest`, `.venv/Scripts/python.exe -m ruff check app ml tests`, `ruff format`. 앱은 `android/`에서 `./gradlew.bat :app:recordRoborazziDebug`(전체 테스트 + 스크린샷).
- VM을 건드리는 단계(`git pull`, 스크립트 실행)는 실행 전에 사용자에게 확인한다.

## Review Focus

1. **위치 API가 비거나 반쪽일 때** [지금] 화면이 오늘과 같아야 한다(열차가 사라지지 않음). → Task 5 `test_position_outage_keeps_today_behavior`.
2. **같은 열차가 두 번** 보이면 안 된다(도착 API 열차 = 위치 열차, 위치 계산 열차 ≈ 시간표 열차). → Task 6 `test_at_mode_no_duplicates`.
3. **이 역 앞에서 끝나는 열차**(행선지가 이 역보다 앞)는 계산되면 안 된다. → Task 3 `test_train_ending_before_target_is_none`.
4. **갈래에서 경로가 둘 이상**이면 계산하지 않는다. → Task 3 `test_ambiguous_branch_is_none`.
5. **고른 시각이 지난 시각이거나 60분 넘게 뒤**면 지금처럼(지난 시각은 지금으로, 60분 넘으면 시간표만). → Task 6 `test_at_beyond_60_minutes_is_timetable_only`, `test_at_in_past_means_now`.

---

### Task 1: 서버용 실측 결과물에 1~9호선 역 연결 넣기

`server_b1`이 1~9호선 `stations`를 걸러 서버의 `statn_of`가 비고, `MeasuredData.statn_of`는 칸(cells)이 있는 역에서만 만들어져 1~9호선 구간 시간을 찾지 못한다.

**Files:**
- Modify: `server/ml/publish.py`(`server_b1`의 `stations` 거르기)
- Modify: `server/app/services/measured.py`(`from_artifacts`에서 `statn_of`를 `stations` 전체로 만들기)
- Test: `server/tests/unit/test_ml_publish.py`, `server/tests/unit/test_measured.py`

**Interfaces:**
- Produces: `server_b1(b1)["stations"]`는 모든 호선의 `"호선|statn_id": {"station_id", "name"}`. `MeasuredData.statn_of`는 `stations`에 있는 모든 (호선, 우리 역 id) → statn_id. `cells`·`scopes`·`modes`는 그대로 1~9호선을 거른다.

- [ ] **Step 1: 실패 테스트**
  - `test_ml_publish.py::test_server_b1_keeps_stations_of_official_lines`: 입력 b1에 `"stations": {"2호선|1002000201": {"station_id": "시청", "name": "시청"}}`과 2호선 칸이 있을 때 `server_b1` 결과 `stations`에 그 키가 있고 `cells`에는 2호선 키가 없다.
  - `test_measured.py::test_hops_work_for_lines_without_cells`: `stations`에 `"2호선|A": 시청`, `"2호선|B": 을지로입구`만 있고 칸은 없으며 b2 `hops`에 `"2호선|0|A|B|0": 95`가 있을 때 `hop_seconds("2호선", "내선", "시청", "을지로입구", express=False, at=...) == 95.0`.
- [ ] **Step 2: 실패 확인** — `.venv/Scripts/python.exe -m pytest tests/unit/test_ml_publish.py tests/unit/test_measured.py -q` → 두 테스트 FAIL.
- [ ] **Step 3: 구현** — `server_b1`의 `stations` 거르기 조건을 없앤다. `from_artifacts`는 `stations` 전체를 돌며 `station_id`가 있는 것으로 `statn_of`를 먼저 채운다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS, 전체 `pytest -q` PASS.
- [ ] **Step 5: 커밋** — `feat(ml): 서버용 실측 결과물에 1~9호선 역 연결을 넣는다(구간 시간 조회용)`

### Task 2: 위치 받기(클라이언트 + 20초 캐시 + 해석)

**Files:**
- Create: `server/app/clients/seoul_position.py`
- Create: `server/app/services/position_service.py`
- Modify: `server/app/core/container.py`(조립), `server/app/core/config.py`(Task 5·6에서 쓸 설정 두 개도 여기서 더한다)
- Test: `server/tests/unit/test_position_service.py`

**Interfaces:**
- Produces:
  - `class SeoulPositionClient: async def fetch_positions(self, line: str) -> list[dict]` — INFO-200이면 `[]`, 그 밖의 코드·HTTP 오류는 `UpstreamError`(기존 `call_upstream`·`QuotaGuard` 재사용, 실시간 키·가드 `seoulRealtime`).
  - `@dataclass(frozen=True) class TrainPosition: line: str; direction: str; train_no: str; station_id: str; status: int; seen_at: datetime; dest_name: str | None; express: bool`
  - `class PositionService: async def positions(self, line: str) -> list[TrainPosition]` — 20초 캐시. 실패는 로그만 남기고 `[]`. 역은 `StationIndex.resolve(statnNm, line)`로 잇고 못 이으면 행을 버린다. `direction`은 `updnLine` "0"/"1" → `measured.direction_name(line, code)`. `seen_at`은 `recptnDt`(미래면 지금). 600초 넘게 오래된 행은 버린다. `express = directAt not in ("0", "", None)`.
  - `Settings.position_now_lines: list[str] = []`, `Settings.position_at_lines: list[str] = []`.

- [ ] **Step 1: 실패 테스트** (respx로 위치 API를 흉내 낸다)
  - `test_parses_rows_into_positions`: 2호선 행 1개(`statnNm` "시청", `updnLine` "0", `trainSttus` "1", `directAt` "0", `statnTnm` "성수", `trainNo` "2155", `recptnDt` 지금-10초) → `TrainPosition(line="2호선", direction="내선", train_no="2155", station_id="시청", status=1, dest_name="성수", express=False)`.
  - `test_itx_counts_as_express`: `directAt` "9" → `express is True`.
  - `test_stale_and_unknown_rows_dropped`: `recptnDt` 지금-601초 행, `statnNm` "없는역" 행 → 결과 `[]`.
  - `test_future_seen_at_becomes_now`: `recptnDt` 지금+120초 → `seen_at == 지금`.
  - `test_no_data_and_errors_give_empty_list`: INFO-200 → `[]`, HTTP 500 → `[]`(예외 없음).
  - `test_cached_for_20_seconds`: 같은 노선 두 번(19초 뒤) 부르면 HTTP 1번, 21초 뒤 2번.
- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_position_service.py -q` → FAIL(모듈 없음).
- [ ] **Step 3: 구현** — 위 Interfaces 그대로. 캐시는 기존 `TtlCache`를 노선 키로 쓴다. `container.py`에서 `PositionService`를 만들어 `ArrivalService`에 넘길 준비(인자는 Task 5에서 받는다).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 전체 PASS, ruff 통과.
- [ ] **Step 5: 커밋** — `feat(server): 실시간 위치 API 받기(노선별 20초 캐시, 실패는 빈 목록)`

### Task 3: 도착 시각 계산기(순수 함수)

**Files:**
- Create: `server/app/services/position_eta.py`
- Test: `server/tests/unit/test_position_eta.py`

**Interfaces:**
- Consumes: `TrainPosition`(Task 2), `TripsData.day(day_type) -> DayTrips`, `DayTrips.stop_index/patterns_at/station_id`, `Pattern.stops/trip_meta`, `MeasuredData.hop_seconds`(Task 1).
- Produces:
  - `LEAD_SEC: dict[int, int] = {0: 20, 1: 0, 2: 0, 3: 90}` — 그 행의 현재 역에 도착하는(했던) 시각 = `seen_at + LEAD_SEC[status]`. 출발(2)은 0으로 둔다: 위치 API가 이동 중인 열차를 다음 역 "출발"로 먼저 잡는 순서 뒤틀림이 있어(11-20 2호선 1.4%), 정차분을 빼면 시각이 앞당겨졌다가 다음 갱신에 뒤로 튄다. 0이면 뒤로 튀는 폭이 작다(설계 6.1의 뒤틀림 처리 결정).
  - `def path_between(day: DayTrips, line: str, direction: str, from_id: str, to_id: str, *, dest_name: str | None, express: bool) -> list[str] | None` — 같은 호선·방향이고 `from_id` 다음에 `to_id`가 오는 패턴들 중, 그 패턴의 운행(`trip_meta`) 가운데 급행 여부가 같고 행선지가 `dest_name`인 것이 하나라도 있는 패턴만 남긴다. 남은 패턴들의 `from_id`~`to_id` 정차 목록이 모두 같으면 그 목록, 다르면(갈래 모호) 또는 없으면 `None`. `dest_name`이 None이면 행선지 조건 없이 본다.
  - `def estimate_arrival(pos: TrainPosition, target_id: str, *, trips: TripsData, measured: MeasuredData, now: datetime) -> datetime | None` — 목적역이 현재 역이면: 상태 2면 `None`(떠났다), 아니면 `max(now, seen_at + LEAD_SEC[status])`. 아니면 `path_between` 경로의 이웃 정차 쌍마다 `hop_seconds(..., express=pos.express, at=now)`을 더해 `seen_at + LEAD_SEC[status] + 합`. 하나라도 `None`이면 `None`. 결과가 `now`보다 이르면 `now`. 행선지가 목적역이면 `None`(이 역에서 끝나는 열차, 탈 수 없다).
  - 운행일 요일 구분은 `timetable.service_days`/`week_tag` 규칙(01시 전은 전날)을 따른다.

- [ ] **Step 1: 실패 테스트** (작은 `TripsData`·`MeasuredData`를 테스트 안에서 만든다: 2호선 내선 A→B→C→D, 구간 90·100·110초)
  - `test_sums_hops_from_current_station`: A 도착(1), seen_at=t → C 도착 `t + 190초`.
  - `test_status_leads`: A 진입(0) → C `t+20+190`, A 전역출발(3) → C `t+90+190`, A 출발(2) → C `t+190`.
  - `test_train_at_target`: C 진입(0) 목적 C → `t+20`, C 출발(2) 목적 C → `None`.
  - `test_missing_hop_is_none`: B→C 구간 시간이 없으면 `None`.
  - `test_train_ending_before_target_is_none`: 행선지 B인 열차, 목적 C → `None`(그 행선지를 가진 패턴 운행 중 C를 지나는 것이 없다).
  - `test_train_ending_at_target_is_none`: 행선지 C, 목적 C → `None`.
  - `test_ambiguous_branch_is_none`: A 다음에 B로 가는 패턴과 B'로 가서 C로 가는 패턴이 둘 다 있고 행선지 조건으로 갈리지 않을 때 목적 C → `None`.
  - `test_express_uses_express_pattern_and_hops`: 급행 패턴 A→C(B 통과), 급행 구간 A→C 150초, `express=True` → `t+150`.
  - `test_never_earlier_than_now`: 합이 지금보다 이르면 `now`.
- [ ] **Step 2: 실패 확인** — `pytest tests/unit/test_position_eta.py -q` → FAIL.
- [ ] **Step 3: 구현** — Interfaces 그대로. 순수 함수(외부 호출 없음).
- [ ] **Step 4: 통과 확인** — PASS, 전체 PASS.
- [ ] **Step 5: 커밋** — `feat(server): 열차 위치 + 구간 시간으로 이 역 도착 시각 계산`

### Task 4: 되돌려 보기(노선별 평가 스크립트)

**Files:**
- Create: `server/ml/position_eval.py`
- Test: `server/tests/unit/test_ml_position_eval.py`

**Interfaces:**
- Consumes: `ml.dataset.load_events/build_runs`, `ml.b2.build_b2`(학습 날짜로 구간 시간), `ml.publish.server_b1/server_b2` + `MeasuredData.from_artifacts`, `TripsData`(VM `ml_data/artifacts`의 최신 운행표 결과물), `position_eta.estimate_arrival`·`path_between`, 시간표: 1~9호선은 `ml.b0.TimetableCache`, 그 밖은 실측 B1(`MeasuredData.departures`).
- Produces: `python -m ml.position_eval --db ml_data/collector.sqlite --artifacts ml_data/artifacts --timetable-cache ml_data/timetable_cache --out ml_data/position_eval.md` 와 `def evaluate(...) -> dict[str, LineScores]`. 출력 표: 호선 × 방법(문구 어림 / 위치 계산 / 시간표) × 지평(0·5·15·30·60분)의 MAE·±60초 비율·건수, 맨 아래 제안 켜기 목록 두 줄(`position_now_lines`, `position_at_lines`).

평가 방법(사용자가 보는 질문 그대로):
- 시험 날짜(`evaluate.split_by_date`의 뒤 날짜)에서, 07~22시 10분마다, 호선마다 역 20곳 표본 × 방향 2.
- 질문: "시각 t에 이 역에서 t+h 이후 처음 오는 열차는 언제?" 실제 = 그 역·방향 도착(1) 이벤트 중 t+h 이후 첫 것.
- 위치 계산: t 직전(600초 안) 각 열차의 마지막 관측 → `estimate_arrival` → t+h 이후 첫 값.
- 문구 어림(h=0만): 같은 열차들의 "이 역까지 남은 역 수 × 120초"(경로는 `path_between`) → 첫 값.
- 시간표: t+h 이후 첫 시간표 출발.
- 오차 = 예측 − 실제. 한 방법이라도 답하지 못한 관측은 그 방법 칸에서만 뺀다(건수 함께 적음).

- [ ] **Step 1: 실패 테스트** — `test_ml_position_eval.py::test_scores_on_synthetic_day`: `tests/ml_helpers.trip`으로 9호선 하루치(열차 6대, 구간 120초 일정)를 학습·시험 날짜에 넣으면 위치 계산 h=0 MAE ≤ 5초, 문구 어림도 ≤ 5초(120초 구간이므로), 시간표가 없으면 시간표 칸 건수 0. `test_report_suggests_lines`: 위치 계산 MAE가 더 작은 호선만 `position_now_lines`에 들어간다.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — 위 Interfaces·평가 방법대로. 읽기만 한다(수집 DB는 `mode=ro`).
- [ ] **Step 4: 통과 확인** — PASS, 전체 PASS.
- [ ] **Step 5: 커밋** — `feat(ml): 위치 계산 되돌려 보기(문구 어림·시간표와 노선별 비교)`

### Task 5: [지금]에 위치 계산 합치기

**Files:**
- Modify: `server/app/services/arrival_service.py`, `server/app/services/remaining_time.py`, `server/app/models/schemas.py`, `server/app/core/container.py`
- Test: `server/tests/integration/test_arrivals_position.py`

**Interfaces:**
- Consumes: `PositionService.positions`, `estimate_arrival`, `Settings.position_now_lines`, `MeasuredStore.current()/current_trips()`.
- Produces: `Train.basis: Literal["realtime","position","timetable","measured"] = "realtime"`. 시간표로 채운 그룹의 열차는 `timetable`/`measured`. `def has_exact_seconds(item: dict) -> bool`(`barvlDt` > 0) in `remaining_time.py`. `ArrivalService.__init__`에 `positions: PositionService | None = None`, `measured: MeasuredStore | None = None`, `now_lines: frozenset[str] = frozenset()` 인자.

규칙: 노선이 `position_now_lines`에 있고, 열차가 `has_exact_seconds`가 아니면, 그 노선 위치에서 `train_no`가 같은 열차를 찾아 `estimate_arrival`이 값을 주면 `remaining_sec`을 그 값으로, `basis="position"`. 못 찾거나 `None`이면 그대로(`realtime`). 위치 받기는 그 노선이 켜져 있고 바꿀 열차가 있을 때만 부른다. 같은 그룹 열차는 다시 남은 시간 순으로 정렬.

- [ ] **Step 1: 실패 테스트**(설정에 `position_now_lines=["1호선"]`, 실측·운행표 픽스처를 서비스에 넣는다)
  - `test_zero_barvl_train_uses_position`: 도착 API 1호선 열차(`barvlDt` 0, "[3]번째 전역", `btrainNo` "0145") + 위치 API 같은 번호 열차 → `basis == "position"`, `remainingSec`이 계산값.
  - `test_exact_seconds_kept`: `barvlDt` 200인 열차는 `basis == "realtime"`, `remainingSec` 그대로.
  - `test_line_not_enabled_unchanged`: 2호선(목록에 없음)은 위치 API를 부르지 않는다(route 호출 0).
  - `test_position_outage_keeps_today_behavior`: 위치 API INFO-200·HTTP 500일 때 응답이 위치 기능 없을 때와 같다(열차 수·남은 시간·`basis` "realtime").
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — 규칙대로. 기존 테스트(`test_arrivals.py`의 키 집합 검사 등)는 `basis`를 더해 맞춘다.
- [ ] **Step 4: 통과 확인** — PASS, 전체 PASS.
- [ ] **Step 5: 커밋** — `feat(server): [지금] 코레일 노선 남은 시간을 열차 위치로 계산(노선별 켜기)`

### Task 6: [시각 선택] 60분 안(`arrivals?at=`)

**Files:**
- Modify: `server/app/api/stations.py`(`/v1/stations/{id}/arrivals`에 쿼리 `at`), `server/app/services/arrival_service.py`
- Test: `server/tests/integration/test_arrivals_at.py`

**Interfaces:**
- Consumes: Task 5 전부, `TimetableService.trains`, `Settings.position_at_lines`.
- Produces: `GET /v1/stations/{id}/arrivals?at=ISO시각`. `ArrivalService.get_arrivals(station_id, line=None, towards=None, at: datetime | None = None)`. `at`이 지금 이전이면 지금으로 본다. `at`이 지금+60분 넘게 뒤면 시간표만(그룹 `source` "timetable"/"measured", 열차 `basis` 같음). 60분 안이면 방향마다: ① 도착 API 열차 중 `has_exact_seconds`이고 도착 ≥ at → `realtime` ② 노선이 `position_at_lines`에 있으면 그 방향으로 오는 위치 계산 열차 중 ≥ at → `position`(①과 번호가 같은 열차는 빼고) ③ 합쳐 시각순 최대 4대, 모자라면 시간표 열차를 마지막 ①·② 열차보다 60초 넘게 뒤인 것만 더한다. `remainingSec`은 지금 기준, `message`는 `"HH:MM 도착 예정"`.

- [ ] **Step 1: 실패 테스트**
  - `test_at_mode_mixes_position_then_timetable`: 위치 계산 2대(at 이후) + 시간표 5대 → 4대, 앞 2대 `position`, 뒤 2대 `timetable`이고 시간표 열차는 마지막 위치 열차+60초 넘게 뒤.
  - `test_at_mode_no_duplicates`: 도착 API(`barvlDt` 300, 번호 X)와 위치(번호 X)가 같은 열차면 한 번만, `basis "realtime"`. 시간표 열차가 위치 열차와 30초 차이면 빠진다.
  - `test_at_line_not_enabled_is_timetable_only`.
  - `test_at_beyond_60_minutes_is_timetable_only`: at = 지금+61분 → 위치 API 부르지 않음.
  - `test_at_in_past_means_now`: at = 지금-5분 → at 없는 응답과 같은 열차.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — 규칙대로. 방향별 위치 열차는 그 노선 위치 전부에 `estimate_arrival(목적=이 역)`을 돌려 `None`이 아닌 것.
- [ ] **Step 4: 통과 확인** — PASS, 전체 PASS, ruff.
- [ ] **Step 5: 커밋** — `feat(server): 고른 시각 60분 안은 열차 위치로 계산한 도착(arrivals?at=)`

### Task 7: 앱 — 열차마다 근거, [시각 선택] 60분 안은 `arrivals?at=`

**Files:**
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/data/remote/Dtos.kt`(`TrainDto.basis`), `.../data/remote/ApiService.kt`(`arrivals`에 `@Query("at") at: String? = null`), 역 저장소(`stations.arrivals`에 `at` 인자), `.../ui/linemap/StationSheetViewModel.kt`, `.../ui/linemap/StationSheet.kt`, `.../ui/common/Copy.kt`
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/linemap/StationSheetViewModelTest.kt`, `StationSheetContentScreenshotTest.kt`

**Interfaces:**
- Consumes: 서버 `Train.basis`, `arrivals?at=`.
- Produces: `TrainDto.basis: String = "realtime"`. `TrainLine.basis: String? = null`(표식 글자, 실시간이면 null). `Copy.BY_POSITION = "위치 계산"`. 시간표·실측 표식은 기존 `Copy.BY_TIMETABLE`·`Copy.BY_MEASURED`를 열차 단위로 쓴다(`DirectionTrains.basis`는 남기되, 열차마다 다르면 표시는 열차 쪽).

규칙: `SheetTime.CUSTOM`이고 고른 시각이 지금+60분 안이면 `stations.arrivals(stationId, at = 고른 시각)`을 부르고 열차를 시각(`clock = true`, "HH:mm")으로 보인다. 60분 넘으면 지금처럼 `loadTimetable`. 열차 줄 옆에 근거 표식(`Tag`)을 열차마다 붙인다. 실시간은 표식 없음.

- [ ] **Step 1: 실패 테스트**
  - `customWithin60MinutesUsesArrivalsAt`: 가짜 저장소에서 `arrivals(at=…)` 호출이 일어나고 열차 `basis` "position" → `TrainLine.basis == Copy.BY_POSITION`.
  - `customBeyond60MinutesUsesTimetable`: `timetable` 호출, `arrivals(at)` 호출 없음.
  - `nowModeShowsPositionBasisPerTrain`: [지금] 응답의 열차 하나가 `position` → 그 열차만 표식.
  - 스크린샷: `sheet_position_basis`(첫 열차 [위치 계산], 그다음 [시간표]).
- [ ] **Step 2: 실패 확인** — `./gradlew.bat :app:recordRoborazziDebug` → 새 테스트 FAIL.
- [ ] **Step 3: 구현** — 규칙대로.
- [ ] **Step 4: 통과 확인** — 전체 테스트 PASS, 스크린샷 확인.
- [ ] **Step 5: 커밋** — `feat(app): 열차마다 근거 표시, 고른 시각 60분 안은 위치 계산 도착`

### Task 8: 반영(VM → 평가 → 켜기 → 배포)

사용자 확인이 필요한 단계마다 멈춘다.

- [ ] **Step 1: develop에 서버·ml 커밋 올리고 push** (사용자 확인). 수동 검토 기록 파일은 빼고 cherry-pick(지금까지 한 방식).
- [ ] **Step 2: VM `git pull`** (사용자 확인) → 다음 03:30 배포 뒤 서버 `MeasuredData.statn_of`에 1~9호선이 있는지 확인(`hop_seconds` 1~9호선 표본 조회).
- [ ] **Step 3: VM에서 평가 실행** (사용자 확인, 읽기만) — `.venv/bin/python -m ml.position_eval ... --out ml_data/position_eval.md`, 결과를 내려받아 `docs/ml/2026-11-2x-position-eval.md`로 커밋.
- [ ] **Step 4: 켜기 목록 채우기** — 평가 표의 제안대로 `Settings.position_now_lines`·`position_at_lines` 기본값을 정하고 테스트(`test_default_enabled_lines_match_eval`: 기본값이 평가 문서의 제안 줄과 같다) 후 커밋, develop push(사용자 확인), Render 수동 배포 요청.
- [ ] **Step 5: 실서버 확인** — [지금] 켜진 코레일 노선 역 하나(예: 안양)에서 `basis "position"` 열차가 나오고, [시각 선택] 30분 뒤 조회에서 켜진 노선이 `position`으로 나오는지 확인. 에뮬레이터 화면 확인.
