package io.github.klanary.jigeumchulbal.ui.routedetail

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.data.repository.TrainRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.session.DetectedTrain
import io.github.klanary.jigeumchulbal.session.SessionController
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.TrainSource
import io.github.klanary.jigeumchulbal.session.detectionSpot
import io.github.klanary.jigeumchulbal.tracking.awaitBoarding
import io.github.klanary.jigeumchulbal.tracking.boardedTrain
import io.github.klanary.jigeumchulbal.tracking.detectionTarget
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 경로 상세에서 묻는 "OO행에 타셨나요?". [train]이 null이면 탑승은 알아냈지만 열차를 고르지 못했다. */
data class BoardingPrompt(val train: DetectedTrain?)

/**
 * 경로 상세만 보던 중의 탑승 자동 감지(⑭, 위치 결합 스펙 7.1). 화면이 보이는 동안만 위치를 받고, 탑승을 알아내면
 * 하차 알림을 시작할지 화면 안에서 묻는다. 세션이 이미 진행 중이면 세션 서비스가 감지하므로 여기서는 하지 않는다.
 */
class BoardingPromptViewModel(
    private val location: LocationProvider,
    private val stations: StationRepository,
    private val trains: TrainRepository,
    private val controller: SessionController,
) : ViewModel() {
    private val _prompt = MutableStateFlow<BoardingPrompt?>(null)
    val prompt: StateFlow<BoardingPrompt?> = _prompt.asStateFlow()

    /** [아니에요]를 누르면 이 화면에서는 다시 묻지 않는다. */
    private var dismissed = false

    /**
     * 탑승을 기다린다. 화면이 STARTED인 동안 부르고, 화면을 떠나면 취소된다. 세션이 진행 중이거나, 위치 권한이 없거나,
     * 첫 지하철 단계가 추적할 수 있는 호선이 아니거나, 이미 물었거나 [아니에요]를 눌렀으면 바로 끝난다.
     */
    suspend fun watch(route: RouteDto) {
        if (dismissed || _prompt.value != null) return
        if (controller.state.value != SessionState.Idle || !location.hasPermission()) return
        val spot = route.detectionSpot() ?: return
        val target = detectionTarget(spot.line, spot.boardStationId, spot.nextStop) { stations.search(it) } ?: return
        if (!awaitBoarding(target, location.updates())) return
        if (dismissed || controller.state.value != SessionState.Idle) return // 그새 세션을 시작했다
        _prompt.value = BoardingPrompt(boardedTrain { trains.near(spot.line, spot.boardStationId, spot.nextStop) })
    }

    fun dismiss() {
        dismissed = true
        _prompt.value = null
    }

    /** [하차 알림 시작]으로 세션이 시작된 뒤 부른다. 알아낸 열차를 지금 추적 단계의 열차로 정한다(출처 AUTO). */
    suspend fun confirmStarted() {
        val train = _prompt.value?.train
        _prompt.value = null
        val tracking = (controller.state.value as? SessionState.Riding)?.tracking ?: return
        if (train != null) {
            controller.dispatch(SessionEvent.TrainIdentified(tracking.pointIndex, train.trainNo, TrainSource.AUTO, train.destination))
        }
    }
}

@Composable
fun boardingPromptViewModel(): BoardingPromptViewModel = appViewModel {
    BoardingPromptViewModel(it.locationProvider, it.stationRepository, it.trainRepository, it.sessionController)
}
