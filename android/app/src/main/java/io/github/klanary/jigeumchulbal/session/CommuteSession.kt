package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
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
        /**
         * [탑승했어요] 뒤 환승·하차 진동 알림을 이어 갈지(재설계 스펙 D2, 5.4). 사용자가 켰을 때만 true다.
         * 이 칸이 없던 예전 저장분은 false로 읽는다.
         */
        val alightAlerts: Boolean = false,
    ) : SessionState

    /**
     * 하차 알림(⑤): 하차·환승 지점마다 알람을 걸어 두고, 울릴 때마다 다음 지점으로 넘어간다.
     * 탑승 단계에는 세션 서비스를 쓰지 않는다(재설계 스펙 5.5). 예전 저장분의 추적 칸(tracking)은 읽을 때 무시한다.
     */
    @Serializable
    @SerialName("riding")
    data class Riding(
        val points: List<AlightPoint>,
        val nextIndex: Int = 0,
        val endAt: Long? = null,
        val alightLeadSec: Int = 120,
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
        /** [탑승했어요] 뒤 환승·하차 진동 알림을 이어 갈지. 사용자가 켰을 때만 true. */
        val alightAlerts: Boolean = false,
    ) : SessionEvent

    data class ArrivalsUpdated(val trains: List<TrainDto>, val receivedAt: Long) : SessionEvent

    data object ArrivalsFailed : SessionEvent

    data object Boarded : SessionEvent

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

    /** "진행 중인 통학 알림이 있어 루틴을 건너뛰었어요"(재설계 스펙 5.4) */
    data object RoutineSkipped : AlertContent

    /** "루틴을 시작하지 못했어요": 저장된 경로를 읽지 못했다. 루틴을 다시 만들어야 한다. */
    data object RoutineFailed : AlertContent

    /** 백그라운드에서 세션 서비스를 켜지 못했다: 눌러서 앱을 열면 이어 간다(재설계 스펙 13). */
    data object TapToStart : AlertContent
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
                alightAlerts = event.alightAlerts,
            )
            listOf(SessionEffect.StartSessionService, SessionEffect.UpdateOngoing)
        }
        is SessionEvent.StartRiding -> {
            val lead = event.settings.alightLeadSec
            val points = alightPoints(event.route, clock(), StartFrom.FIRST_TRANSIT, lead)
            startRiding(points, lead, before = emptyList())
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
            SessionEvent.Boarded -> board(current, now)
            SessionEvent.Timeout -> end(EndReason.TIMEOUT, stopService = true)
            SessionEvent.Cancel -> end(EndReason.CANCELLED, stopService = true)
            else -> emptyList()
        }
    }

    /**
     * 대기에서 탑승으로(⑤⑦). 진동 알림을 켰고 경로가 있으면 첫 지하철 단계부터 하차 지점 알람을 건다.
     * 아니면 세션을 끝낸다(재설계 스펙 D2, 5.4). 탑승 단계에는 세션 서비스를 쓰지 않으므로 멈춘다.
     */
    private fun board(current: SessionState.Waiting, now: Long): List<SessionEffect> {
        val route = current.route
        if (route == null || !current.alightAlerts) return end(EndReason.BOARDED_AT_STATION, stopService = true)
        val points = alightPoints(route, now, StartFrom.FIRST_SUBWAY, current.alightLeadSec)
        val before = listOf(SessionEffect.StopSessionService, SessionEffect.CancelAlarm(AlarmId.PreBoarding))
        return startRiding(points, current.alightLeadSec, before)
    }

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
                listOf(SessionEffect.ClearOngoing, SessionEffect.Finished(EndReason.ARRIVED))
            }
            AlarmId.PreBoarding -> emptyList()
        }
        SessionEvent.Cancel -> end(EndReason.CANCELLED, stopService = false)
        else -> emptyList()
    }

    private fun startRiding(
        points: List<AlightPoint>,
        alightLeadSec: Int,
        before: List<SessionEffect>,
    ): List<SessionEffect> {
        if (points.isEmpty()) {
            // 대중교통 단계가 없는 경로(도보만)는 알릴 지점이 없다.
            state = SessionState.Idle
            return before + listOf(SessionEffect.CancelAllAlarms, SessionEffect.ClearOngoing, SessionEffect.Finished(EndReason.ARRIVED))
        }
        state = SessionState.Riding(points, alightLeadSec = alightLeadSec)
        return before + points.mapIndexed { i, p -> SessionEffect.Schedule(AlarmId.Alight(i), p.alertAt) } +
            SessionEffect.UpdateOngoing
    }

    /** 세션을 끝낸다. [stopService]: 세션 서비스가 돌고 있을 수 있으면(대기 단계) 멈춘다. */
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
    }
}
