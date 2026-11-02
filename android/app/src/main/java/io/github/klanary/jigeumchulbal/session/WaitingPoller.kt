package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import kotlinx.coroutines.delay

/**
 * 대기 단계(⑦)의 조회 반복(스펙 8.2). 세션이 대기 중인 동안 [intervalMs]마다 실시간 도착을 받아 컨트롤러에 넘긴다.
 * 실패한 조회는 실패로만 알리고(컨트롤러가 마지막 예측을 유지한다), 최대 대기 시간이 지나면 끝낸다.
 * 안드로이드에 의존하지 않아 가상 시간으로 테스트한다. 대기 서비스와 앱 안 모드(Task 8)가 함께 쓴다.
 */
class WaitingPoller(
    private val fetch: suspend (WaitTarget) -> ApiResult<ArrivalsDto>,
    private val dispatch: suspend (SessionEvent) -> Unit,
    private val state: () -> SessionState,
    private val clock: () -> Long,
    private val intervalMs: Long = 30_000,
) {
    suspend fun run() {
        while (true) {
            val waiting = state() as? SessionState.Waiting ?: return
            if (clock() >= waiting.deadlineAt) {
                dispatch(SessionEvent.Timeout)
                return
            }
            when (val result = fetch(waiting.target)) {
                is ApiResult.Ok -> {
                    val trains = pickGroup(result.value.groups, waiting.target)?.trains ?: emptyList()
                    dispatch(SessionEvent.ArrivalsUpdated(trains, clock()))
                }
                is ApiResult.Err -> dispatch(SessionEvent.ArrivalsFailed)
            }
            delay(intervalMs)
        }
    }
}
