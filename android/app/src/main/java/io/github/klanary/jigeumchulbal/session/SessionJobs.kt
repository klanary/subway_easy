package io.github.klanary.jigeumchulbal.session

import android.content.pm.ServiceInfo
import android.os.Build
import io.github.klanary.jigeumchulbal.data.repository.StationRepository

// 세션 서비스가 지금 상태에서 할 일. 안드로이드 서비스와 떼어 순수 함수로 테스트한다.

/** 대기 조회, 할 일 없음. 탑승 단계에는 서비스를 쓰지 않는다(재설계 스펙 5.5). */
enum class SessionJob { NONE, WAIT }

fun sessionJob(state: SessionState): SessionJob = when (state) {
    is SessionState.Waiting -> SessionJob.WAIT
    SessionState.Idle, is SessionState.Riding -> SessionJob.NONE
}

/** 세션 서비스 유형: dataSync만 쓴다(재설계 스펙 7). Android 10 미만은 유형을 쓰지 않는다. */
fun sessionServiceType(sdkInt: Int): Int =
    if (sdkInt < Build.VERSION_CODES.Q) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

/** 대기 단계의 실시간 도착 조회(⑦). */
fun waitingPoller(controller: SessionController, stations: StationRepository, clock: () -> Long) = WaitingPoller(
    fetch = { stations.arrivals(it.stationId, it.line, it.towards) },
    dispatch = controller::dispatch,
    state = { controller.state.value },
    clock = clock,
)

/** 세션 서비스와 앱 안 모드가 함께 쓴다. 대기 단계가 끝나면 돌아온다. */
suspend fun runSessionWork(controller: SessionController, stations: StationRepository, clock: () -> Long) {
    if (sessionJob(controller.state.value) == SessionJob.WAIT) waitingPoller(controller, stations, clock).run()
}
