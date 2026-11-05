package io.github.klanary.jigeumchulbal.ui.session

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.TrainNearDto
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.data.repository.TrainRepository
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.SessionUiEvent
import io.github.klanary.jigeumchulbal.session.TrainPlan
import io.github.klanary.jigeumchulbal.session.TrainSource
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.ceilMinutes
import io.github.klanary.jigeumchulbal.session.currentPoint
import io.github.klanary.jigeumchulbal.session.dueAlarms
import io.github.klanary.jigeumchulbal.session.noPlanText
import io.github.klanary.jigeumchulbal.session.runSessionWork
import io.github.klanary.jigeumchulbal.session.sessionJob
import io.github.klanary.jigeumchulbal.session.stopLabel
import io.github.klanary.jigeumchulbal.session.timeToAlight
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 통학 세션 화면과 홈 배너가 쓴다. 세션 자체는 앱 전체에 하나인 [SessionController]가 들고 있다. */
class SessionViewModel(
    private val controller: SessionController,
    private val clock: () -> Long,
    private val stations: StationRepository,
    exactAlarms: () -> Boolean = { true },
    private val trains: TrainRepository,
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

    private val _picker = MutableStateFlow<PickerState>(PickerState.Hidden)

    /** 탄 열차 직접 고르기 목록(⑭ 3번, 위치 결합 스펙 7.1). */
    val picker: StateFlow<PickerState> = _picker.asStateFlow()

    /** 지금 추적 중인 단계의 탈 역 근처에서, 그 단계의 다음 정거장 쪽으로 가는 열차를 불러온다. */
    fun openTrainPicker() {
        val riding = controller.state.value as? SessionState.Riding ?: return
        val tracking = riding.tracking ?: return
        val point = riding.points.getOrNull(tracking.pointIndex)
        val station = point?.boardStationId ?: return
        _picker.value = PickerState.Loading
        viewModelScope.launch {
            _picker.value = when (val result = trains.near(tracking.line, station, point.nextStop)) {
                is ApiResult.Ok -> PickerState.Loaded(result.value.trains)
                is ApiResult.Err -> PickerState.Failed(pickerErrorMessage(result.error))
            }
        }
    }

    /** 고른 열차로 정한다. 세션 서비스가 꺼져 있었으면 상태 머신이 다시 켠다. */
    fun chooseTrain(train: TrainNearDto) {
        _picker.value = PickerState.Hidden
        val tracking = (controller.state.value as? SessionState.Riding)?.tracking ?: return
        viewModelScope.launch {
            controller.dispatch(
                SessionEvent.TrainIdentified(tracking.pointIndex, train.trainNo, TrainSource.MANUAL, train.destination),
            )
        }
    }

    fun closeTrainPicker() {
        _picker.value = PickerState.Hidden
    }

    /**
     * 앱 안 모드(알림 권한 없음, 스펙 8.6): 알람과 세션 서비스 대신 이 화면이 보이는 동안 1초마다 울릴 알람을 확인하고,
     * 서비스가 할 일(대기 조회, 탄 열차 추적)도 돌린다. 탑승 감지는 하지 않는다(위치 결합 스펙 7.3, 서비스만 한다).
     * 세션 화면이 STARTED인 동안, 앱 안 모드일 때만 부른다.
     */
    suspend fun runInAppLoop(): Unit = coroutineScope {
        launch {
            // 할 일이 바뀔 때만 다시 시작한다(조회 결과로 상태가 바뀔 때마다 다시 시작하지 않게 할 일의 종류만 본다).
            // 직접 고르기로 추적이 다시 생겨도 여기서 이어서 돈다.
            controller.state.map { sessionJob(it, canDetect = false) }.distinctUntilChanged().collectLatest {
                runSessionWork(controller, stations, trains, clock, canDetect = { false }, detect = { awaitCancellation() })
            }
        }
        while (true) {
            dueAlarms(controller.state.value, clock()).forEach { controller.dispatch(SessionEvent.AlarmFired(it)) }
            delay(1_000)
        }
    }
}

@Composable
fun sessionViewModel(): SessionViewModel = appViewModel {
    SessionViewModel(
        it.sessionController,
        System::currentTimeMillis,
        it.stationRepository,
        it.alarmScheduler::canScheduleExact,
        it.trainRepository,
    )
}

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
