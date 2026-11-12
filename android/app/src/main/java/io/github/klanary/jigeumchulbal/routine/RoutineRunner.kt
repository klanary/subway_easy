package io.github.klanary.jigeumchulbal.routine

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.session.AlertContent
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionNotifierPort
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.StartResult
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.waitStartFromRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 루틴 알람이 울렸다(재설계 스펙 5.4): 다음 실행을 다시 예약하고, 그 경로로 "지금 출발하세요" 알림 세션을 시작한다
 * (출발 타이밍 방식, 걷는 시간 = 루틴의 걷는 시간). 이미 진행 중인 세션이 있으면 건너뛰고 알린다.
 */
class RoutineRunner(
    private val controller: SessionController,
    private val routines: RoutineRepository,
    private val notifier: SessionNotifierPort,
    private val settings: Flow<AppSettings>,
    private val json: Json,
) {
    suspend fun run(routineId: Long) {
        val routine = routines.get(routineId) ?: return
        if (!routine.enabled) return
        routines.reschedule(routineId)
        if (controller.state.value != SessionState.Idle) {
            notifier.alert(AlertContent.RoutineSkipped)
            return
        }
        val route = runCatching { json.decodeFromString<RouteDto>(routine.routeJson) }.getOrNull() ?: return
        val start = waitStartFromRoute(route) ?: return
        val event = SessionEvent.StartWaiting(
            target = start.target,
            mode = WaitMode.DEPARTURE_TIMING,
            route = route,
            travelSec = routine.walkMin * 60,
            settings = settings.first(),
            alightAlerts = routine.alightAlerts,
        )
        if (controller.start(event, inAppOnly = false) == StartResult.ALREADY_RUNNING) {
            notifier.alert(AlertContent.RoutineSkipped)
        }
    }
}
