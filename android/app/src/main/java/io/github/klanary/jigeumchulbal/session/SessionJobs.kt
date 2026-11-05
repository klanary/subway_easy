package io.github.klanary.jigeumchulbal.session

import android.content.pm.ServiceInfo
import android.os.Build
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.data.repository.TrainRepository
import io.github.klanary.jigeumchulbal.tracking.TRACKED_LINES
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 세션 서비스가 지금 상태에서 할 일(위치 결합 스펙 7.3). 안드로이드 서비스와 떼어 순수 함수로 테스트한다.

/** 대기 조회, 대기 조회와 탑승 감지, 탄 열차 추적, 탑승 감지(환승 뒤), 할 일 없음. */
enum class SessionJob { NONE, WAIT, WAIT_AND_DETECT, TRACK, DETECT }

/** 위치로 탑승을 알아볼 곳: 탈 역과 그 다음 정거장(위치 결합 스펙 7.1). */
data class DetectionSpot(val line: String, val boardStationId: String, val nextStop: String)

/**
 * 탑승을 감지할 곳. 대기 중이면 경로의 첫 지하철 단계, 탑승 중이면 열차를 아직 정하지 못한 추적 단계다.
 * 추적할 수 없는 호선이거나 탈 역·다음 정거장을 모르면 null이다.
 */
fun detectionSpot(state: SessionState): DetectionSpot? = when (state) {
    SessionState.Idle -> null
    is SessionState.Waiting -> state.route?.detectionSpot()
    is SessionState.Riding -> state.tracking?.takeIf { it.trainNo == null }?.let { tracking ->
        state.points.getOrNull(tracking.pointIndex)?.let { spot(it.line, it.boardStationId, it.nextStop) }
    }
}

/** 경로의 첫 지하철 단계에서 탑승을 감지할 곳(대기 중인 세션, 경로 상세 화면). */
fun RouteDto.detectionSpot(): DetectionSpot? =
    legs.flatMap { it.steps }.firstOrNull { it.mode == StepMode.SUBWAY }?.let { step ->
        spot(step.lineName, step.boardStationId, step.stops?.getOrNull(1))
    }

private fun spot(line: String?, board: String?, next: String?): DetectionSpot? =
    if (line != null && line in TRACKED_LINES && board != null && next != null) DetectionSpot(line, board, next) else null

fun sessionJob(state: SessionState, canDetect: Boolean): SessionJob = when (state) {
    SessionState.Idle -> SessionJob.NONE
    is SessionState.Waiting -> if (canDetect && detectionSpot(state) != null) SessionJob.WAIT_AND_DETECT else SessionJob.WAIT
    is SessionState.Riding -> {
        val tracking = state.tracking
        when {
            tracking == null -> SessionJob.NONE
            tracking.trainNo != null -> SessionJob.TRACK
            // 감지할 수 없으면 서비스를 멈춘다. 직접 고르면 상태 머신이 서비스를 다시 켠다.
            canDetect && detectionSpot(state) != null -> SessionJob.DETECT
            else -> SessionJob.NONE
        }
    }
}

/**
 * 세션 서비스 유형. 위치 권한 없이 `location` 유형으로 시작하면 Android 14에서 SecurityException으로 앱이 죽으므로,
 * 권한이 있을 때만 더한다(Review Focus 4). Android 10 미만은 유형을 쓰지 않는다.
 */
fun sessionServiceType(hasLocationPermission: Boolean, sdkInt: Int): Int = when {
    sdkInt < Build.VERSION_CODES.Q -> 0
    hasLocationPermission -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}

/**
 * 할 일이 없어질 때까지 지금 상태에 맞는 일을 돌린다. 할 일의 종류가 바뀌면 지금 일을 바로 멈추고 다음 일로 넘어간다.
 * 탑승 감지는 위치만 기다려 스스로 끝나지 않으므로, 그사이 직접 고르기로 열차를 정하면 이렇게 멈춰야 추적이 시작된다.
 * 조회도 다음 조회까지 기다리지 않고 곧바로 넘어간다. 일을 멈춰도 상태 전환은 컨트롤러가 끝까지 한다.
 */
suspend fun runSessionJobs(
    states: StateFlow<SessionState>,
    canDetect: () -> Boolean,
    wait: suspend () -> Unit,
    track: suspend () -> Unit,
    detect: suspend (withWaiting: Boolean) -> Unit,
) {
    while (true) {
        val job = sessionJob(states.value, canDetect())
        if (job == SessionJob.NONE) return
        coroutineScope {
            val work = launch {
                when (job) {
                    SessionJob.WAIT -> wait()
                    SessionJob.WAIT_AND_DETECT -> detect(true)
                    SessionJob.TRACK -> track()
                    SessionJob.DETECT -> detect(false)
                    SessionJob.NONE -> Unit
                }
            }
            val watcher = launch {
                states.first { sessionJob(it, canDetect()) != job }
                work.cancel()
            }
            work.invokeOnCompletion { watcher.cancel() }
        }
    }
}

/** 대기 단계의 실시간 도착 조회(⑦). 세션 서비스의 감지와 함께 돌 때도 같은 것을 쓴다. */
fun waitingPoller(controller: SessionController, stations: StationRepository, clock: () -> Long) = WaitingPoller(
    fetch = { stations.arrivals(it.stationId, it.line, it.towards) },
    dispatch = controller::dispatch,
    state = { controller.state.value },
    clock = clock,
)

/** 세션 서비스와 앱 안 모드가 함께 쓰는 대기 조회·추적 조회. 감지는 [detect]로 받는다(서비스만 한다). */
suspend fun runSessionWork(
    controller: SessionController,
    stations: StationRepository,
    trains: TrainRepository,
    clock: () -> Long,
    canDetect: () -> Boolean,
    detect: suspend (withWaiting: Boolean) -> Unit,
) = runSessionJobs(
    states = controller.state,
    canDetect = canDetect,
    wait = { waitingPoller(controller, stations, clock).run() },
    track = {
        TrackingPoller(
            // TrackingPoller는 열차 번호를 정한 동안에만 조회한다.
            fetch = { trains.eta(checkNotNull(it.trainNo), it.line, it.alightStationId) },
            dispatch = controller::dispatch,
            state = { controller.state.value },
            clock = clock,
        ).run()
    },
    detect = detect,
)
