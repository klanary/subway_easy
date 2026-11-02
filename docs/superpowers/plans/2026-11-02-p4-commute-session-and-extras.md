# P4. 통학 세션(알림)과 확장 기능 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스펙 8장의 통학 세션(⑤ 하차 알림, ⑦ 승차 전 알림)과 ⑧ 경유지, ⑨ 자주 가는 경로, ⑩ 실시간 결합을 완성한다.

**Architecture:** 세션 규칙은 안드로이드에 의존하지 않는 순수 Kotlin(`CommuteSession` 상태 머신과 계산 함수)에 둔다. 상태 머신은 이벤트를 받아 새 상태와 "효과"(알람 예약·알림·서비스 시작과 중지) 목록을 돌려주고, `SessionController`가 그 효과를 안드로이드 구성 요소(`AlarmScheduler`, `SessionNotifier`, `WaitingService`)로 실행한다. 상태는 바뀔 때마다 DataStore에 JSON으로 저장해 앱 재시작·재부팅 때 복원한다. 서버 확장(경유지 합성, 실시간 결합)은 P2의 `RouteService`를 넓힌다.

**Tech Stack:** Kotlin, kotlinx.serialization(봉인 클래스 직렬화), AlarmManager(`setAlarmClock`/`setAndAllowWhileIdle`), NotificationCompat(채널, 크로노미터), 포그라운드 서비스(`dataSync`), BroadcastReceiver, DataStore, Robolectric / Python, FastAPI(서버 확장)

**Spec:** `docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md` (8장 전체, 3.1 ⑤⑦⑧⑨⑩, 3.2, 4.2 통학 세션·홈 배너, 6.6 경유지 합성·실시간 결합, 7.2 자주 가는 경로)

**기간:** 11/16 ~ 11/22 · **선행:** P2, P3 · **순서(스펙 11절):** 하차 알림(Task 1~6) → 승차 전 알림(7~9) → 경유지(10~11) → 자주 가는 경로(12) → 실시간 결합(13) · **점검일 11/20:** 남은 기능은 3.4절 최소 버전(⑤ 최종 하차 지점만, ⑦ 역 상세 N분 전만, ⑧ 구간별 최적 1개씩, ⑨ 최근 사용순, ⑩ 경로 상세에서만)으로 마무리하고 README "알려진 한계"에 적는다.

**브랜치:** `feat/session-core`(Task 1~5) → `feat/session-alight`(6) → `feat/session-waiting`(7~9) → `feat/via`(10~11) → `feat/favorite-routes`(12) → `feat/realtime-join`(13) → (선택) `feat/subway-only`(14)

**경로 표기:** P3와 같다(`<pkg>/`, `<test>/`). 서버 경로는 `server/`부터 쓴다.

## Global Constraints

- 설정 기본값(8.6): 출발 여유 60초, 하차·환승 알림 120초 전, 역 상세 승차 전 알림 180초 전, 최대 대기 30분. 모두 `AppSettings`에서 읽는다.
- 대기 단계는 30초마다 조회하고, 3회 연속 실패하면 "실시간 정보 연결 끊김"을 표시한다. 출발 알림이 울린 뒤에는 그 열차의 도착 예정 시각 + 60초까지 예약을 바꾸지 않는다. 마지막 지점 알림 5분 뒤 세션을 끝낸다. (8.2, 8.3)
- 세션은 한 번에 하나다. 진행 중에 새로 시작하면 기존 세션을 끝낼지 묻는다. (8.1)
- `CommuteSession`과 계산 함수는 안드로이드 API를 쓰지 않는 순수 Kotlin이고 현재 시각을 주입받는다. (7.1)
- 알림 채널은 `session_ongoing`(낮음, 소리 없음)과 `session_alerts`(높음, 진동, 잠금 화면 표시). 알림을 누르면 통학 세션 화면이 열린다. (8.5)
- 매니페스트 권한: `INTERNET`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`. (8.6)
- 정확한 알람 권한이 있으면 `setAlarmClock`, 없으면 `setAndAllowWhileIdle`과 "알림이 늦을 수 있어요" 표시. `AlarmScheduler`는 인터페이스다. (8.4)
- 포그라운드 서비스(`dataSync`)는 대기 단계에서만 쓴다. 탑승 단계는 알람과 시스템 크로노미터 알림만 쓴다. (8.2, 8.3)
- 서버 작업은 P2의 Global Constraints를 그대로 따른다. 앱 작업은 P3의 Global Constraints를 그대로 따른다.
- 작업마다 커밋에 학습 노트 초안 `docs/learning/NN-주제.md`(12절 형식)를 넣는다.

## Review Focus

1. 한 정거장처럼 소요 시간이 알림 시점(120초)보다 짧은 구간 → 알림 시각이 과거가 되어도 건너뛰지 않고 탑승 즉시 알린다. (Task 1 `shortRideAlertsImmediately`)
2. 탈 수 있는 열차가 없음(막차 이후, 모든 열차가 이동 시간보다 가까움, 빈 목록, 남은 시간 정보 없음) → 알람을 예약하지 않고 기존 예약을 취소하며, 진행 중 알림에 "탈 수 있는 열차 정보가 없어요". (Task 1 `noCatchableTrain`, Task 2 `noCatchableTrainCancelsAlarm`, Task 5 `waitingWithoutPlan`)
3. 자정을 넘는 시간대(00:30에 전날 23:45 사용 기록) → 같은 시간대로 센다. (Task 12 `windowWrapsAroundMidnight`)
4. 버스 하차 알림이 "OO정류장역"이 되거나, 이미 "역"으로 끝나는 이름에 "역"이 또 붙음 → 버스는 "OO 정류장", 지하철은 "역"을 한 번만. (Task 1 `stopLabels`)
5. 기기 재부팅·앱 강제 종료 뒤 → 하차 시각까지 지난 지점은 다시 울리지 않고, 알림 시각만 지난 지점은 바로 울리며, 최대 대기 시간이 지난 대기 세션은 다시 시작하지 않고, 부팅 직후에는 포그라운드 서비스를 시작하지 않는다. (Task 9의 `restore…` 테스트들)

---

### Task 1: 세션 모델과 계산 함수

**Files:**
- Create: `<pkg>/session/SessionModel.kt`, `<pkg>/session/SessionMath.kt`, `<test>/session/SessionMathTest.kt`

**Interfaces:**
- Consumes: P3 DTO(`TrainDto`, `RouteDto`, `StepDto`, `ArrivalGroupDto`)
- Produces (`SessionModel.kt`, 모두 `@Serializable`): `data class TrainPlan(val alertAt: Long, val trainArrivalAt: Long, val destination: String?)`, `enum class WaitMode { DEPARTURE_TIMING, MINUTES_BEFORE }`, `data class WaitTarget(val stationId: String, val stationName: String, val line: String, val towards: String?, val direction: String?)`, `enum class PointKind { TRANSFER, VIA, FINAL }`, `data class AlightPoint(val name: String, val isBus: Boolean, val kind: PointKind, val alightAt: Long, val alertAt: Long, val stopsToGo: Int, val nextLine: String? = null)`, `enum class StartFrom { FIRST_SUBWAY, FIRST_TRANSIT }`
- Produces (`SessionMath.kt`, 시각은 모두 epoch ms):
  - `departurePlan(trains: List<TrainDto>, receivedAt: Long, travelSec: Int, marginSec: Int): TrainPlan?` — `remainingSec`가 있는 열차를 남은 시간순으로 보고, `remainingSec ≥ travelSec + marginSec`인 첫 열차. `alertAt = receivedAt + (remainingSec − travelSec − marginSec) × 1000`, `trainArrivalAt = receivedAt + remainingSec × 1000` (8.2)
  - `minutesBeforePlan(trains: List<TrainDto>, receivedAt: Long, leadSec: Int, now: Long): TrainPlan?` — 남은 시간이 있는 첫 열차, `alertAt = max(now, receivedAt + (remainingSec − leadSec) × 1000)`
  - `alightPoints(route: RouteDto, boardedAt: Long, from: StartFrom, leadSec: Int): List<AlightPoint>` — 모든 구간의 단계를 이어 붙이고, `FIRST_SUBWAY`면 첫 지하철 단계, `FIRST_TRANSIT`면 첫 지하철·버스 단계부터 소요 시간을 차례로 더한다(사이의 도보 포함). 지하철·버스 단계마다 지점 하나: `name = stops.last()`, `alightAt` = 누적 시각, `alertAt = max(boardedAt, alightAt − leadSec × 1000)`, `stopsToGo = stops.size − 1`. 종류: 전체의 마지막 대중교통 단계 = FINAL, 구간이 2개이고 두 번째 구간에 대중교통이 있을 때 첫 구간의 마지막 대중교통 단계 = VIA, 나머지 = TRANSFER(`nextLine` = 다음 대중교통 단계의 `lineName ?: routeName`) (8.3)
  - `stopLabel(point: AlightPoint): String` — 버스는 `"{name} 정류장"`, 지하철은 이름이 "역"으로 끝나면 그대로, 아니면 `"{name}역"`
  - `pickGroup(groups: List<ArrivalGroupDto>, target: WaitTarget): ArrivalGroupDto?` — `line`이 같고 `target.direction`이 있으면 방향도 같은 첫 그룹(방면은 서버가 이미 걸렀다)
  - `fun RouteDto.withoutPaths(): RouteDto` — 단계의 `path`를 비운 사본(세션 저장 크기를 줄인다)

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class SessionMathTest {
    private fun t(sec: Int?, dest: String = "성수") = TrainDto(dest, sec)
    private fun step(mode: StepMode, sec: Int, line: String? = null, stops: Int = 2, route: String? = null) =
        StepDto(mode, sec, 0, lineName = line, routeName = route,
                stops = if (mode == StepMode.WALK) null else List(stops) { "역$it" })
    private fun route(vararg legs: List<StepDto>) = specRoute().copy(legs = legs.map { LegDto(it) })
    private val twoLines = route(listOf(step(StepMode.WALK, 300), step(StepMode.SUBWAY, 600, "2호선", 3),
        step(StepMode.WALK, 120), step(StepMode.SUBWAY, 900, "3호선", 5), step(StepMode.WALK, 200)))

    @Test fun departurePicksFirstCatchableTrain() =
        assertEquals(TrainPlan(alertAt = 120_000, trainArrivalAt = 600_000, destination = "성수"),
            departurePlan(listOf(t(300), t(600), t(900)), receivedAt = 0, travelSec = 420, marginSec = 60))

    @Test fun noCatchableTrain() {
        assertNull(departurePlan(listOf(t(300), t(null)), 0, 420, 60))
        assertNull(departurePlan(emptyList(), 0, 420, 60))
    }

    @Test fun minutesBeforeAlertsAtLeadOrImmediately() {
        assertEquals(TrainPlan(420_000, 600_000, "성수"), minutesBeforePlan(listOf(t(600)), 0, leadSec = 180, now = 0))
        assertEquals(5_000L, minutesBeforePlan(listOf(t(120)), 0, 180, now = 5_000)!!.alertAt)
    }

    @Test fun alightPointsFromFirstTransit() {
        val p = alightPoints(twoLines, boardedAt = 0, from = StartFrom.FIRST_TRANSIT, leadSec = 120)
        assertEquals(listOf(600_000L, 1_620_000L), p.map { it.alightAt })
        assertEquals(listOf(480_000L, 1_500_000L), p.map { it.alertAt })
        assertEquals(listOf(PointKind.TRANSFER, PointKind.FINAL), p.map { it.kind })
        assertEquals(listOf(2, 4), p.map { it.stopsToGo })
        assertEquals("3호선", p[0].nextLine)
    }

    @Test fun firstSubwaySkipsEarlierBus() {
        val r = route(listOf(step(StepMode.BUS, 300, route = "7016"), step(StepMode.WALK, 60), step(StepMode.SUBWAY, 600, "2호선")))
        assertEquals(listOf(600_000L), alightPoints(r, 0, StartFrom.FIRST_SUBWAY, 120).map { it.alightAt })
    }

    @Test fun shortRideAlertsImmediately() {
        val r = route(listOf(step(StepMode.SUBWAY, 90, "2호선")))
        assertEquals(10_000L, alightPoints(r, boardedAt = 10_000, from = StartFrom.FIRST_TRANSIT, leadSec = 120).single().alertAt)
    }

    @Test fun viaLegEndIsViaPoint() {
        val r = route(listOf(step(StepMode.SUBWAY, 600, "2호선")), listOf(step(StepMode.WALK, 60), step(StepMode.SUBWAY, 300, "6호선")))
        assertEquals(listOf(PointKind.VIA, PointKind.FINAL), alightPoints(r, 0, StartFrom.FIRST_TRANSIT, 120).map { it.kind })
    }

    @Test fun stopLabels() {
        fun pt(name: String, bus: Boolean) = AlightPoint(name, bus, PointKind.FINAL, 0, 0, 1)
        assertEquals("홍대입구역", stopLabel(pt("홍대입구", false)))
        assertEquals("서울역", stopLabel(pt("서울역", false)))
        assertEquals("신촌오거리 정류장", stopLabel(pt("신촌오거리", true)))
    }

    @Test fun pickGroupByLineAndDirection() {
        val inner = ArrivalGroupDto("2호선", "내선", "신촌", emptyList())
        val outer = ArrivalGroupDto("2호선", "외선", "합정", emptyList())
        assertEquals(outer, pickGroup(listOf(inner, outer), WaitTarget("홍대입구", "홍대입구", "2호선", "합정", "외선")))
        assertEquals(inner, pickGroup(listOf(inner, outer), WaitTarget("홍대입구", "홍대입구", "2호선", "신촌", null)))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL (컴파일 오류)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 통학 세션 계산 함수(출발 시각, 하차 지점)"`

---

### Task 2: CommuteSession 상태 머신

**Files:**
- Create: `<pkg>/session/CommuteSession.kt`, `<test>/session/CommuteSessionTest.kt`

**Interfaces:**
- Consumes: Task 1 전부, P3 `AppSettings`
- Produces (`@Serializable`, 봉인 인터페이스는 `@SerialName`으로 구분):
  - `sealed interface SessionState`: `Idle`(`data object`), `Waiting(val target: WaitTarget, val mode: WaitMode, val route: RouteDto?, val travelSec: Int, val marginSec: Int, val leadSec: Int, val alightLeadSec: Int, val startedAt: Long, val deadlineAt: Long, val plan: TrainPlan? = null, val lockedUntil: Long? = null, val failures: Int = 0, val nextTrainSec: Int? = null, val updatedAt: Long? = null)`, `Riding(val points: List<AlightPoint>, val nextIndex: Int = 0, val endAt: Long? = null)`
  - `sealed interface AlarmId { data object PreBoarding; data class Alight(val index: Int); data object SessionEnd }`
  - `sealed interface SessionEvent`: `StartWaiting(target, mode, route: RouteDto?, travelSec: Int, settings: AppSettings)`, `ArrivalsUpdated(trains: List<TrainDto>, receivedAt: Long)`, `ArrivalsFailed`, `Boarded`, `StartRiding(route: RouteDto, settings: AppSettings)`, `AlarmFired(id: AlarmId)`, `Cancel`, `Timeout` (8.1 이벤트 + 조회 실패)
  - `sealed interface AlertContent`: `LeaveNow(minutes: Int, destination: String?)`, `TrainSoon(minutes: Int, destination: String?)`, `Replanned`, `AlightSoon(point: AlightPoint)`, `WaitingInterrupted`
  - `enum class EndReason { CANCELLED, TIMEOUT, ARRIVED, BOARDED_AT_STATION, INTERRUPTED }`
  - `sealed interface SessionEffect`: `Schedule(id: AlarmId, at: Long)`, `CancelAlarm(id: AlarmId)`, `CancelAllAlarms`, `Alert(content: AlertContent)`, `UpdateOngoing`, `ClearOngoing`, `StartWaitingService`, `StopWaitingService`, `Finished(reason: EndReason)`
  - `class CommuteSession(private val clock: () -> Long, initial: SessionState = SessionState.Idle) { var state: SessionState; private set; fun handle(event: SessionEvent): List<SessionEffect> }`

전이 표(`now = clock()`, 표에 없는 조합은 상태 그대로·효과 없음):

| 상태 | 이벤트 | 새 상태 | 효과(순서대로) |
|---|---|---|---|
| Idle | StartWaiting | `Waiting(startedAt = now, deadlineAt = now + maxWaitMin × 60,000, marginSec·leadSec·alightLeadSec은 settings에서(leadSec = stationLeadSec))` | StartWaitingService, UpdateOngoing |
| Idle | StartRiding | `Riding(alightPoints(route, now, FIRST_TRANSIT, alightLeadSec))` | 지점마다 Schedule(Alight(i), alertAt), UpdateOngoing |
| Waiting(잠금 없음) | ArrivalsUpdated | plan = 모드에 맞는 계산, failures = 0, nextTrainSec·updatedAt 갱신 | plan 있으면 Schedule(PreBoarding, alertAt), 없으면 CancelAlarm(PreBoarding); UpdateOngoing |
| Waiting(now < lockedUntil) | ArrivalsUpdated | nextTrainSec·updatedAt만 갱신 | UpdateOngoing |
| Waiting(now ≥ lockedUntil) | ArrivalsUpdated | lockedUntil = null, plan 다시 계산 | plan 있으면 Alert(Replanned), Schedule; 없으면 CancelAlarm(PreBoarding); UpdateOngoing |
| Waiting | ArrivalsFailed | failures + 1 | UpdateOngoing |
| Waiting(plan 있음) | AlarmFired(PreBoarding) | lockedUntil = plan.trainArrivalAt + 60,000 | Alert(LeaveNow 또는 TrainSoon, minutes = ⌈(trainArrivalAt − now) / 60,000⌉), UpdateOngoing |
| Waiting(route 있음) | Boarded | `Riding(alightPoints(route, now, FIRST_SUBWAY, alightLeadSec))` | StopWaitingService, CancelAlarm(PreBoarding), 지점마다 Schedule, UpdateOngoing |
| Waiting(route 없음) | Boarded | Idle | StopWaitingService, CancelAllAlarms, ClearOngoing, Finished(BOARDED_AT_STATION) |
| Waiting | Timeout | Idle | StopWaitingService, CancelAllAlarms, ClearOngoing, Finished(TIMEOUT) |
| Waiting / Riding | Cancel | Idle | (Waiting이면 StopWaitingService), CancelAllAlarms, ClearOngoing, Finished(CANCELLED) |
| Riding | AlarmFired(Alight(i)) | nextIndex = i + 1, 마지막 지점이면 endAt = now + 300,000 | Alert(AlightSoon(points[i])), 마지막이면 Schedule(SessionEnd, endAt), UpdateOngoing |
| Riding | AlarmFired(SessionEnd) | Idle | ClearOngoing, Finished(ARRIVED) |

모드에 맞는 계산: DEPARTURE_TIMING → `departurePlan(trains, receivedAt, travelSec, marginSec)`, MINUTES_BEFORE → `minutesBeforePlan(trains, receivedAt, leadSec, now)`. 알림 종류: DEPARTURE_TIMING → LeaveNow, MINUTES_BEFORE → TrainSoon.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class CommuteSessionTest {
    private var now = 0L
    private val settings = AppSettings()
    private fun t(sec: Int) = TrainDto("성수", sec)
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private fun waiting(route: RouteDto? = specRoute()) = CommuteSession({ now }).also {
        it.handle(SessionEvent.StartWaiting(target, WaitMode.DEPARTURE_TIMING, route, travelSec = 420, settings = settings))
    }

    @Test fun startWaitingStartsServiceAndSetsDeadline() {
        val s = CommuteSession({ now })
        assertEquals(listOf(SessionEffect.StartWaitingService, SessionEffect.UpdateOngoing),
            s.handle(SessionEvent.StartWaiting(target, WaitMode.DEPARTURE_TIMING, null, 420, settings)))
        assertEquals(1_800_000L, (s.state as SessionState.Waiting).deadlineAt)
    }

    @Test fun arrivalsScheduleDepartureAlarm() =
        assertTrue(SessionEffect.Schedule(AlarmId.PreBoarding, 120_000) in
            waiting().handle(SessionEvent.ArrivalsUpdated(listOf(t(300), t(600)), receivedAt = 0)))

    @Test fun noCatchableTrainCancelsAlarm() {
        val s = waiting(); s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        assertTrue(SessionEffect.CancelAlarm(AlarmId.PreBoarding) in s.handle(SessionEvent.ArrivalsUpdated(listOf(t(100)), 0)))
        assertNull((s.state as SessionState.Waiting).plan)
    }

    @Test fun departureAlarmLocksPlanUntilTrainArrivalPlus60s() {
        val s = waiting(); s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        now = 120_000
        assertEquals(SessionEffect.Alert(AlertContent.LeaveNow(8, "성수")),
            s.handle(SessionEvent.AlarmFired(AlarmId.PreBoarding)).first())
        now = 150_000
        assertEquals(listOf(SessionEffect.UpdateOngoing), s.handle(SessionEvent.ArrivalsUpdated(listOf(t(450), t(750)), now)))
    }

    @Test fun missedTrainReplansOnceAfterLock() {
        val s = waiting(); s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        now = 120_000; s.handle(SessionEvent.AlarmFired(AlarmId.PreBoarding))
        now = 660_001
        val effects = s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), now))
        assertEquals(listOf(SessionEffect.Alert(AlertContent.Replanned), SessionEffect.Schedule(AlarmId.PreBoarding, 780_001), SessionEffect.UpdateOngoing), effects)
        assertFalse(SessionEffect.Alert(AlertContent.Replanned) in s.handle(SessionEvent.ArrivalsUpdated(listOf(t(570)), now + 30_000)))
    }

    @Test fun threeFailuresAreCounted() {
        val s = waiting(); repeat(3) { s.handle(SessionEvent.ArrivalsFailed) }
        assertEquals(3, (s.state as SessionState.Waiting).failures)
    }

    @Test fun boardedFromRouteStartsRidingFromFirstSubway() {
        val s = waiting(); now = 500_000
        val effects = s.handle(SessionEvent.Boarded)
        assertEquals(SessionEffect.StopWaitingService, effects.first())
        assertEquals(500_000L + 600_000L, (s.state as SessionState.Riding).points.single().alightAt)   // 스펙 예시: 지하철 600초
    }

    @Test fun boardedFromStationEnds() {
        val s = waiting(route = null)
        assertEquals(SessionEffect.Finished(EndReason.BOARDED_AT_STATION), s.handle(SessionEvent.Boarded).last())
        assertEquals(SessionState.Idle, s.state)
    }

    @Test fun timeoutAndCancelEnd() {
        assertEquals(SessionEffect.Finished(EndReason.TIMEOUT), waiting().handle(SessionEvent.Timeout).last())
        assertEquals(SessionEffect.Finished(EndReason.CANCELLED), waiting().handle(SessionEvent.Cancel).last())
    }

    @Test fun ridingAlertsEachPointThenEndsFiveMinutesAfterLast() {
        val s = CommuteSession({ now }); s.handle(SessionEvent.StartRiding(specRoute(), settings))
        now = 480_000
        val effects = s.handle(SessionEvent.AlarmFired(AlarmId.Alight(0)))
        assertTrue(SessionEffect.Schedule(AlarmId.SessionEnd, 780_000) in effects)
        now = 780_000
        assertEquals(SessionEffect.Finished(EndReason.ARRIVED), s.handle(SessionEvent.AlarmFired(AlarmId.SessionEnd)).last())
    }

    @Test fun unrelatedEventsAreIgnored() = assertTrue(CommuteSession({ now }).handle(SessionEvent.Boarded).isEmpty())

    @Test fun stateRoundTripsThroughJson() {
        val s = waiting(); s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(s.state, json.decodeFromString<SessionState>(json.encodeToString<SessionState>(s.state)))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현** — 위 전이 표 그대로
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 통학 세션 상태 머신"`

---

### Task 3: 세션 저장과 SessionController

**Files:**
- Create: `<pkg>/session/SessionStore.kt`, `<pkg>/session/SessionPorts.kt`, `<pkg>/session/SessionController.kt`, `<test>/session/SessionControllerTest.kt`
- Modify: `<pkg>/di/AppContainer.kt`(이 작업에서는 포트를 아무 일도 하지 않는 구현으로 연결하고, Task 4·5·7이 실제 구현으로 바꾼다)

**Interfaces:**
- Consumes: Task 2 전부
- Produces (`SessionPorts.kt`): `interface AlarmScheduler { fun schedule(id: AlarmId, atMillis: Long); fun cancel(id: AlarmId); fun cancelAll(); fun canScheduleExact(): Boolean }`(8.4), `interface SessionNotifierPort { fun showOngoing(state: SessionState, now: Long); fun clearOngoing(); fun alert(content: AlertContent) }`, `interface WaitingServiceLauncher { fun start(); fun stop() }`, 그리고 아무 일도 하지 않는 `NoopAlarmScheduler`·`NoopNotifier`·`NoopLauncher`(앱 안 모드와 테스트용)
- Produces (`SessionStore.kt`): `class SessionStore(dataStore: DataStore<Preferences>, json: Json) { suspend fun load(): SessionState; suspend fun save(state: SessionState) }` — 키 `session_json`, 없거나 읽지 못하면 `Idle`
- Produces (`SessionController.kt`): `class SessionController(store: SessionStore, scheduler: AlarmScheduler, notifier: SessionNotifierPort, launcher: WaitingServiceLauncher, clock: () -> Long)`
  - `val state: StateFlow<SessionState>`, `val inAppOnly: StateFlow<Boolean>`, `val uiEvents: SharedFlow<SessionUiEvent>` (`sealed interface SessionUiEvent { data class Finished(val reason: EndReason); data class InAppAlert(val content: AlertContent) }`)
  - `enum class StartResult { STARTED, ALREADY_RUNNING }`, `suspend fun start(event: SessionEvent, inAppOnly: Boolean, replaceExisting: Boolean = false): StartResult` — 진행 중이면 `replaceExisting`이 false일 때 `ALREADY_RUNNING`, true면 `Cancel`을 먼저 처리
  - `suspend fun dispatch(event: SessionEvent)` — `Mutex` 안에서: 처음 한 번 저장된 상태를 불러옴(`ensureLoaded`) → `session.handle(event)` → 앱 안 모드가 아니면 `store.save` → 효과를 순서대로 실행(Schedule → `scheduler.schedule`, CancelAlarm → `cancel`, CancelAllAlarms → `cancelAll`, Alert → `notifier.alert`(앱 안 모드면 `uiEvents`에 `InAppAlert`), UpdateOngoing → `notifier.showOngoing(state, now)`, ClearOngoing → `clearOngoing`, Start/StopWaitingService → `launcher`, Finished → `uiEvents`). 앱 안 모드에서는 scheduler·notifier·launcher 대신 Noop을 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class SessionControllerTest {
    private val scheduler = FakeAlarmScheduler()      // scheduled: MutableMap<AlarmId, Long>, cancelAllCalls
    private val notifier = FakeNotifier()             // alerts, ongoingShown
    private val launcher = FakeLauncher()             // starts, stops
    private fun controller(store: SessionStore) = SessionController(store, scheduler, notifier, launcher) { 0L }
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private val startWaiting = SessionEvent.StartWaiting(target, WaitMode.MINUTES_BEFORE, null, 0, AppSettings())

    @Test fun startPersistsAndAppliesEffects() = runTest {
        val store = testSessionStore(); val c = controller(store)
        assertEquals(StartResult.STARTED, c.start(startWaiting, inAppOnly = false))
        assertTrue(store.load() is SessionState.Waiting)
        assertEquals(1, launcher.starts); assertTrue(notifier.ongoingShown > 0)
    }

    @Test fun secondStartNeedsConfirmationThenReplaces() = runTest {
        val c = controller(testSessionStore()); c.start(startWaiting, false)
        assertEquals(StartResult.ALREADY_RUNNING, c.start(SessionEvent.StartRiding(specRoute(), AppSettings()), false))
        assertEquals(StartResult.STARTED, c.start(SessionEvent.StartRiding(specRoute(), AppSettings()), false, replaceExisting = true))
        assertEquals(1, scheduler.cancelAllCalls); assertTrue(c.state.value is SessionState.Riding)
    }

    @Test fun inAppOnlyNeitherPersistsNorSchedules() = runTest {
        val store = testSessionStore(); val c = controller(store)
        c.start(SessionEvent.StartRiding(specRoute(), AppSettings()), inAppOnly = true)
        assertEquals(SessionState.Idle, store.load())
        assertTrue(scheduler.scheduled.isEmpty()); assertTrue(c.state.value is SessionState.Riding)
    }

    @Test fun corruptStoredJsonLoadsAsIdle() = runTest {
        assertEquals(SessionState.Idle, testSessionStore(rawJson = "{망가진").load())
    }
}
```

(`testSessionStore(rawJson)`은 임시 파일 DataStore로 만든 `SessionStore`이고, 가짜 포트들은 `<test>/fakes/Fakes.kt`에 더한다.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 세션 저장과 효과 실행 컨트롤러"`

---

### Task 4: 알람 예약

**Files:**
- Create: `<pkg>/session/AlarmCodes.kt`, `<pkg>/session/AndroidAlarmScheduler.kt`, `<pkg>/session/AlarmReceiver.kt`, `<test>/session/AlarmCodesTest.kt`, `<test>/session/AndroidAlarmSchedulerTest.kt`
- Modify: `AndroidManifest.xml`(`SCHEDULE_EXACT_ALARM`, `<receiver android:name=".session.AlarmReceiver" android:exported="false"/>`), `<pkg>/di/AppContainer.kt`, `<pkg>/session/SessionController.kt`(`suspend fun onAlarm(id: AlarmId)` = `dispatch(AlarmFired(id))`)

**Interfaces:**
- Consumes: Task 3 `AlarmScheduler`, `SessionController`
- Produces (`AlarmCodes.kt`): `const val MAX_ALIGHT_POINTS = 50`, `fun AlarmId.requestCode(): Int`(PreBoarding 1000, Alight(i) 2000 + i, SessionEnd 3000), `fun AlarmId.encode(): String`(`"PRE_BOARDING"`, `"ALIGHT:{i}"`, `"SESSION_END"`), `fun decodeAlarmId(value: String?): AlarmId?`, 상수 `ACTION_ALARM = "io.github.klanary.jigeumchulbal.ALARM"`, `EXTRA_ALARM_ID = "alarm_id"`
- Produces: `class AndroidAlarmScheduler(context: Context, alarmManager: AlarmManager) : AlarmScheduler` — `canScheduleExact()`는 API 31 미만이면 true, 아니면 `alarmManager.canScheduleExactAlarms()`. `schedule`은 `PendingIntent.getBroadcast(context, id.requestCode(), Intent(ACTION_ALARM, AlarmReceiver, EXTRA_ALARM_ID = id.encode()), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`로, 정확하면 `setAlarmClock(AlarmClockInfo(at, 세션 화면을 여는 PendingIntent), pi)`, 아니면 `setAndAllowWhileIdle(RTC_WAKEUP, at, pi)`. `cancel`은 같은 PendingIntent로 취소, `cancelAll`은 PreBoarding·SessionEnd·Alight(0 until 50)
- Produces: `class AlarmReceiver : BroadcastReceiver` — `goAsync()` 안에서 `decodeAlarmId` → `container.sessionController.onAlarm(id)` → `finish()`. 앱 프로세스가 죽어 있었으면 컨트롤러가 저장된 상태를 먼저 불러온다(Task 3 `ensureLoaded`).

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class AlarmCodesTest {
    private val all = listOf(AlarmId.PreBoarding, AlarmId.SessionEnd) + (0 until MAX_ALIGHT_POINTS).map { AlarmId.Alight(it) }
    @Test fun requestCodesAreUnique() = assertEquals(all.size, all.map { it.requestCode() }.toSet().size)
    @Test fun encodeDecodeRoundTrip() = all.forEach { assertEquals(it, decodeAlarmId(it.encode())) }
    @Test fun badValueDecodesToNull() { assertNull(decodeAlarmId("ALIGHT:x")); assertNull(decodeAlarmId(null)) }
}

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    // Robolectric ShadowAlarmManager로 정확한 알람 허용 여부를 바꾸고, 예약된 알람 목록을 읽는다.
    @Test fun exactPermissionUsesAlarmClock() { /* 허용 → schedule(PreBoarding, t) → 예약 1건이 알람 시계 방식, 시각 t */ }
    @Test fun withoutPermissionUsesAllowWhileIdle() { /* 거부 → canScheduleExact() false, 예약이 allow-while-idle 방식 */ }
    @Test fun cancelAllRemovesEverything() { /* PreBoarding, Alight(0), Alight(3), SessionEnd 예약 → cancelAll → 예약 0건 */ }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 정확한 알람과 대체 알람 예약"`

---

### Task 5: 알림 채널, 진행 중 알림, 알림 버튼

**Files:**
- Create: `<pkg>/session/NotificationChannels.kt`, `<pkg>/session/OngoingContent.kt`, `<pkg>/session/AlertText.kt`, `<pkg>/session/SessionNotifier.kt`, `<pkg>/session/SessionActionReceiver.kt`, `<test>/session/OngoingContentTest.kt`, `<test>/session/AlertTextTest.kt`
- Modify: `AndroidManifest.xml`(`POST_NOTIFICATIONS`, `SessionActionReceiver` 등록), `<pkg>/App.kt`(채널 생성), `<pkg>/di/AppContainer.kt`, `<pkg>/MainActivity.kt`(`EXTRA_OPEN_SESSION`이 있으면 세션 화면으로 — 화면은 Task 6)

**Interfaces:**
- Consumes: Task 1 `stopLabel`, Task 2 상태·`AlertContent`, Task 3 `SessionNotifierPort`, P3 `formatRemaining`
- Produces (`NotificationChannels.kt`): `session_ongoing`(IMPORTANCE_LOW, 이름 "진행 중인 통학"), `session_alerts`(IMPORTANCE_HIGH, 진동, 잠금 화면 공개, 이름 "출발·하차 알림") (8.5)
- Produces (`OngoingContent.kt`): `enum class SessionAction { BOARDED, CANCEL }`(버튼 문구 "탑승했어요", "종료"), `data class OngoingContent(val title: String, val text: String, val subText: String?, val countdownTo: Long?, val actions: List<SessionAction>)`, `fun ongoingContent(state: SessionState, now: Long, exactAlarms: Boolean): OngoingContent?`
  - Waiting: 제목 `"{stationName} {line} {towards} 방면"`(towards가 없으면 `"{stationName} {line}"`), 본문: 실패 3회 이상 → `"실시간 정보 연결 끊김"`, plan 없음 → `"탈 수 있는 열차 정보가 없어요"`, DEPARTURE_TIMING → `"다음 열차 {formatRemaining(nextTrainSec)} · {⌈(alertAt − now)/60,000⌉}분 뒤 출발"`(이미 지났으면 `"지금 출발하세요"`), MINUTES_BEFORE → `"다음 열차 {formatRemaining(nextTrainSec)} · {leadSec/60}분 전에 알려드려요"`. 버튼 [탑승했어요] [종료] (8.2 예: "홍대입구 2호선 합정 방면 · 다음 열차 4분 · 7분 뒤 출발")
  - Riding: 다음 지점(`points[nextIndex]`, 다 지났으면 마지막) 기준 제목 `"{stopLabel}까지 {stopsToGo}정거장"`, 본문 `"약 {⌈(alightAt − now)/60,000⌉}분"`, `countdownTo = alightAt`, 버튼 [종료] (4.2)
  - `subText`: 정확한 알람이 아니면 `"알림이 늦을 수 있어요"`(8.4). Idle이면 `null`.
- Produces (`AlertText.kt`): `fun alertText(content: AlertContent): Pair<String, String>`(제목, 본문)
  - LeaveNow(m, d) → `"지금 출발하세요"`, `"{m}분 뒤 {d}행 열차"`(d가 없으면 `"{m}분 뒤 열차"`) (8.2)
  - TrainSoon(m, d) → `"열차가 곧 도착해요"`, `"{m}분 뒤 {d}행 열차"`
  - Replanned → `"다음 열차 기준으로 다시 안내할게요"`, `"출발할 시각이 되면 다시 알려드려요"`
  - AlightSoon(p) → `"곧 {stopLabel(p)}이에요"`, FINAL·VIA는 `"내릴 준비를 하세요"`, TRANSFER는 `"{nextLine} 환승 준비를 하세요"` (8.3)
  - WaitingInterrupted → `"대기 알림이 중단됐어요"`, `"기기가 다시 켜져 승차 전 알림을 멈췄어요. 필요하면 다시 시작해 주세요"` (8.7)
- Produces: `class SessionNotifier(context: Context, scheduler: AlarmScheduler) : SessionNotifierPort` — 진행 중 알림(id 1, `session_ongoing`, `setOngoing(true)`, `setOnlyAlertOnce(true)`, `countdownTo`가 있으면 `setUsesChronometer(true)`·`setChronometerCountDown(true)`·`setWhen(countdownTo)`), 알림을 누르면 `EXTRA_OPEN_SESSION`을 단 `MainActivity`, 버튼은 `SessionActionReceiver`로 가는 PendingIntent(요청 코드 4001 BOARDED, 4002 CANCEL). 높은 중요도 알림은 id 100부터(최근 50개 순환), `session_alerts`, 진동. `fun foregroundNotification(state: SessionState, now: Long): Notification`(Task 7의 서비스가 같은 id 1로 씀)
- Produces: `class SessionActionReceiver : BroadcastReceiver` — BOARDED → `dispatch(Boarded)`, CANCEL → `dispatch(Cancel)` (`goAsync`)

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class OngoingContentTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private fun waiting(plan: TrainPlan?, failures: Int = 0) = SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, null,
        420, 60, 180, 120, startedAt = 0, deadlineAt = 1_800_000, plan = plan, failures = failures, nextTrainSec = 240)

    @Test fun waitingDepartureText() {
        val c = ongoingContent(waiting(TrainPlan(420_000, 900_000, "성수")), now = 0, exactAlarms = true)!!
        assertEquals("홍대입구 2호선 합정 방면" to "다음 열차 4분 · 7분 뒤 출발", c.title to c.text)
        assertEquals(listOf(SessionAction.BOARDED, SessionAction.CANCEL), c.actions)
    }
    @Test fun waitingWithoutPlan() = assertEquals("탈 수 있는 열차 정보가 없어요", ongoingContent(waiting(null), 0, true)!!.text)
    @Test fun threeFailuresShowDisconnected() =
        assertEquals("실시간 정보 연결 끊김", ongoingContent(waiting(TrainPlan(420_000, 900_000, null), failures = 3), 0, true)!!.text)
    @Test fun ridingCountdown() {
        val p = AlightPoint("홍대입구", false, PointKind.FINAL, alightAt = 300_000, alertAt = 180_000, stopsToGo = 2)
        val c = ongoingContent(SessionState.Riding(listOf(p)), now = 0, exactAlarms = false)!!
        assertEquals(listOf("홍대입구역까지 2정거장", "약 5분", "알림이 늦을 수 있어요"), listOf(c.title, c.text, c.subText))
        assertEquals(300_000L, c.countdownTo)
    }
}

class AlertTextTest {
    @Test fun leaveNow() = assertEquals("지금 출발하세요" to "8분 뒤 성수행 열차", alertText(AlertContent.LeaveNow(8, "성수")))
    @Test fun transferAlight() = assertEquals("곧 시청역이에요" to "1호선 환승 준비를 하세요",
        alertText(AlertContent.AlightSoon(AlightPoint("시청", false, PointKind.TRANSFER, 0, 0, 3, nextLine = "1호선"))))
    @Test fun busFinalAlight() = assertEquals("곧 신촌오거리 정류장이에요" to "내릴 준비를 하세요",
        alertText(AlertContent.AlightSoon(AlightPoint("신촌오거리", true, PointKind.FINAL, 0, 0, 4))))
    @Test fun replanned() = assertEquals("다음 열차 기준으로 다시 안내할게요", alertText(AlertContent.Replanned).first)
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 세션 알림 채널, 진행 중 알림, 알림 버튼"`

---

### Task 6: 하차 알림 시작 흐름과 통학 세션 화면 (⑤)

**Files:**
- Create: `<pkg>/ui/session/StartGate.kt`, `<pkg>/ui/session/SessionViewModel.kt`, `<pkg>/ui/session/SessionScreen.kt`, `<pkg>/ui/session/StartSessionFlow.kt`, `<test>/ui/session/StartGateTest.kt`, `<test>/ui/session/SessionHeadlineTest.kt`
- Modify: `<pkg>/ui/Destinations.kt`(`@Serializable object SessionDest`), `<pkg>/ui/AppNavHost.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`([출발(하차 알림)] 버튼), `<pkg>/ui/home/HomeScreen.kt`(진행 중 세션 배너 — `HomeViewModel`은 바꾸지 않고 화면이 `SessionViewModel`을 함께 쓴다), `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Consumes: Task 3 `SessionController.start`, Task 5 `alertText`, P3 `SettingsStore`(`permissionIntroShown`)
- Produces (`StartGate.kt`): `enum class StartDecision { SHOW_INTRO, START_NORMAL, START_IN_APP_ONLY }`, `fun decideStart(introShown: Boolean, notificationsGranted: Boolean): StartDecision` — 처음이면 SHOW_INTRO, 그 뒤로는 알림이 허용돼 있으면 START_NORMAL, 아니면 START_IN_APP_ONLY(다시 조르지 않는다. 설정 화면의 [설정 열기]로 바꿀 수 있다) (4.4, 8.6)
- Produces (`StartSessionFlow.kt`): `@Composable fun rememberStartSession(onStarted: () -> Unit): (SessionEvent) -> Unit` — `decideStart` → SHOW_INTRO면 설명 대화상자(제목 `"알림을 허용해 주세요"`, 본문 `"출발할 시각과 내릴 역을 알림으로 알려드려요. 정확한 시각에 울리려면 '알람 및 리마인더' 권한도 필요해요."`, [계속]) → `permissionIntroShown = true` 저장 → Android 13 이상이면 `POST_NOTIFICATIONS` 요청 → 정확한 알람이 안 되면 `"정확한 시각에 알리려면 '알람 및 리마인더'를 허용해 주세요"` 대화상자([설정 열기] = `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`(패키지 URI), [나중에]) → 결과로 다시 `decideStart`. 시작 결과가 `ALREADY_RUNNING`이면 `"진행 중인 통학 알림을 끝내고 새로 시작할까요?"`([새로 시작] → `replaceExisting = true`, [취소]). 시작되면 `onStarted()`(세션 화면으로 이동).
- Produces (`SessionViewModel.kt`): `class SessionViewModel(controller: SessionController, clock: () -> Long) : ViewModel` — `val state`, `val inAppOnly`, `fun boarded()`, `fun cancel()`, 1초 시계(`now`), 그리고 순수 함수 `fun sessionHeadline(state: SessionState, now: Long): String?` — Waiting + plan: `"지금 출발하면 {⌈(trainArrivalAt − now)/60,000⌉}분 뒤 {destination}행 열차를 탈 수 있어요"`(4.2), Waiting + plan 없음: `"탈 수 있는 열차 정보가 없어요"`, Riding: `"{stopLabel}까지 {stopsToGo}정거장, 약 {⌈(alightAt − now)/60,000⌉}분"`, Idle: `null`
- Produces (`SessionScreen`): 머리글, 대기 단계의 [탑승했어요], [종료], 탑승 단계의 지점 목록(각 지점 이름과 하차 예정 시각), 띠: 정확한 알람이 아니면 `"알림이 늦을 수 있어요"`, 앱 안 모드면 `"알림 권한이 없어 이 화면에서만 안내해요"`. `uiEvents`의 `Finished`를 받으면 뒤로 간다.
- 경로 상세 [출발(하차 알림)] → `StartRiding(route.withoutPaths(), settings)`. 홈 배너: 세션이 있으면 `"통학 알림 진행 중 · {sessionHeadline}"` → `SessionDest`.
- 최소 버전(3.4): 최종 하차 지점만 알림

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class StartGateTest {
    @Test fun firstTimeShowsIntro() = assertEquals(StartDecision.SHOW_INTRO, decideStart(introShown = false, notificationsGranted = true))
    @Test fun grantedStartsNormally() = assertEquals(StartDecision.START_NORMAL, decideStart(true, true))
    @Test fun deniedRunsInAppOnly() = assertEquals(StartDecision.START_IN_APP_ONLY, decideStart(true, false))
}

class SessionHeadlineTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    @Test fun waitingHeadline() {
        val s = SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, null, 420, 60, 180, 120, 0, 1_800_000,
            plan = TrainPlan(120_000, 600_000, "성수"))
        assertEquals("지금 출발하면 10분 뒤 성수행 열차를 탈 수 있어요", sessionHeadline(s, now = 0))
    }
    @Test fun ridingHeadline() {
        val p = AlightPoint("홍대입구", false, PointKind.FINAL, alightAt = 300_000, alertAt = 180_000, stopsToGo = 2)
        assertEquals("홍대입구역까지 2정거장, 약 5분", sessionHeadline(SessionState.Riding(listOf(p)), now = 0))
    }
    @Test fun idleHasNoHeadline() = assertNull(sessionHeadline(SessionState.Idle, 0))
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 실기기 확인** — 실제 경로에서 [출발(하차 알림)] → 진행 중 알림의 카운트다운, 각 지점 2분 전 높은 중요도 알림(잠금 화면 포함), 마지막 지점 5분 뒤 알림이 사라지는지 확인한다.
- [ ] **Step 6: Commit** — `git add android docs/learning && git commit -m "feat(android): 하차 알림 시작과 통학 세션 화면"`

---

### Task 7: 대기 서비스와 승차 전 알림 (⑦)

**Files:**
- Create: `<pkg>/session/WaitingPoller.kt`, `<pkg>/session/WaitingService.kt`, `<pkg>/session/AndroidServiceLauncher.kt`, `<pkg>/session/WaitTargets.kt`, `<test>/session/WaitingPollerTest.kt`, `<test>/session/WaitTargetsTest.kt`
- Modify: `AndroidManifest.xml`(`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `<service android:name=".session.WaitingService" android:foregroundServiceType="dataSync" android:exported="false"/>`), `<pkg>/di/AppContainer.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`([승차 전 알림]), `<pkg>/ui/station/StationScreen.kt`(그룹마다 [승차 전 알림]), `<pkg>/ui/common/Copy.kt`

**Interfaces:**
- Consumes: Task 1 `pickGroup`, Task 3 `WaitingServiceLauncher`, Task 5 `SessionNotifier.foregroundNotification`, P3 `StationRepository.arrivals`
- Produces (`WaitingPoller.kt`): `class WaitingPoller(fetch: suspend (WaitTarget) -> ApiResult<ArrivalsDto>, dispatch: suspend (SessionEvent) -> Unit, state: () -> SessionState, clock: () -> Long, intervalMs: Long = 30_000)` — `suspend fun run()`: 상태가 Waiting인 동안 반복. `clock() ≥ deadlineAt`이면 `Timeout`을 보내고 끝. `fetch(target)`가 성공하면 `pickGroup(...)?.trains ?: emptyList()`로 `ArrivalsUpdated(trains, clock())`, 실패하면 `ArrivalsFailed`. 그다음 `delay(intervalMs)`.
- Produces: `class WaitingService : Service` — `onStartCommand`에서 `ServiceCompat.startForeground(this, 1, notifier.foregroundNotification(state, now), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`, 서비스 범위 코루틴에서 `WaitingPoller(stations::arrivals(target.stationId, target.line, target.towards), controller::dispatch, …).run()`, 끝나면 `stopSelf()`. `onDestroy`에서 범위를 취소한다.
- Produces: `class AndroidServiceLauncher(context: Context) : WaitingServiceLauncher` — `start()` = `ContextCompat.startForegroundService`, `stop()` = `stopService`
- Produces (`WaitTargets.kt`): `data class WaitStart(val target: WaitTarget, val mode: WaitMode, val travelSec: Int)`, `fun waitStartFromRoute(route: RouteDto): WaitStart?` — `firstBoarding`이나 그 `stationId`가 없으면 `null`(버튼 비활성, `Copy.NO_REALTIME_FOR_ROUTE = "이 경로는 실시간 열차 정보를 쓸 수 없어요"`). 역 이름은 첫 지하철 단계의 `stops[0]`, 모드는 `allWalkBeforeBoarding`이면 DEPARTURE_TIMING 아니면 MINUTES_BEFORE(4.3), `travelSec = secToBoarding`. `fun waitStartFromGroup(stationId: String, group: ArrivalGroupDto): WaitStart` — MINUTES_BEFORE, travel 0, `direction` 포함
- 경로 상세 [승차 전 알림] → `StartWaiting(target, mode, route.withoutPaths(), travelSec, settings)`, 역 상세 그룹 [승차 전 알림] → `StartWaiting(target, MINUTES_BEFORE, null, 0, settings)` (둘 다 Task 6의 시작 흐름을 거친다)
- 최소 버전(3.4): 역 상세의 N분 전 알림만

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class WaitingPollerTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private val waiting = SessionState.Waiting(target, WaitMode.MINUTES_BEFORE, null, 0, 60, 180, 120, 0, deadlineAt = 1_800_000)
    private val ok = ApiResult.Ok(ArrivalsDto("홍대입구", "t", groups = listOf(
        ArrivalGroupDto("2호선", "외선", "합정", listOf(TrainDto("성수", 240))))))

    @Test fun pollsEvery30sAndDispatchesTrains() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val job = launch { WaitingPoller({ ok }, { sent += it }, { waiting }, { currentTime }).run() }
        advanceTimeBy(60_001); job.cancel()
        assertEquals(3, sent.size)
        assertEquals(listOf(TrainDto("성수", 240)), (sent[0] as SessionEvent.ArrivalsUpdated).trains)
    }
    @Test fun failureDispatchesArrivalsFailed() = runTest {
        val sent = mutableListOf<SessionEvent>()
        val job = launch { WaitingPoller({ ApiResult.Err(ApiError.Network) }, { sent += it }, { waiting }, { currentTime }).run() }
        runCurrent(); job.cancel()
        assertEquals(listOf<SessionEvent>(SessionEvent.ArrivalsFailed), sent)
    }
    @Test fun stopsWhenStateLeavesWaiting() = runTest {
        var state: SessionState = waiting
        val job = launch { WaitingPoller({ ok }, { state = SessionState.Idle }, { state }, { currentTime }).run() }
        advanceTimeBy(30_001)
        assertTrue(job.isCompleted)
    }
    @Test fun timeoutWhenDeadlinePassed() = runTest {
        val sent = mutableListOf<SessionEvent>()
        WaitingPoller({ ok }, { sent += it }, { waiting.copy(deadlineAt = 45_000) }, { currentTime }).run()
        assertEquals(SessionEvent.Timeout, sent.last())
    }
}

class WaitTargetsTest {
    @Test fun routeWithoutFirstBoardingHasNoWaitStart() = assertNull(waitStartFromRoute(specRoute().copy(firstBoarding = null)))
    @Test fun routeWaitStartUsesFirstBoarding() =
        assertEquals(WaitStart(WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null), WaitMode.DEPARTURE_TIMING, 420),
            waitStartFromRoute(specRoute()))
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 실기기와 절전 모드 확인** — 역 상세 [승차 전 알림]과 경로 상세 [승차 전 알림]을 각각 시작해 진행 중 알림 문구가 30초마다 바뀌고 알맞은 때 알림이 오는지 본다. 9.2의 `adb shell dumpsys battery unplug` → `adb shell dumpsys deviceidle force-idle` 상태에서도 알람이 울리는지 확인하고 결과를 `docs/measurements/device-checklist.md`(P5 Task 2에서 정리)에 적는다.
- [ ] **Step 6: Commit** — `git add android docs docs/learning && git commit -m "feat(android): 대기 포그라운드 서비스와 승차 전 알림"`

---

### Task 8: 알림 권한이 없을 때 앱 안 안내

**Files:**
- Create: `<pkg>/session/DueAlarms.kt`, `<test>/session/DueAlarmsTest.kt`
- Modify: `<pkg>/ui/session/SessionViewModel.kt`, `<pkg>/ui/session/SessionScreen.kt`

**Interfaces:**
- Consumes: Task 3 앱 안 모드(`inAppOnly`, `uiEvents`의 `InAppAlert`), Task 7 `WaitingPoller`, Task 5 `alertText`
- Produces: `fun dueAlarms(state: SessionState, now: Long): List<AlarmId>` — Waiting: plan이 있고 잠금이 없고 `plan.alertAt ≤ now`면 `[PreBoarding]`; Riding: `nextIndex`부터 `alertAt ≤ now`인 지점을 순서대로 `Alight(i)`, `endAt ≤ now`면 끝에 `SessionEnd`; 그 밖은 빈 목록
- Produces: `SessionViewModel.runInAppLoop()` — 세션 화면이 `repeatOnLifecycle(STARTED)` 안에서 앱 안 모드일 때만 부른다. 1초마다 `dueAlarms`의 각 id로 `dispatch(AlarmFired(id))`, 대기 단계면 같은 범위에서 `WaitingPoller`도 돌린다. `InAppAlert`는 `alertText` 대화상자와 진동 한 번으로 보여준다(8.6 부록 B: 화면이 열려 있는 동안에만).

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class DueAlarmsTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private fun waiting(plan: TrainPlan?, locked: Long? = null) = SessionState.Waiting(target, WaitMode.MINUTES_BEFORE, null,
        0, 60, 180, 120, 0, 1_800_000, plan = plan, lockedUntil = locked)
    private fun pt(alert: Long) = AlightPoint("역", false, PointKind.TRANSFER, alert + 120_000, alert, 1)

    @Test fun waitingDueOnlyWhenUnlockedAndPast() {
        assertEquals(listOf(AlarmId.PreBoarding), dueAlarms(waiting(TrainPlan(100, 1_000, null)), now = 100))
        assertTrue(dueAlarms(waiting(TrainPlan(100, 1_000, null)), now = 99).isEmpty())
        assertTrue(dueAlarms(waiting(TrainPlan(100, 1_000, null), locked = 61_000), now = 200).isEmpty())
    }
    @Test fun ridingFiresMissedPointsInOrderThenEnd() {
        val s = SessionState.Riding(listOf(pt(100), pt(200), pt(900)), nextIndex = 0, endAt = null)
        assertEquals(listOf(AlarmId.Alight(0), AlarmId.Alight(1)), dueAlarms(s, now = 300))
        assertEquals(listOf(AlarmId.SessionEnd), dueAlarms(s.copy(nextIndex = 3, endAt = 1_000), now = 1_000))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 거부 경로 확인** — 앱 설정에서 알림을 끈 뒤 세션을 시작하면 띠 문구가 보이고, 세션 화면을 열어 둔 동안 알림 대신 대화상자가 뜬다.
- [ ] **Step 6: Commit** — `git add android docs/learning && git commit -m "feat(android): 알림 권한이 없을 때 앱 안 안내"`

---

### Task 9: 세션 복원 (앱 시작, 재부팅, 정확한 알람 허용)

**Files:**
- Create: `<pkg>/session/RestorePlan.kt`, `<pkg>/session/BootReceiver.kt`, `<pkg>/session/ExactAlarmPermissionReceiver.kt`, `<test>/session/RestorePlanTest.kt`
- Modify: `<pkg>/session/SessionController.kt`(`suspend fun restore(onBoot: Boolean)`, `suspend fun rescheduleAll()`), `<pkg>/MainActivity.kt`, `AndroidManifest.xml`(`RECEIVE_BOOT_COMPLETED`, `BootReceiver`(BOOT_COMPLETED), `ExactAlarmPermissionReceiver`(`android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`), 둘 다 `exported="false"`)

**Interfaces:**
- Consumes: Task 2 상태·효과, Task 3 컨트롤러
- Produces: `fun planRestore(state: SessionState, now: Long, onBoot: Boolean): Pair<SessionState, List<SessionEffect>>` (8.7)
  - Idle → 그대로, 효과 없음
  - Waiting이고 `onBoot` → Idle, `[Alert(WaitingInterrupted), CancelAllAlarms, ClearOngoing, Finished(INTERRUPTED)]` (부팅 직후 포그라운드 서비스 제약을 피한다)
  - Waiting이고 `now ≥ deadlineAt` → Idle, `[CancelAllAlarms, ClearOngoing, Finished(TIMEOUT)]`
  - Waiting → 그대로, `[StartWaitingService]` + (plan이 있고 잠금이 없고 `alertAt > now`면 `Schedule(PreBoarding, alertAt)`) + `[UpdateOngoing]`
  - Riding → `nextIndex`부터 `alightAt ≤ now`인 지점은 건너뛰고(새 `nextIndex`는 처음 남은 지점), 남은 지점마다 `Schedule(Alight(i), max(alertAt, now))`, 그리고 `UpdateOngoing`. 남은 지점이 없으면 `lastEnd = endAt ?: (마지막 지점 alertAt + 300,000)`이 `now`보다 뒤면 `Schedule(SessionEnd, lastEnd)`와 `UpdateOngoing`, 아니면 Idle과 `[ClearOngoing, Finished(ARRIVED)]`
- Produces: `SessionController.restore(onBoot)` = 불러오기 → `planRestore` → 저장 → 효과 실행. `rescheduleAll()` = `cancelAll` 뒤 `planRestore(state, now, onBoot = false)`의 `Schedule` 효과만 실행
- 호출 위치: `MainActivity.onCreate`(저장된 인스턴스가 없을 때) → `restore(onBoot = false)`. **`App.onCreate`에서는 부르지 않는다** — 부팅·알람으로 프로세스가 깨어날 때 포그라운드 서비스를 시작하면 안 되기 때문이다. `BootReceiver` → `restore(onBoot = true)`, `ExactAlarmPermissionReceiver` → `rescheduleAll()` (둘 다 `goAsync`)

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class RestorePlanTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private fun waiting(plan: TrainPlan? = TrainPlan(900_000, 1_200_000, "성수")) = SessionState.Waiting(target,
        WaitMode.DEPARTURE_TIMING, null, 420, 60, 180, 120, startedAt = 0, deadlineAt = 1_800_000, plan = plan)
    private fun pt(alight: Long, alert: Long) = AlightPoint("역", false, PointKind.TRANSFER, alight, alert, 1)

    @Test fun restoreRidingSkipsPassedAndFiresDuePoints() {
        val riding = SessionState.Riding(listOf(pt(100_000, 0), pt(700_000, 580_000), pt(1_300_000, 1_180_000)))
        val (state, effects) = planRestore(riding, now = 650_000, onBoot = true)
        assertEquals(1, (state as SessionState.Riding).nextIndex)
        assertEquals(listOf(SessionEffect.Schedule(AlarmId.Alight(1), 650_000), SessionEffect.Schedule(AlarmId.Alight(2), 1_180_000)),
            effects.filterIsInstance<SessionEffect.Schedule>())
    }
    @Test fun restoreRidingAllPassedEnds() =
        assertEquals(SessionState.Idle, planRestore(SessionState.Riding(listOf(pt(100_000, 0))), now = 1_000_000, onBoot = false).first)
    @Test fun restoreWaitingOnBootInterrupts() {
        val (state, effects) = planRestore(waiting(), now = 10_000, onBoot = true)
        assertEquals(SessionState.Idle, state)
        assertEquals(SessionEffect.Alert(AlertContent.WaitingInterrupted), effects.first())
        assertFalse(SessionEffect.StartWaitingService in effects)
    }
    @Test fun restoreWaitingAfterDeadlineEnds() =
        assertEquals(SessionEffect.Finished(EndReason.TIMEOUT), planRestore(waiting(), now = 1_800_000, onBoot = false).second.last())
    @Test fun restoreWaitingRestartsServiceAndAlarm() =
        assertEquals(listOf(SessionEffect.StartWaitingService, SessionEffect.Schedule(AlarmId.PreBoarding, 900_000), SessionEffect.UpdateOngoing),
            planRestore(waiting(), now = 10_000, onBoot = false).second)
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest assembleDebug` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: (직접) 재부팅 확인** — 탑승 단계에서 기기를 재부팅해 남은 지점 알림이 오는지, 대기 단계에서 재부팅하면 "대기 알림이 중단됐어요"가 오는지 확인한다(`docs/measurements/device-checklist.md`).
- [ ] **Step 6: Commit** — `git add android docs docs/learning && git commit -m "feat(android): 앱 시작·재부팅·권한 변경 때 세션 복원"`

---

### Task 10: 경유지 합성 (서버, ⑧)

**Files:**
- Create: `server/app/services/via_composer.py`, `server/tests/unit/test_via_composer.py`, `server/tests/integration/test_routes_via.py`
- Modify: `server/app/services/route_service.py`, `server/app/api/routes.py`

**Interfaces:**
- Consumes: P2 `Route`, `Leg`, `route_type`, `route_id`, `RouteService`의 캐시 키 형식, `kakao_route` 픽스처
- Produces: `compose_via(first: list[Route], second: list[Route], limit: int = 5) -> list[Route]` (6.6) — 각 결과를 `total_sec`으로 정렬한 상위 3개끼리 9개 조합. `total_sec`·`distance_m`은 합, `fare`는 둘 다 있으면 합 아니면 `None`, `transfers`는 합(경유지에서 내리는 것은 환승이 아님), `legs = [a.legs[0], b.legs[0]]`, `type = route_type(두 구간의 모든 단계)`, `id = route_id(legs)`, `first_boarding = a.first_boarding`(첫 구간 기준). `(total_sec, transfers)` 오름차순 상위 `limit`개.
- Produces: `RouteService.search(origin, dest, origin_name=None, dest_name=None, via: tuple[float, float] | None = None, via_name: str | None = None)` — 경유지가 있으면 두 구간을 기존 단일 구간 검색(구간마다 캐시 키 `"{출발}|-|{도착}"`)으로 동시에(`asyncio.gather`) 가져와 합성한다. 출발=경유 또는 경유=도착(소수점 4자리)이면 `InvalidRequest("EQUAL_POINTS")`. 한 구간이라도 `NoRoute`면 그대로 던진다. `updated_at`은 두 구간 중 오래된 쪽, `stale`은 하나라도 stale이면 true.
- Produces: `GET /v1/routes`에 `via`, `viaName` 파라미터
- 최소 버전(3.4): 구간별 최적 경로 1개씩 이어 붙이기(`limit`과 상위 개수 1)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/unit/test_via_composer.py
def r(id_, total, transfers=0, fare=1000, line="2호선"):
    step = Step(mode="SUBWAY", duration_sec=total, distance_m=total * 10, guidance=id_, path=[],
                line_name=line, stops=["가", "나"])
    fb = FirstBoarding(station_id=id_, line_name=line, towards="나", sec_to_boarding=0, all_walk_before_boarding=True)
    return Route(id=id_, type="SUBWAY", total_sec=total, distance_m=total * 10, transfers=transfers, fare=fare,
                 legs=[Leg(steps=[step])], first_boarding=fb)

def test_combines_top3_each_and_sorts():
    first = [r("a1", 600), r("a2", 700), r("a3", 800), r("a4", 100_000)]
    second = [r("b1", 300, transfers=1), r("b2", 300), r("b3", 900)]
    out = compose_via(first, second)
    assert [x.total_sec for x in out] == [900, 900, 1000, 1000, 1100]
    assert out[0].transfers == 0 and all(len(x.legs) == 2 for x in out)

def test_sums_fare_and_distance_and_none_fare_propagates():
    assert compose_via([r("a", 600, fare=1400)], [r("b", 300, fare=1500)])[0].fare == 2900
    assert compose_via([r("a", 600, fare=None)], [r("b", 300)])[0].fare is None

def test_first_boarding_from_first_leg_and_via_is_not_a_transfer():
    out = compose_via([r("a", 600, transfers=1)], [r("b", 300)])[0]
    assert out.first_boarding.station_id == "a" and out.transfers == 1

# tests/integration/test_routes_via.py
HONGDAE, GYEONGBOK, GANGNAM = "37.5571,126.9245", "37.5759,126.9735", "37.4979,127.0276"

async def test_via_returns_two_legs(client, kakao_route):
    route = kakao_route("kakao_route_transfer.json")
    r = await client.get("/v1/routes", params={"from": HONGDAE, "via": GYEONGBOK, "to": GANGNAM})
    assert r.status_code == 200 and route.call_count == 2
    assert 1 <= len(r.json()["routes"]) <= 5 and all(len(x["legs"]) == 2 for x in r.json()["routes"])

async def test_via_reuses_cached_leg(client, kakao_route):
    route = kakao_route("kakao_route_transfer.json")
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GYEONGBOK})
    await client.get("/v1/routes", params={"from": HONGDAE, "via": GYEONGBOK, "to": GANGNAM})
    assert route.call_count == 2

async def test_via_equal_to_origin_is_400(client, respx_mock):
    r = await client.get("/v1/routes", params={"from": HONGDAE, "via": HONGDAE, "to": GANGNAM})
    assert r.status_code == 400 and r.json()["error"]["reason"] == "EQUAL_POINTS" and not respx_mock.calls
```

- [ ] **Step 2: 실패 확인** — Run: `cd server && pytest tests/unit/test_via_composer.py tests/integration/test_routes_via.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: Commit** — `git add server docs/learning && git commit -m "feat(server): 경유지 경로 합성"`

---

### Task 11: 경유지 (앱)

**Files:**
- Modify: `<pkg>/ui/routeedit/RouteFlowViewModel.kt`, `<pkg>/ui/routeedit/RouteEditScreen.kt`, `<pkg>/ui/mappick/MapPickScreen.kt`([경유] 버튼), `<pkg>/ui/routedetail/RouteDetailScreen.kt`(구간 사이 "경유지: {이름}" 구분선), `<test>/ui/routeedit/RouteFlowViewModelTest.kt`, `<test>/fakes/Fakes.kt`(`FakeRouteRepository.lastVia`)

**Interfaces:**
- Consumes: P3 `RouteRepository.routes(origin, dest, via)`, Task 10 서버 `via`
- Produces: `RouteFlowState`에 `via: Endpoint? = null`, `viaVisible: Boolean = false`. `RouteFlowViewModel.toggleVia()`(숨기면 `via = null`), `apply(PickResult(Slot.VIA, …))`는 `via`를 넣고 `viaVisible = true`, `search()`는 `via`를 넘긴다, `swap()`은 경유지를 그대로 둔다
- 경로 편집 화면: "+ 경유지" 버튼을 누를 때만 경유지 칸이 펼쳐진다(4.2), 칸 오른쪽 [×]로 지운다. 지도 핀 선택의 경로 모드는 [출발] [경유] [도착].

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun viaHiddenByDefaultAndToggles() = runTest {
    val vm = vm(FakeLocationProvider(true, here)); vm.init(null); advanceUntilIdle()
    assertFalse(vm.state.value.viaVisible); vm.toggleVia(); assertTrue(vm.state.value.viaVisible)
}
@Test fun searchSendsVia() = runTest {
    val vm = vm(FakeLocationProvider(true, here)); vm.init(7); advanceUntilIdle()
    val via = Endpoint("경복궁", GeoPoint(37.5759, 126.9735), EndpointKind.STATION, "경복궁")
    vm.apply(PickResult(Slot.VIA, via)); vm.search(); advanceUntilIdle()
    assertEquals(via, routes.lastVia)
}
@Test fun hidingViaClearsIt() = runTest {
    val vm = vm(FakeLocationProvider(true, here)); vm.init(null); advanceUntilIdle()
    vm.apply(PickResult(Slot.VIA, Endpoint("경복궁", GeoPoint(37.5759, 126.9735), EndpointKind.PIN)))
    vm.toggleVia()
    assertNull(vm.state.value.via)
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 경유지 1곳 추가"`

---

### Task 12: 자주 가는 경로 (⑨)

**Files:**
- Create: `<pkg>/ui/home/FavoriteRouteSort.kt`, `<test>/ui/home/FavoriteRouteSortTest.kt`
- Modify: `<pkg>/data/repository/FavoriteRepository.kt`, `<pkg>/ui/routeedit/RouteFlowViewModel.kt`, `<pkg>/ui/routedetail/RouteDetailScreen.kt`([★ 즐겨찾기]), `<pkg>/ui/home/HomeViewModel.kt`, `<pkg>/ui/home/HomeScreen.kt`, `<pkg>/ui/AppNavHost.kt`, `<test>/fakes/Fakes.kt`

**Interfaces:**
- Consumes: P3 `FavoriteRouteDao`(`observeAll`, `insert`, `delete`, `insertUsage`, `observeUsagesSince`), `RouteFlowGraph(favoriteRouteId)`
- Produces (`FavoriteRouteSort.kt`): `fun circularMinuteDistance(a: Int, b: Int): Int = min(|a − b|, 1440 − |a − b|)`, `fun sortFavoriteRoutes(routes: List<FavoriteRouteEntity>, usages: List<RouteUsageEntity>, now: ZonedDateTime): List<FavoriteRouteEntity>` — `now − 30일` 이후 기록만 본다. 경로마다 (현재 시:분과 `circularMinuteDistance ≤ 60`인 사용 수, 30일 전체 사용 수, 마지막 사용 시각, `createdAt`)을 모두 내림차순으로 정렬한다(7.2). 시:분은 `now.zone`으로 계산한다. `fun sameEndpoints(fav: FavoriteRouteEntity, origin: Endpoint, via: Endpoint?, dest: Endpoint): Boolean` — 좌표를 소수점 4자리로 비교, 경유지는 둘 다 없거나 같아야 한다.
- Produces (`FavoriteRepository`에 추가): `val routes: Flow<List<FavoriteRouteEntity>>`, `fun usagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>>`, `suspend fun addRoute(origin: Endpoint, via: Endpoint?, dest: Endpoint)`, `suspend fun removeRoute(id: Long)`, `suspend fun findRoute(origin: Endpoint, via: Endpoint?, dest: Endpoint): FavoriteRouteEntity?`, `suspend fun recordUsageIfFavorite(origin: Endpoint, via: Endpoint?, dest: Endpoint, at: Long)`
- Produces: `RouteFlowViewModel` — 생성자에 `favorites: FavoriteRepository`를 `clock` 앞에 더하고(P3 Task 6 테스트의 생성 코드를 함께 고친다), 검색이 성공하면 `recordUsageIfFavorite`(7.2 부록 B), 상태에 `isFavorite`, `toggleFavorite()`. `init`은 `init(destMyPlaceId: Long?, favoriteRouteId: Long? = null)`이 되고, `favoriteRouteId`가 있으면 즐겨찾기의 출발·경유·도착을 넣고 바로 `search()` — 홈 카드 한 번 탭으로 경로 결과(⑨)
- 홈: 자주 가는 경로 카드(`"{출발} → {도착}"`, 경유지가 있으면 `"{출발} → {경유} → {도착}"`)를 `sortFavoriteRoutes(…, ZonedDateTime.now())` 순서로 보여주고, 누르면 `RouteFlowGraph(favoriteRouteId = id)` → 곧바로 경로 결과
- 최소 버전(3.4): 최근 사용순

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
class FavoriteRouteSortTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun fav(id: Long, created: Long = 0) = FavoriteRouteEntity(id, "집", 37.0, 127.0, null, null, null, "학교", 37.5, 127.5, created)
    private fun use(id: Long, t: ZonedDateTime) = RouteUsageEntity(routeId = id, usedAt = t.toInstant().toEpochMilli())

    @Test fun countsUsesWithinOneHourOfNow() {
        val usages = listOf(use(1, at(17, 7, 30)), use(1, at(18, 8, 50)), use(2, at(17, 18, 0)), use(2, at(18, 18, 0)), use(2, at(19, 18, 0)))
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, at(20, 8, 10)).map { it.id })
    }
    @Test fun windowWrapsAroundMidnight() {
        val usages = listOf(use(1, at(19, 23, 45)), use(2, at(19, 2, 0)), use(2, at(18, 2, 0)))
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, at(20, 0, 30)).map { it.id })
    }
    @Test fun ignoresUsesOlderThan30Days() {
        val usages = listOf(use(2, at(1, 8, 0)), use(2, at(2, 8, 0)), use(1, at(19, 12, 0)))
        // 11/1·11/2 기록은 12/5 기준 30일 밖 → 둘 다 창 안 0회, 전체는 1이 1회 · 2가 0회
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, ZonedDateTime.of(2026, 12, 5, 8, 0, 0, 0, zone)).map { it.id })
    }
    @Test fun tieBreaksByMostRecentUse() {      // 창 안 0회·전체 1회로 같을 때
        val usages = listOf(use(1, at(19, 12, 0)), use(2, at(18, 12, 0)))
        assertEquals(listOf(1L, 2L), sortFavoriteRoutes(listOf(fav(2), fav(1)), usages, at(20, 8, 0)).map { it.id })
    }
    @Test fun sameEndpointsUsesFourDecimals() {
        val f = fav(1)
        assertTrue(sameEndpoints(f, Endpoint("a", GeoPoint(37.00004, 127.0), EndpointKind.PIN), null, Endpoint("b", GeoPoint(37.5, 127.5), EndpointKind.PIN)))
        assertFalse(sameEndpoints(f, Endpoint("a", GeoPoint(37.0002, 127.0), EndpointKind.PIN), null, Endpoint("b", GeoPoint(37.5, 127.5), EndpointKind.PIN)))
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`. 실기기에서 ★한 경로가 홈 카드로 보이고 한 번 탭으로 경로 결과가 열린다.
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 자주 가는 경로와 시간대별 정렬"`

---

### Task 13: 실시간 결합 (서버, ⑩)

**Files:**
- Create: `server/tests/integration/test_routes_realtime.py`
- Modify: `server/app/services/arrival_service.py`, `server/app/services/route_service.py`, `server/app/cache/ttl_cache.py`(`peek`), `server/app/core/container.py`(RouteService에 ArrivalService 연결)

**Interfaces:**
- Consumes: P2 `ArrivalService.get_arrivals`, `RouteService`, `FirstBoarding.next_trains`
- Produces: `ArrivalService.next_trains(station_id: str, line: str, towards: str | None) -> list[Train] | None` — `towards`가 없으면 `None`(방향을 정할 수 없음). `get_arrivals(station_id, line, towards)`의 첫 그룹 열차(최대 2대), 그룹이 없거나 `AppError`가 나면 `None`(경고 로그) (6.6)
- Produces: `RouteService` — 생성자에 `arrivals: ArrivalService`를 더한다. 캐시에서 꺼낸 경로(카카오 결과만 캐시 — 6.4)에 응답할 때마다 실시간 정보를 붙인다. 서로 다른 `(station_id, line_name, towards)`마다 한 번씩 `asyncio.gather`로 조회하고, 경로는 `model_copy(update=...)`로 복사해 붙인다. **캐시에 든 객체를 바꾸지 않는다.** 경로 캐시는 `RouteService.cache` 속성으로 둔다.
- Produces: `TtlCache.peek(key: str) -> V | None` — 지표를 세지 않고 저장된 값을 보는 조회(테스트·진단용)
- 앱: P3 Task 8의 경로 카드는 `nextTrains`가 오면 "다음 열차 N분"을 이미 보여준다. 실기기에서 확인만 한다.
- 최소 버전(3.4): 앱이 경로 상세를 열 때 첫 탑승역 도착을 따로 조회해 상세 화면에서만 표시

- [ ] **Step 1: 실패하는 테스트 작성**

```python
HONGDAE, GANGNAM = "37.5571,126.9245", "37.4979,127.0276"

def boarding_at_hongdae(routes):
    return [x for x in routes if x["firstBoarding"] and x["firstBoarding"]["stationId"] == "홍대입구"
            and x["firstBoarding"]["towards"]]

async def test_next_trains_attached_from_matching_group(client, kakao_route, seoul):
    kakao_route("kakao_route_subway_only.json"); seoul("seoul_realtime_hongdae.json")
    routes = (await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})).json()["routes"]
    hongdae = boarding_at_hongdae(routes)
    assert hongdae and any(x["firstBoarding"]["nextTrains"] for x in hongdae)
    assert all(len(x["firstBoarding"]["nextTrains"] or []) <= 2 for x in hongdae)

async def test_one_lookup_per_boarding_station(client, kakao_route, seoul):
    kakao_route("kakao_route_subway_only.json"); arrivals = seoul("seoul_realtime_hongdae.json")
    routes = (await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})).json()["routes"]
    stations = {x["firstBoarding"]["stationId"] for x in routes
                if x["firstBoarding"] and x["firstBoarding"]["stationId"] and x["firstBoarding"]["towards"]}
    assert arrivals.call_count == len(stations)      # 같은 역은 실시간 캐시로 한 번만

async def test_realtime_failure_keeps_routes(client, kakao_route, respx_mock):
    kakao_route("kakao_route_subway_only.json")
    respx_mock.get(url__startswith=SEOUL_REALTIME_BASE).mock(return_value=httpx.Response(500))
    r = await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    assert r.status_code == 200 and all(x["firstBoarding"] is None or x["firstBoarding"]["nextTrains"] is None
                                        for x in r.json()["routes"])

async def test_route_cache_keeps_kakao_result_only(client, kakao_route, seoul, services):
    kakao_route("kakao_route_subway_only.json"); seoul("seoul_realtime_hongdae.json")
    await client.get("/v1/routes", params={"from": HONGDAE, "to": GANGNAM})
    cached = services.routes.cache.peek("37.5571,126.9245|-|37.4979,127.0276")
    assert cached and all(r.first_boarding is None or r.first_boarding.next_trains is None for r in cached)
```

(`seoul` 픽스처의 시계 맞춤은 P2 Task 9와 같다.)

- [ ] **Step 2: 실패 확인** — Run: `cd server && pytest tests/integration/test_routes_realtime.py -q` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `pytest -q && ruff check .` / Expected: 모두 통과
- [ ] **Step 5: (직접) 배포와 앱 확인** — 배포 후 실기기 경로 결과 카드에 "다음 열차 N분"이 보이는지 확인한다.
- [ ] **Step 6: Commit** — `git add server docs/learning && git commit -m "feat(server): 경로 결과에 첫 탑승역 실시간 도착 결합"`

---

### Task 14 (선택, 3.2 ⑫): 경로 결과의 "지하철만 보기"

필수 기능(Task 1~13)을 모두 마친 뒤 시간이 남을 때만 한다. ⑪(실시간 정보로 하차 알림 보정)은 이 계획 범위 밖이며, 할 경우 별도 계획을 쓴다.

**Files:**
- Modify: `<pkg>/ui/routeedit/RouteFlowViewModel.kt`, `<pkg>/ui/routes/RoutesScreen.kt`, `<test>/ui/routeedit/RouteFlowViewModelTest.kt`

**Interfaces:**
- Produces: `RouteFlowState.subwayOnly: Boolean = false`, `RouteFlowViewModel.setSubwayOnly(on: Boolean)`, 파생 값 `visibleRoutes: List<RouteDto>`(켜져 있으면 `type == "SUBWAY"`만). 경로 결과 화면 위에 "지하철만" 칩.

- [ ] **Step 1: 실패하는 테스트 작성** — `subwayOnlyFilter`: `type`이 SUBWAY·MIXED·BUS인 경로 3개에서 켜면 SUBWAY 하나만, 끄면 3개
- [ ] **Step 2: 실패 확인** — Run: `./gradlew testDebugUnitTest` / Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — Run: `./gradlew testDebugUnitTest` / Expected: `BUILD SUCCESSFUL`
- [ ] **Step 5: Commit** — `git add android docs/learning && git commit -m "feat(android): 경로 결과 지하철만 보기"`
