# 구간별 열차 고르기 — 구현 계획 (작업 3/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 경로 상세에서 지하철 구간마다 [이전]/[다음]·급행/일반으로 열차를 바꾸면, 앱이 그 자리에서 뒤 구간 자동 맞춤·경고·총 소요를 다시 계산하고, [지금 출발 알림]이 고른 첫 열차에 맞춰 울린다.

**Architecture:** 서버는 `Ride`마다 같은 노선·방향에서 탈 역·내릴 역에 모두 서는 운행을 후보로 모아(`app/services/candidates.py`) `Step.candidates`로 보낸다. 앱은 순수 함수 `ui/routedetail/TrainPicker.kt`로 고정·자동 맞춤·경고를 계산하고, `RouteFlowViewModel`이 구간별 선택 상태와 "더 늦은 열차 불러오기"를 맡는다. 세션 `Waiting`에 `chosen`(고른 첫 열차)을 더해 실시간 번호가 맞으면 그 시각, 아니면 고른 시각으로 알리고, 놓치면 알린 뒤 끝낸다.

**Tech Stack:** Python 3.12+, FastAPI, pytest; Kotlin/Compose, kotlinx.serialization, JUnit/Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-18-train-picker-design.md`

## Global Constraints

- 후보 범위: 고른 열차 출발 기준 **30분 전 ~ 2시간 뒤**(`BEFORE_SEC = 1800`, `AFTER_SEC = 7200`), 출발 순, **최대 40대**(`MAX_CANDIDATES = 40`). 고른 열차는 반드시 들어간다.
- 후보는 내릴 역에 서는 운행만(탈 역 위치 < 내릴 역 위치인 같은 노선·방향 패턴). 운행일 경계는 지금 검색과 같은 `service_days` 규칙.
- 걷는 시간 0: 뒤 구간 열차는 `departsAt ≥ 앞 구간 arrivesAt`(같은 초 허용).
- 경고 문구(그대로): `앞 열차가 HH:MM에 도착해요. 이 열차는 HH:MM에 떠나요`
- 후보가 모자랄 때 문구: `이 시각 뒤 열차 정보가 없어요`, 버튼 `더 늦은 열차 불러오기`, 고정 표시 `📌 고정됨`, 버튼 `고정 풀기`, `◀ 이전`, `다음 ▶`.
- 놓침 알림: 제목 `고른 열차를 놓쳤어요`, 본문 `경로에서 다른 열차를 골라 다시 시작해 주세요`. 놓친 뒤 다음 열차로 넘어가지 않고 세션을 끝낸다.
- 루틴에는 고른 열차를 저장하지 않는다. 걷는 시간·앞 구간 앞당기기 제안 없음.
- 서버 테스트 `server/`에서 `.venv/Scripts/python -m pytest -q`, `ruff check .`; 앱 `android/`에서 `./gradlew.bat testDebugUnitTest lintDebug`. 커밋 전 출력에 ` failed`가 없는지 확인. 커밋은 `develop`, 끝줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 푸시는 사용자에게 묻는다. 학습 노트는 `docs/learning/106-…`부터, 작업마다 하나.

## Review Focus

1. **실시간 열차 번호 표기 차이:** 실시간 `btrainNo`가 운행표 번호와 앞자리 0 등으로 다를 수 있다("05301" vs "5301"). 맞추는 비교는 앞의 0을 뗀 문자열끼리. → Task 6 `chosenPlan_matches_train_number_ignoring_leading_zeros`.
2. **경로가 이어 타기로 합친 구간(2호선 성수 번호 바뀜):** 탈 역·내릴 역이 서로 다른 운행이라 후보가 비면 고른 열차 하나만 후보로 둔다(이전/다음 꺼짐, 오류 아님). → Task 1 `test_merged_continuation_keeps_only_chosen`.
3. **자정 근처:** 23:50 고른 열차의 후보에 00:20(운행일 초 ≥ 86400) 열차가 들어가고, 00:10 고른 열차의 30분 전 후보에 전날 운행일 23:45 열차가 들어간다. → Task 1 `test_window_crosses_midnight`.
4. **저장 크기:** 세션·루틴에 저장하는 경로(`withoutPaths()`)에 후보 목록이 남으면 DataStore가 커진다. 저장본에서는 후보를 비운다. → Task 2 `withoutPaths_drops_candidates`.
5. **고른 열차로 시작했는데 실시간에 그 번호가 끝내 안 보임(실측 노선, 번호 없는 어림 운행):** 고른 출발 시각으로 알리고, 그 시각 + 60초가 지나면 놓침으로 끝낸다. → Task 6 `chosen_without_realtime_uses_scheduled_time`, `chosen_missed_ends_session`.

---

### Task 1: 서버 — 구간별 후보 열차

**Files:**
- Create: `server/app/services/candidates.py`
- Modify: `server/app/models/schemas.py` (`Candidate` 모델, `Step.candidates`)
- Modify: `server/app/services/route_service.py` (`_step`이 후보를 채움)
- Modify: `server/app/main.py` (`GZipMiddleware(minimum_size=1000)`)
- Test: `server/tests/unit/test_candidates.py`, `server/tests/integration/test_routes.py`

**Interfaces:**
- Consumes: `TripsData.day(week_tag)`, `DayTrips.stop_index/patterns_at/station_id`, `Pattern.dep/arr/trip_meta/n_trips/line/direction/stops`, `service_days(at)`, `week_tag(day)`, `Ride`.
- Produces: `candidates(trips: TripsData, ride: Ride, *, before_sec: int = BEFORE_SEC, after_sec: int = AFTER_SEC, limit: int = MAX_CANDIDATES) -> list[Departure]` — 기존 `raptor.Departure`에 `arrives_at: datetime | None = None`, `source: str = "official"`, `estimated: bool = False`를 더해 재사용. 응답 모델 `Candidate(train_no, departs_at, arrives_at, is_express, destination, source, estimated)`, `Step.candidates: list[Candidate] | None = None`. JSON은 camelCase(`trainNo`, `departsAt`, `arrivesAt`, `isExpress`, `destination`, `source`, `estimated`).

- [ ] **Step 1: 실패하는 단위 테스트** — `test_raptor.py`와 같은 손 운행표 도우미(`trip`, `pattern`, `data`, `at`)를 쓴다.

```python
def test_includes_chosen_and_excludes_express_skipping_alight():
    # 9호선 A-B-C 일반 08:00/08:10/08:20, 급행(A-C만) 08:05. 구간 A→B.
    ...
    assert [hhmm(c.departs_at) for c in found] == ["11-18 08:00", "11-18 08:10", "11-18 08:20"]
    assert ride.departs_at in [c.departs_at for c in found]
    assert hhmm(found[1].arrives_at) == "11-18 08:15"   # 내릴 역 B 도착

def test_window_is_30_min_before_to_2h_after():
    # 07:20, 07:35, 08:00(고른 열차), 10:00, 10:01 → 07:35, 08:00, 10:00
    ...

def test_at_most_40_and_chosen_kept():
    # 2분 간격 100대, 고른 열차 08:00 → 길이 40, 08:00 포함, 08:00 앞은 최대 10대(가까운 것)

def test_window_crosses_midnight():
    # 고른 23:50 → 다음 날 00:20(운행일 초 86400+1200) 후보 포함
    # 고른 00:10(전날 운행일) → 전날 운행일 23:45 후보 포함

def test_merged_continuation_keeps_only_chosen():
    # 탈 역·내릴 역이 같은 패턴에 없으면 [고른 열차 하나]
```

- [ ] **Step 2: 실패 확인** — `.venv/Scripts/python -m pytest tests/unit/test_candidates.py -q` → `ModuleNotFoundError: app.services.candidates`.

- [ ] **Step 3: `candidates()` 구현** — 볼 운행일 = `service_days(ride.departs_at - before)`와 `service_days(ride.departs_at + after)`의 날짜 합집합. 날마다 `day = trips.day(week_tag(d))`, 탈 역·내릴 역 색인, `patterns_at(board)` 중 `line`·`direction`이 ride와 같고 내릴 역이 뒤 위치에 있는 패턴의 모든 운행에서 `start + dep(t, i)`가 창 안이면 넣는다. `(train_no, departs_at)`로 중복 제거, 출발 순 정렬. `limit`을 넘으면 고른 열차 앞은 가까운 10대까지, 나머지는 뒤에서 채운다. 비면 고른 열차(ride 값으로 만든 `Departure`) 하나. 상수 `BEFORE_SEC = 1800`, `AFTER_SEC = 7200`, `MAX_CANDIDATES = 40`, `EARLIER_KEEP = 10`.

- [ ] **Step 4: 통과 확인** — 같은 명령 → 5 passed.

- [ ] **Step 5: 응답 연결 테스트** — `test_routes.py`에 추가:

```python
async def test_steps_carry_candidates(client, trips_loaded):
    params = {"from": "마포", "to": "노량진", "departAt": "2026-11-03T08:00:00+09:00"}
    first, second = steps((await get(client, **params)).json()["routes"][0])
    assert [c["trainNo"] for c in first["candidates"]] == ["5301", "5303"]
    assert first["candidates"][1]["arrivesAt"][11:16] == "08:19"
    assert [c["trainNo"] for c in second["candidates"]] == ["9101", "9103"]

async def test_large_route_response_is_gzipped(client, trips_loaded):
    r = await client.get(URL, params={"from": "마포", "to": "노량진"}, headers={"Accept-Encoding": "gzip"})
    assert r.headers.get("content-encoding") == "gzip"
```

(gzip 테스트가 1000바이트 미만이라 실패하면 `minimum_size`를 응답 크기 아래로 두지 말고, 테스트 운행표 응답 크기를 확인해 `minimum_size=500`으로 맞춘다.)

- [ ] **Step 6: 구현** — `RouteService.search`가 `trips`를 `_route`/`_step`에 넘기고 `_step`이 `candidates=[Candidate(...) for d in candidates(trips, ride)]`. 후보 계산도 `asyncio.to_thread` 안에서 하도록, 스레드에서 도는 함수가 `(journey, split, candidates_per_ride)`를 돌려준다. `main.py`에 GZip 미들웨어.

- [ ] **Step 7: 전체 확인** — `pytest -q`(` failed` 없음), `ruff check .`.

- [ ] **Step 8: 학습 노트 `docs/learning/106-후보-열차-모으기.md`, 커밋** `feat(server): 구간마다 후보 열차(30분 전~2시간 뒤, 최대 40대)와 gzip 응답`.

---

### Task 2: 앱 — 후보 DTO와 저장본 정리

**Files:**
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/data/remote/Dtos.kt`
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/session/SessionMath.kt` (`withoutPaths`)
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/data/remote/` (기존 DTO 디코딩 테스트 파일에 추가), `session/SessionMathTest.kt`

**Interfaces:**
- Produces: `@Serializable data class CandidateDto(val trainNo: String? = null, val departsAt: String, val arrivesAt: String? = null, val isExpress: Boolean = false, val destination: String? = null, val source: String? = null, val estimated: Boolean = false)`; `StepDto.candidates: List<CandidateDto>? = null`; `RouteDto.withoutPaths()`가 모든 단계의 `candidates`를 null로.

- [ ] **Step 1: 실패하는 테스트** — `candidates_decode`(camelCase JSON 한 단계 → 두 후보, `arrivesAt` 읽힘), `withoutPaths_drops_candidates`(후보 있는 경로 → 모든 단계 `candidates == null`).
- [ ] **Step 2: 실패 확인** — `./gradlew.bat testDebugUnitTest --tests "*SessionMathTest*"` → 컴파일 오류.
- [ ] **Step 3: 구현** (위 Interfaces 그대로).
- [ ] **Step 4: 통과 확인**.
- [ ] **Step 5: 커밋** `feat(app): 경로 단계의 후보 열차 DTO, 저장본에서는 뺀다` (학습 노트는 Task 3과 함께).

---

### Task 3: 앱 — 고르기 계산(순수 함수)

**Files:**
- Create: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/routedetail/TrainPicker.kt`
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/routedetail/TrainPickerTest.kt`

**Interfaces:**
- Consumes: `RouteDto`, `StepDto`, `CandidateDto` (Task 2).
- Produces:
  - `enum class ExpressFilter { ALL, EXPRESS, LOCAL }`
  - `data class SegmentChoice(val pinned: CandidateDto? = null, val filter: ExpressFilter = ExpressFilter.ALL)`
  - `data class PickedSegment(val step: StepDto, val train: CandidateDto?, val pinned: Boolean, val warning: String?, val canPrev: Boolean, val canNext: Boolean, val needsMore: Boolean, val hasExpress: Boolean, val hasLocal: Boolean)`
  - `fun subwaySteps(route: RouteDto): List<StepDto>` — 모든 다리의 SUBWAY 단계 순서대로.
  - `fun pickTrains(route: RouteDto, choices: List<SegmentChoice>): List<PickedSegment>` — `choices`가 짧으면 기본값으로 채움.
  - `fun move(route: RouteDto, choices: List<SegmentChoice>, segment: Int, delta: Int): List<SegmentChoice>` — 지금 보이는 열차에서 필터 적용 후보 기준 ±1로 옮기고 고정. 끝이면 그대로.
  - `fun setFilter(choices, segment, filter): List<SegmentChoice>`, `fun unpin(choices, segment): List<SegmentChoice>`
  - `fun RouteDto.withPicks(picks: List<PickedSegment>): RouteDto` — 각 SUBWAY 단계의 `departsAt/arrivesAt/trainNo/isExpress/destination/source/estimated`를 고른 열차로, `departAt`=첫 구간 출발, `arriveAt`=마지막 구간 도착, `totalSec`=그 차(초). 고른 열차가 null인 구간은 원래 값 유지.
- 시각 비교는 ISO 문자열을 `OffsetDateTime.parse(...).toInstant()`로. 후보 목록이 비면(옛 서버) 단계의 원래 열차 하나를 후보로 본다.

규칙(설계 §5.2):
- 고정 구간 = 그 열차. 고정 안 한 구간 = 필터 맞는 후보 중 `departsAt ≥ 기준`인 첫 열차. 기준: 첫 구간은 그 단계의 원래 `departsAt`, 그 뒤는 앞 구간 `arrivesAt`(없으면 `departsAt`).
- 고정 구간의 출발이 앞 구간 도착보다 이르면 `warning = "앞 열차가 HH:MM에 도착해요. 이 열차는 HH:MM에 떠나요"`.
- 고정 안 한 구간에 맞는 열차가 없으면 `train = null`, `needsMore = true`. [다음]이 필터 후보 끝이면 `canNext = false`, `needsMore = true`. 첫 후보면 `canPrev = false`.
- `hasExpress`/`hasLocal`: 그 구간 후보에 급행/일반이 있는지(둘 다 있을 때만 화면에 필터를 보인다).

- [ ] **Step 1: 실패하는 테스트** (구간 2개 손 경로: 5호선 08:05/08:15/08:25, 9호선 08:10/08:20/08:30, 9호선 08:20은 급행)

```kotlin
@Test fun unpinned_route_keeps_server_choice()          // 5301 08:05, 9101 08:10, warning 없음
@Test fun next_on_first_refits_second()                 // move(0,+1) → 1구간 08:15 고정, 2구간 08:20 자동
@Test fun same_second_connection_is_allowed()           // 앞 도착 08:20 = 뒤 출발 08:20 → 그 열차
@Test fun pinned_conflict_shows_warning()               // 2구간 08:10 고정 뒤 1구간 08:15 고정 → 2구간 경고 "앞 열차가 08:19에 도착해요. 이 열차는 08:10에 떠나요"
@Test fun express_filter_skips_locals()                 // setFilter(1, EXPRESS) → 2구간 08:20 급행
@Test fun last_candidate_disables_next_and_needs_more() // 1구간 08:25 고정 → canNext false, needsMore true; 2구간 맞는 열차 없음 → train null, needsMore
@Test fun first_candidate_disables_prev()
@Test fun unpin_returns_to_auto_fit()
@Test fun withPicks_updates_times_and_total()           // departAt 08:15, arriveAt, totalSec 재계산
@Test fun empty_candidates_fall_back_to_step_train()
```

- [ ] **Step 2: 실패 확인** — `./gradlew.bat testDebugUnitTest --tests "*TrainPickerTest*"` → 컴파일 오류.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인**.
- [ ] **Step 5: 학습 노트 `107-열차-고르기-계산.md`, 커밋** `feat(app): 구간별 열차 고르기 계산(고정·자동 맞춤·경고)`.

---

### Task 4: 앱 — ViewModel 상태와 더 늦은 열차 불러오기

**Files:**
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/routeedit/RouteFlowViewModel.kt`
- Test: `android/app/src/test/java/io/github/klanary/jigeumchulbal/ui/routeedit/RouteFlowViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3 전부, `RouteRepository.routes(from, to, via, departAt: Instant?)`.
- Produces:
  - `RouteFlowState.choices: List<SegmentChoice> = emptyList()`, `RouteFlowState.loadingMore: Boolean = false`
  - 파생: `val picks: List<PickedSegment>` (selectedRoute 없으면 빈 목록), `val pickedRoute: RouteDto?` = `selectedRoute?.withPicks(picks)`
  - `fun moveTrain(segment: Int, delta: Int)`, `fun setExpressFilter(segment: Int, filter: ExpressFilter)`, `fun unpin(segment: Int)`, `fun loadLater(segment: Int)`
  - `select(routeId)`와 `search()`는 `choices`를 비운다.

`loadLater(segment)`: `departAt` = 그 구간 마지막 후보 출발 + 1분으로 `routes.routes(...)`를 부른다(검색 결과·`departAt` 상태는 건드리지 않음). 응답에서 지하철 구간 열(`lineName`, `boardStationId`, `alightStationId`)이 선택된 경로와 같은 경로를 찾아, 구간마다 후보를 합친다(`trainNo`+`departsAt`로 중복 제거, 출발 순). 합친 경로를 `results` 안 같은 id 경로에 바꿔 넣는다. 같은 경로가 없거나 실패하면 그대로 두고 `loadingMore = false`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun moveTrain_changes_picked_route_and_total()  // select 후 moveTrain(0,+1) → pickedRoute.departAt 08:15, totalSec 변경
@Test fun select_resets_choices()
@Test fun loadLater_merges_candidates_of_matching_route() // 가짜 저장소 두 번째 응답에 08:35 후보 → 1구간 후보 4개, departAt 인자 = 08:26
@Test fun loadLater_without_matching_route_keeps_state()
```

- [ ] **Step 2: 실패 확인** — `--tests "*RouteFlowViewModelTest*"`.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인**.
- [ ] **Step 5: 커밋** `feat(app): 경로 상세에서 고른 열차 상태와 더 늦은 열차 불러오기` (학습 노트는 Task 5와 함께).

---

### Task 5: 앱 — 경로 상세 화면

**Files:**
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/routedetail/RouteDetailScreen.kt`
- Modify: `android/app/src/main/java/io/github/klanary/jigeumchulbal/ui/common/Copy.kt` (Global Constraints의 문구)
- Test: 화면 동작은 Task 4 ViewModel 테스트로 덮는다. 문구 조립만 `TrainLineTest.kt`에 추가.

**Interfaces:**
- Consumes: `state.pickedRoute`, `state.picks`, Task 4의 네 함수.
- Produces: `fun pickLine(train: CandidateDto): String` (StepText.kt) — `"08:05 출발 → 08:09 도착 · 5301"`(+ `" · 급행"`, 어림이면 `" (어림)"`), 기존 `trainLine` 규칙과 같게.

- [ ] **Step 1: 실패하는 테스트** — `pickLine_formats_candidate`(일반·급행·어림 세 경우).
- [ ] **Step 2: 구현** — 제목 `formatDuration(pickedRoute.totalSec)`. `stepItems`가 SUBWAY 단계마다 `picks[k]`로 한 줄: `[◀ 이전]`(enabled=canPrev) `pickLine` `[다음 ▶]`(canNext면 moveTrain(k,+1), 아니고 needsMore면 loadLater(k)), `hasExpress && hasLocal`일 때만 전체/급행/일반 칩. 고정이면 `📌 고정됨 [고정 풀기]`, 경고면 오류색 `⚠ 경고문`, `train == null`이면 `이 시각 뒤 열차 정보가 없어요 [더 늦은 열차 불러오기]`(`loadingMore` 동안 끔). `SessionButtons`와 루틴 만들기는 `pickedRoute`를 쓴다(루틴 저장본은 `withoutPaths`로 후보가 빠진다).
- [ ] **Step 3: 확인** — `./gradlew.bat testDebugUnitTest lintDebug`.
- [ ] **Step 4: 학습 노트 `108-열차-고르기-화면.md`, 커밋** `feat(app): 경로 상세에서 구간마다 이전/다음·급행 고르기`.

---

### Task 6: 앱 — 알림 세션이 고른 열차를 쓴다

**Files:**
- Modify: `session/SessionModel.kt` (`ChosenTrain`), `session/CommuteSession.kt` (`Waiting.chosen`, `StartWaiting.chosen`, `EndReason.MISSED_CHOSEN`, `AlertContent.ChosenMissed`, `onArrivals`), `session/SessionMath.kt` (`chosenPlan`), `session/AlertText.kt`, `session/WaitTargets.kt` (`waitStartFromRoute`가 `chosen`도 채움), `ui/routedetail/RouteDetailScreen.kt`(StartWaiting에 넘김), `EndReason`을 `when`으로 쓰는 곳(컴파일러가 알려 줌).
- Test: `session/SessionMathTest.kt`, `session/CommuteSessionTest.kt`, `session/AlertTextTest.kt`, `session/WaitTargetsTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class ChosenTrain(val trainNo: String?, val departsAt: Long)` (epoch ms)
  - `WaitStart.chosen: ChosenTrain? = null`; `waitStartFromRoute(route)`는 첫 SUBWAY 단계의 `trainNo`·`departsAt`이 있으면 채운다(경로 → 고른 경로 `pickedRoute`를 넘기므로 고른 열차가 된다).
  - `SessionEvent.StartWaiting.chosen: ChosenTrain? = null`, `SessionState.Waiting.chosen: ChosenTrain? = null`(옛 저장분 null).
  - `fun chosenPlan(trains: List<TrainDto>, receivedAt: Long, chosen: ChosenTrain, travelSec: Int, marginSec: Int): TrainPlan` — 실시간에서 번호(앞의 0 뗀 것)가 같고 `remainingSec`가 있는 열차면 `trainArrivalAt = receivedAt + sec*1000`, 아니면 `chosen.departsAt`. `alertAt = trainArrivalAt − (travelSec + marginSec)*1000`, `trainNo = chosen.trainNo`.
  - `EndReason.MISSED_CHOSEN`, `AlertContent.ChosenMissed` → `"고른 열차를 놓쳤어요" to "경로에서 다른 열차를 골라 다시 시작해 주세요"`.
- `onArrivals`에서 `current.chosen != null`이고 `DEPARTURE_TIMING`이면: `plan = chosenPlan(...)`. `now > plan.trainArrivalAt + MISS_GRACE_MS`면 `Alert(ChosenMissed)` 뒤 `end(MISSED_CHOSEN, stopService = true)`. 아니면 잠금 중이어도 예약은 그 plan으로 유지(Replanned 알림 없음). `chosen == null`이면 지금과 같다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// SessionMathTest
@Test fun chosenPlan_uses_realtime_when_number_matches()
@Test fun chosenPlan_matches_train_number_ignoring_leading_zeros()   // "05301" ↔ "5301"
@Test fun chosen_without_realtime_uses_scheduled_time()               // 다른 번호만 있음 → departsAt
// CommuteSessionTest
@Test fun chosen_train_schedules_pre_boarding_for_that_train()
@Test fun chosen_missed_ends_session()                                // now = departsAt + 61s → Alert(ChosenMissed), Finished(MISSED_CHOSEN)
@Test fun without_chosen_behaves_as_before()                          // 기존 Replanned 흐름 그대로
@Test fun old_saved_waiting_without_chosen_decodes()                  // chosen 칸 없는 JSON → null
// AlertTextTest
@Test fun chosen_missed_text()
// WaitTargetsTest
@Test fun waitStart_carries_first_subway_train()
```

- [ ] **Step 2: 실패 확인** — `--tests "*Session*" --tests "*WaitTargets*" --tests "*AlertText*"`.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 전체 확인** — `./gradlew.bat testDebugUnitTest lintDebug`.
- [ ] **Step 5: 학습 노트 `109-고른-열차로-알림.md`, 커밋** `feat(app): 출발 알림이 고른 첫 열차에 맞추고 놓치면 알린 뒤 끝낸다`.

---

### Task 7: 마무리 확인

- [ ] 서버 `pytest -q`·`ruff check .`, 앱 `testDebugUnitTest lintDebug` 모두 통과(` failed` 없음).
- [ ] README의 경로 상세 설명에 열차 고르기 한 단락, `docs/superpowers/specs/2026-11-18-train-picker-design.md` §8의 구현자 결정(gzip 적용, 같은 경로 비교 = 지하철 구간 노선·탈 역·내릴 역 열, 합친 이어 타기 구간은 후보 1대)을 적는다.
- [ ] 메모리 `route-redesign-plan.md`에 작업 3 완료 표시.
- [ ] 커밋 `docs: 열차 고르기 정리`. 푸시·Render 배포는 사용자에게 묻는다.
