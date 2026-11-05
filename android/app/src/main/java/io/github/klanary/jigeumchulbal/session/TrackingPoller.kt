package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.TrainEtaDto
import kotlinx.coroutines.delay

/**
 * 탑승 중 탄 열차 추적(⑪, 위치 결합 스펙 7.2). 탄 열차를 정한 동안 [intervalMs]마다 내릴 역까지 남은 시간을 받아
 * 컨트롤러에 넘긴다. 열차를 못 찾았거나(`found == false`) 조회가 실패하면 실패로만 알린다(3회 연속이면 끊김 표시).
 * 열차를 아직 정하지 못했거나 탑승 단계가 아니면 끝난다. 세션 서비스와 앱 안 모드가 함께 쓴다.
 */
class TrackingPoller(
    private val fetch: suspend (Tracking) -> ApiResult<TrainEtaDto>,
    private val dispatch: suspend (SessionEvent) -> Unit,
    private val state: () -> SessionState,
    private val clock: () -> Long,
    private val intervalMs: Long = 30_000,
) {
    suspend fun run() {
        while (true) {
            val tracking = (state() as? SessionState.Riding)?.tracking?.takeIf { it.trainNo != null } ?: return
            val event = when (val result = fetch(tracking)) {
                is ApiResult.Ok ->
                    if (result.value.found) SessionEvent.TrainEtaUpdated(result.value.etaSec, clock()) else SessionEvent.TrainLookupFailed
                is ApiResult.Err -> SessionEvent.TrainLookupFailed
            }
            dispatch(event)
            delay(intervalMs)
        }
    }
}
