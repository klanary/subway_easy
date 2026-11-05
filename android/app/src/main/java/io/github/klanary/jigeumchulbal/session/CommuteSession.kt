package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.tracking.trackingFrom
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 세션 상태(스펙 8.1). 바뀔 때마다 DataStore에 JSON으로 저장해 앱 재시작·재부팅 뒤 복원한다(8.7). */
@Serializable
sealed interface SessionState {
    @Serializable
    @SerialName("idle")
    data object Idle : SessionState

    /** 승차 전 알림(⑦): 30초마다 실시간 도착을 받아 알림 시각을 다시 계산한다. */
    @Serializable
    @SerialName("waiting")
    data class Waiting(
        val target: WaitTarget,
        val mode: WaitMode,
        val route: RouteDto?,
        val travelSec: Int,
        val marginSec: Int,
        val leadSec: Int,
        val alightLeadSec: Int,
        val startedAt: Long,
        val deadlineAt: Long,
        val plan: TrainPlan? = null,
        /** 출발 알림 뒤 이 시각(그 열차 도착 + 60초)까지는 예약을 바꾸지 않는다. */
        val lockedUntil: Long? = null,
        val failures: Int = 0,
        val nextTrainSec: Int? = null,
        val updatedAt: Long? = null,
    ) : SessionState

    /**
     * 하차 알림(⑤): 하차·환승 지점마다 알람을 걸어 두고, 울릴 때마다 다음 지점으로 넘어간다.
     * [tracking]이 있으면 탄 열차의 실시간 위치로 하차 시각을 맞춘다(⑪, 위치 결합 스펙 7.2). [alightLeadSec]은 그때 알림
     * 시각을 다시 계산하는 데 쓰며, 이 칸이 없던 예전 저장분은 설정 기본값(120초)으로 읽는다.
     */
    @Serializable
    @SerialName("riding")
    data class Riding(
        val points: List<AlightPoint>,
        val nextIndex: Int = 0,
        val endAt: Long? = null,
        val alightLeadSec: Int = 120,
        val tracking: Tracking? = null,
    ) : SessionState
}

@Serializable
sealed interface AlarmId {
    @Serializable
    @SerialName("pre_boarding")
    data object PreBoarding : AlarmId

    @Serializable
    @SerialName("alight")
    data class Alight(val index: Int) : AlarmId

    @Serializable
    @SerialName("session_end")
    data object SessionEnd : AlarmId
}

sealed interface SessionEvent {
    data class StartWaiting(
        val target: WaitTarget,
        val mode: WaitMode,
        val route: RouteDto?,
        val travelSec: Int,
        val settings: AppSettings,
    ) : SessionEvent

    data class ArrivalsUpdated(val trains: List<TrainDto>, val receivedAt: Long) : SessionEvent

    data object ArrivalsFailed : SessionEvent

    data object Boarded : SessionEvent

    /** 위치로 탑승을 알아냈다(⑭ 자동 감지). 탄 열차를 고르지 못했으면 [train]이 null이다. */
    data class BoardedDetected(val train: DetectedTrain?) : SessionEvent

    /**
     * 탄 열차가 내릴 역까지 남은 시간(⑪). [etaSec]이 null이면 열차가 내릴 역을 지났다.
     * [receivedAt]은 응답을 받은 시각으로, 새 하차 시각은 `receivedAt + etaSec`이다.
     */
    data class TrainEtaUpdated(val etaSec: Int?, val receivedAt: Long) : SessionEvent

    /** 탄 열차를 지금 위치 목록에서 못 찾았거나 조회가 실패했다. */
    data object TrainLookupFailed : SessionEvent

    /** 탄 열차를 정하거나 바꾼다(⑭). [pointIndex]가 지금 추적 중인 단계와 다르면 무시한다. */
    data class TrainIdentified(
        val pointIndex: Int,
        val trainNo: String,
        val source: TrainSource,
        val destination: String?,
    ) : SessionEvent

    data class StartRiding(val route: RouteDto, val settings: AppSettings) : SessionEvent

    data class AlarmFired(val id: AlarmId) : SessionEvent

    data object Cancel : SessionEvent

    data object Timeout : SessionEvent
}

sealed interface AlertContent {
    /** "지금 출발하세요: N분 뒤 OO행 열차" */
    data class LeaveNow(val minutes: Int, val destination: String?) : AlertContent

    /** "N분 뒤 OO행 열차가 도착해요" */
    data class TrainSoon(val minutes: Int, val destination: String?) : AlertContent

    /** "다음 열차 기준으로 다시 안내할게요" */
    data object Replanned : AlertContent

    /** "곧 OO역이에요. 내릴 준비를 하세요" */
    data class AlightSoon(val point: AlightPoint) : AlertContent

    /** "대기 알림이 중단됐어요"(재부팅 등) */
    data object WaitingInterrupted : AlertContent

    /** "OO행 열차에 탄 것으로 보여요" — 위치로 탑승을 알아냈다. [다른 열차였어요] 버튼을 단다(위치 결합 스펙 7.1). */
    data class TrainDetected(val destination: String?) : AlertContent

    /** "어느 열차에 탔나요?" — 탑승은 알아냈지만 탄 열차를 정하지 못했다. */
    data object PickTrain : AlertContent
}

enum class EndReason { CANCELLED, TIMEOUT, ARRIVED, BOARDED_AT_STATION, INTERRUPTED }

/** 상태 머신이 돌려주는 "할 일". SessionController가 안드로이드 구성 요소로 실행한다. */
sealed interface SessionEffect {
    data class Schedule(val id: AlarmId, val at: Long) : SessionEffect

    data class CancelAlarm(val id: AlarmId) : SessionEffect

    data object CancelAllAlarms : SessionEffect

    data class Alert(val content: AlertContent) : SessionEffect

    data object UpdateOngoing : SessionEffect

    data object ClearOngoing : SessionEffect

    data object StartSessionService : SessionEffect

    data object StopSessionService : SessionEffect

    data class Finished(val reason: EndReason) : SessionEffect
}

/**
 * 통학 세션 상태 머신(스펙 8.1~8.3). 안드로이드 API를 쓰지 않고 현재 시각을 주입받는다.
 * 이벤트를 받아 상태를 바꾸고 할 일(효과) 목록을 돌려준다. 정의되지 않은 조합은 무시한다.
 */
class CommuteSession(
    private val clock: () -> Long,
    initial: SessionState = SessionState.Idle,
) {
    var state: SessionState = initial
        private set

    fun handle(event: SessionEvent): List<SessionEffect> = when (val current = state) {
        SessionState.Idle -> onIdle(event)
        is SessionState.Waiting -> onWaiting(current, event)
        is SessionState.Riding -> onRiding(current, event)
    }

    private fun onIdle(event: SessionEvent): List<SessionEffect> = when (event) {
        is SessionEvent.StartWaiting -> {
            val now = clock()
            state = SessionState.Waiting(
                target = event.target,
                mode = event.mode,
                route = event.route,
                travelSec = event.travelSec,
                marginSec = event.settings.departMarginSec,
                leadSec = event.settings.stationLeadSec,
                alightLeadSec = event.settings.alightLeadSec,
                startedAt = now,
                deadlineAt = now + event.settings.maxWaitMin * MINUTE_MS,
            )
            listOf(SessionEffect.StartSessionService, SessionEffect.UpdateOngoing)
        }
        is SessionEvent.StartRiding -> {
            val lead = event.settings.alightLeadSec
            val points = alightPoints(event.route, clock(), StartFrom.FIRST_TRANSIT, lead)
            val tracking = trackingFrom(points, 0)
            // 경로 상세에서 화면을 보며 시작하므로 세션 서비스를 켤 수 있다. 열차는 감지나 직접 고르기로 정한다.
            startRiding(points, lead, tracking, before = emptyList(), after = listOfNotNull(SessionEffect.StartSessionService.takeIf { tracking != null }))
        }
        else -> emptyList()
    }

    private fun onWaiting(current: SessionState.Waiting, event: SessionEvent): List<SessionEffect> {
        val now = clock()
        return when (event) {
            is SessionEvent.ArrivalsUpdated -> onArrivals(current, event, now)
            SessionEvent.ArrivalsFailed -> {
                state = current.copy(failures = current.failures + 1)
                listOf(SessionEffect.UpdateOngoing)
            }
            is SessionEvent.AlarmFired -> {
                val plan = current.plan
                if (event.id != AlarmId.PreBoarding || plan == null) return emptyList()
                state = current.copy(lockedUntil = plan.trainArrivalAt + MISS_GRACE_MS)
                val minutes = ceilDiv(plan.trainArrivalAt - now, MINUTE_MS).coerceAtLeast(0)
                val content = when (current.mode) {
                    WaitMode.DEPARTURE_TIMING -> AlertContent.LeaveNow(minutes, plan.destination)
                    WaitMode.MINUTES_BEFORE -> AlertContent.TrainSoon(minutes, plan.destination)
                }
                listOf(SessionEffect.Alert(content), SessionEffect.UpdateOngoing)
            }
            SessionEvent.Boarded -> board(current, now, detected = null, byDetection = false)
            is SessionEvent.BoardedDetected -> board(current, now, event.train, byDetection = true)
            SessionEvent.Timeout -> end(EndReason.TIMEOUT, stopService = true)
            SessionEvent.Cancel -> end(EndReason.CANCELLED, stopService = true)
            else -> emptyList()
        }
    }

    /**
     * 대기에서 탑승으로(⑤⑦). 경로가 있으면 첫 지하철 단계부터 하차 지점을 계산하고, 그 단계가 추적할 수 있는 호선이면
     * 탄 열차를 정한다: 위치로 알아낸 열차(AUTO) → 승차 전 알림이 계획한 열차(PLANNED) 순서(위치 결합 스펙 7.1).
     * 추적할 단계가 있으면 세션 서비스를 멈추지 않고 이어서 쓴다(7.3).
     */
    private fun board(current: SessionState.Waiting, now: Long, detected: DetectedTrain?, byDetection: Boolean): List<SessionEffect> {
        val route = current.route ?: return end(EndReason.BOARDED_AT_STATION, stopService = true)
        val points = alightPoints(route, now, StartFrom.FIRST_SUBWAY, current.alightLeadSec)
        val plan = current.plan
        val tracking = trackingFrom(points, 0)?.let { found ->
            when {
                found.pointIndex != 0 -> found // 계획·감지한 열차는 첫 지하철 단계의 열차다
                detected != null -> found.identify(detected.trainNo, TrainSource.AUTO, detected.destination)
                plan?.trainNo != null -> found.identify(plan.trainNo, TrainSource.PLANNED, plan.destination)
                else -> found
            }
        }
        val before = buildList {
            if (tracking == null) add(SessionEffect.StopSessionService)
            add(SessionEffect.CancelAlarm(AlarmId.PreBoarding))
        }
        val after = if (byDetection) listOfNotNull(detectionAlert(detected, tracking)) else emptyList()
        return startRiding(points, current.alightLeadSec, tracking, before, after)
    }

    /** 위치로 탑승을 알아냈을 때의 알림: 고른 열차가 있으면 "탄 것으로 보여요", 아직 열차를 모르면 "어느 열차에 탔나요?". */
    private fun detectionAlert(detected: DetectedTrain?, tracking: Tracking?): SessionEffect? = when {
        detected != null -> SessionEffect.Alert(AlertContent.TrainDetected(detected.destination))
        tracking != null && tracking.trainNo == null -> SessionEffect.Alert(AlertContent.PickTrain)
        else -> null
    }

    private fun Tracking.identify(trainNo: String, source: TrainSource, destination: String?) =
        copy(trainNo = trainNo, destination = destination, source = source, failures = 0, lost = false)

    private fun onArrivals(current: SessionState.Waiting, event: SessionEvent.ArrivalsUpdated, now: Long): List<SessionEffect> {
        val refreshed = current.copy(
            failures = 0, // 연속 실패만 세므로 성공하면 0으로 돌린다
            nextTrainSec = event.trains.mapNotNull { it.remainingSec }.minOrNull(),
            updatedAt = event.receivedAt,
        )
        val locked = current.lockedUntil
        if (locked != null && now < locked) {
            // 출발 알림대로 역으로 걸어가는 중: 다음 열차 기준으로 바꾸지 않는다(스펙 8.2).
            state = refreshed
            return listOf(SessionEffect.UpdateOngoing)
        }
        val plan = when (current.mode) {
            WaitMode.DEPARTURE_TIMING -> departurePlan(event.trains, event.receivedAt, current.travelSec, current.marginSec)
            WaitMode.MINUTES_BEFORE -> minutesBeforePlan(event.trains, event.receivedAt, current.leadSec, now)
        }
        state = refreshed.copy(plan = plan, lockedUntil = null)
        return buildList {
            if (plan == null) {
                add(SessionEffect.CancelAlarm(AlarmId.PreBoarding)) // 탈 수 있는 열차가 없다(막차 이후 등)
            } else {
                // 잠금이 풀렸는데 아직 타지 않았으면 그 열차를 놓친 것: 다음 열차로 한 번 다시 안내한다.
                if (locked != null) add(SessionEffect.Alert(AlertContent.Replanned))
                add(SessionEffect.Schedule(AlarmId.PreBoarding, plan.alertAt))
            }
            add(SessionEffect.UpdateOngoing)
        }
    }

    private fun onRiding(current: SessionState.Riding, event: SessionEvent): List<SessionEffect> = when (event) {
        is SessionEvent.AlarmFired -> when (val id = event.id) {
            is AlarmId.Alight -> {
                val point = current.points.getOrNull(id.index)
                if (point == null || id.index < current.nextIndex) {
                    emptyList() // 없는 지점이거나 이미 지난 지점(늦게 울린 알람, 복원과 겹친 알람)
                } else {
                    val isLast = id.index == current.points.lastIndex
                    val endAt = if (isLast) clock() + END_AFTER_LAST_MS else current.endAt
                    state = current.copy(nextIndex = id.index + 1, endAt = endAt)
                    buildList {
                        add(SessionEffect.Alert(AlertContent.AlightSoon(point)))
                        if (isLast && endAt != null) add(SessionEffect.Schedule(AlarmId.SessionEnd, endAt))
                        add(SessionEffect.UpdateOngoing)
                    }
                }
            }
            AlarmId.SessionEnd -> {
                state = SessionState.Idle
                buildList {
                    if (current.tracking != null) add(SessionEffect.StopSessionService)
                    add(SessionEffect.ClearOngoing)
                    add(SessionEffect.Finished(EndReason.ARRIVED))
                }
            }
            AlarmId.PreBoarding -> emptyList()
        }
        is SessionEvent.BoardedDetected -> onRidingDetected(current, event.train)
        is SessionEvent.TrainIdentified -> onTrainIdentified(current, event)
        is SessionEvent.TrainEtaUpdated -> onTrainEta(current, event)
        SessionEvent.TrainLookupFailed -> onTrainLookupFailed(current)
        SessionEvent.Cancel -> end(EndReason.CANCELLED, stopService = current.tracking != null)
        else -> emptyList()
    }

    /** 환승 뒤 등, 열차를 아직 정하지 못한 단계에서 위치로 탑승을 알아냈다(위치 결합 스펙 7.1, 7.2). */
    private fun onRidingDetected(current: SessionState.Riding, detected: DetectedTrain?): List<SessionEffect> {
        val tracking = current.tracking
        if (tracking == null || tracking.trainNo != null) return emptyList() // 이미 정했으면(직접 고름 등) 그대로 둔다
        if (detected != null) {
            state = current.copy(tracking = tracking.identify(detected.trainNo, TrainSource.AUTO, detected.destination))
        }
        return listOfNotNull(detectionAlert(detected, tracking), SessionEffect.UpdateOngoing)
    }

    private fun onTrainIdentified(current: SessionState.Riding, event: SessionEvent.TrainIdentified): List<SessionEffect> {
        val tracking = current.tracking
        if (tracking == null || tracking.pointIndex != event.pointIndex) return emptyList() // 다른 단계를 고른 늦은 사건
        state = current.copy(tracking = tracking.identify(event.trainNo, event.source, event.destination))
        // 직접 고르기는 화면에서 하므로 서비스를 켤 수 있다(권한이 없어 꺼져 있었으면 다시 켜서 추적한다).
        return listOfNotNull(
            SessionEffect.StartSessionService.takeIf { event.source == TrainSource.MANUAL },
            SessionEffect.UpdateOngoing,
        )
    }

    /**
     * 탄 열차의 새 하차 시각(⑪, 위치 결합 스펙 7.2). 지금 지점의 하차 시각이 20초 넘게 달라지면 그 지점과 이후 지점을
     * 같은 만큼 옮기고, 아직 울리지 않은 알람만 다시 예약한다(알림 시각 = max(지금, 하차 시각 − 알림 시점)).
     * 마지막 지점 알림 뒤의 세션 종료 시각도 같이 옮겨, 열차가 늦을 때 내리기 전에 진행 중 알림이 사라지지 않게 한다.
     */
    private fun onTrainEta(current: SessionState.Riding, event: SessionEvent.TrainEtaUpdated): List<SessionEffect> {
        val tracking = current.tracking?.takeIf { it.trainNo != null } ?: return emptyList()
        val index = tracking.pointIndex
        val point = current.points.getOrNull(index) ?: return emptyList()
        val etaSec = event.etaSec ?: return advanceTracking(current) // 내릴 역을 지났다
        val now = clock()
        val healthy = tracking.copy(failures = 0, lost = false)
        val delta = event.receivedAt + etaSec * SECOND_MS - point.alightAt
        if (abs(delta) <= ETA_SHIFT_MS) {
            if (healthy == tracking) return emptyList()
            state = current.copy(tracking = healthy)
            return listOf(SessionEffect.UpdateOngoing)
        }
        val leadMs = current.alightLeadSec * SECOND_MS
        val points = current.points.mapIndexed { j, p ->
            when {
                j < index -> p
                j < current.nextIndex -> p.copy(alightAt = p.alightAt + delta) // 이미 울린 알림은 다시 예약하지 않는다
                else -> (p.alightAt + delta).let { alight -> p.copy(alightAt = alight, alertAt = maxOf(now, alight - leadMs)) }
            }
        }
        val endAt = current.endAt?.let { maxOf(now, it + delta) }
        state = current.copy(points = points, endAt = endAt, tracking = healthy)
        return buildList {
            for (j in maxOf(index, current.nextIndex) until points.size) add(SessionEffect.Schedule(AlarmId.Alight(j), points[j].alertAt))
            if (endAt != null && endAt != current.endAt) add(SessionEffect.Schedule(AlarmId.SessionEnd, endAt))
            add(SessionEffect.UpdateOngoing)
        }
    }

    /** 탄 열차를 못 찾음. 3회 연속이면 "실시간 추적 끊김"이고 알람은 마지막 시각을 유지한다. 내릴 시각이 지났으면 다음 단계로 넘어간다. */
    private fun onTrainLookupFailed(current: SessionState.Riding): List<SessionEffect> {
        val tracking = current.tracking?.takeIf { it.trainNo != null } ?: return emptyList()
        val point = current.points.getOrNull(tracking.pointIndex)
        if (point == null || clock() >= point.alightAt) return advanceTracking(current)
        val failures = tracking.failures + 1
        val updated = tracking.copy(failures = failures, lost = failures >= LOST_AFTER_FAILURES)
        state = current.copy(tracking = updated)
        return if (updated.lost != tracking.lost) listOf(SessionEffect.UpdateOngoing) else emptyList()
    }

    /** 지금 단계의 하차 지점을 지났다(환승, 7.2): 다음 지하철 단계를 추적하고 열차는 다시 정한다. 더 없으면 세션 서비스를 멈춘다. */
    private fun advanceTracking(current: SessionState.Riding): List<SessionEffect> {
        val index = current.tracking?.pointIndex ?: return emptyList()
        val next = trackingFrom(current.points, index + 1)
        state = current.copy(tracking = next)
        return listOfNotNull(SessionEffect.StopSessionService.takeIf { next == null }, SessionEffect.UpdateOngoing)
    }

    private fun startRiding(
        points: List<AlightPoint>,
        alightLeadSec: Int,
        tracking: Tracking?,
        before: List<SessionEffect>,
        after: List<SessionEffect> = emptyList(),
    ): List<SessionEffect> {
        if (points.isEmpty()) {
            // 대중교통 단계가 없는 경로(도보만)는 알릴 지점이 없다.
            state = SessionState.Idle
            return before + listOf(SessionEffect.CancelAllAlarms, SessionEffect.ClearOngoing, SessionEffect.Finished(EndReason.ARRIVED))
        }
        state = SessionState.Riding(points, alightLeadSec = alightLeadSec, tracking = tracking)
        return before + points.mapIndexed { i, p -> SessionEffect.Schedule(AlarmId.Alight(i), p.alertAt) } + after +
            SessionEffect.UpdateOngoing
    }

    /** 세션을 끝낸다. [stopService]: 세션 서비스가 돌고 있을 수 있으면(대기 단계, 추적 중인 탑승 단계) 멈춘다. */
    private fun end(reason: EndReason, stopService: Boolean): List<SessionEffect> {
        state = SessionState.Idle
        return buildList {
            if (stopService) add(SessionEffect.StopSessionService)
            add(SessionEffect.CancelAllAlarms)
            add(SessionEffect.ClearOngoing)
            add(SessionEffect.Finished(reason))
        }
    }

    private fun ceilDiv(a: Long, b: Long): Int = ((a + b - 1).floorDiv(b)).toInt()

    companion object {
        const val MINUTE_MS = 60_000L
        const val MISS_GRACE_MS = 60_000L
        const val END_AFTER_LAST_MS = 300_000L
        const val SECOND_MS = 1_000L

        /** 탄 열차의 하차 시각이 이보다 많이 달라질 때만 지점을 옮긴다(위치 결합 스펙 3.1 ⑪). */
        const val ETA_SHIFT_MS = 20_000L

        /** 탄 열차를 이만큼 연달아 못 찾으면 "실시간 추적 끊김"이다. */
        const val LOST_AFTER_FAILURES = 3
    }
}
