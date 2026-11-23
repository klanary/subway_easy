package io.github.klanary.jigeumchulbal.ui.session

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.SessionUiEvent
import io.github.klanary.jigeumchulbal.session.TrainPlan
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.ceilMinutes
import io.github.klanary.jigeumchulbal.session.currentPoint
import io.github.klanary.jigeumchulbal.session.noPlanText
import io.github.klanary.jigeumchulbal.session.stopLabel
import io.github.klanary.jigeumchulbal.session.timeToAlight
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 통학 세션 화면과 홈 배너가 쓴다. 세션 자체는 앱 전체에 하나인 [SessionController]가 들고 있다. */
class SessionViewModel(
    private val controller: SessionController,
    private val clock: () -> Long,
    exactAlarms: () -> Boolean = { true },
) : ViewModel() {
    private val readExactAlarms = exactAlarms

    val state: StateFlow<SessionState> = controller.state
    val inAppOnly: StateFlow<Boolean> = controller.inAppOnly
    val events: SharedFlow<SessionUiEvent> = controller.uiEvents

    /** 남은 분을 다시 그리는 1초 시계. 보는 화면이 있을 때만 돈다. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(clock())
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock())

    /** 정확한 알람이 안 되면 화면에 "알림이 늦을 수 있어요"를 띄운다(스펙 8.4). */
    fun exactAlarms(): Boolean = readExactAlarms()

    fun boarded() {
        viewModelScope.launch { controller.dispatch(SessionEvent.Boarded) }
    }

    fun cancel() {
        viewModelScope.launch { controller.dispatch(SessionEvent.Cancel) }
    }
}

@Composable
fun sessionViewModel(): SessionViewModel =
    appViewModel { SessionViewModel(it.sessionController, System::currentTimeMillis, it.alarmScheduler::canScheduleExact) }

/** 세션 화면 머리글과 홈 배너 문구(스펙 4.2). 세션이 없으면 null이다. */
fun sessionHeadline(state: SessionState, now: Long): String? = when (state) {
    SessionState.Idle -> null
    is SessionState.Waiting -> state.plan?.let { waitingHeadline(state.mode, it, now) } ?: noPlanText(state)
    is SessionState.Riding -> state.currentPoint()?.let { point ->
        "${stopLabel(point)}까지 ${point.stopsToGo}정거장, ${timeToAlight(point, now)}"
    }
}

private fun waitingHeadline(mode: WaitMode, plan: TrainPlan, now: Long): String {
    val left = plan.trainArrivalAt - now
    if (left <= 0) return "열차가 곧 도착해요"
    val train = plan.destination?.let { "${it}행 열차" } ?: "열차"
    val minutes = ceilMinutes(left)
    return when (mode) {
        WaitMode.DEPARTURE_TIMING -> "지금 출발하면 ${minutes}분 뒤 ${train}를 탈 수 있어요"
        // 역 상세에서 시작한 세션은 이미 역에 있으므로 열차가 오는 시각만 알린다.
        WaitMode.MINUTES_BEFORE -> "${minutes}분 뒤 ${train}가 도착해요"
    }
}
