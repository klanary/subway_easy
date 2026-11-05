package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.session.CommuteSession.Companion.END_AFTER_LAST_MS

/**
 * 저장된 세션을 이어 가는 계획(스펙 8.7). 앱을 열 때와 재부팅 뒤에 부른다.
 * 대기 단계는 서비스와 알람을 다시 켜지만, 부팅 직후에는 포그라운드 서비스를 시작할 수 없어 세션을 끝내고 알린다.
 * 탑승 단계는 하차 시각까지 지난 지점은 건너뛰고, 알림 시각만 지난 지점은 바로 알린다.
 */
fun planRestore(state: SessionState, now: Long, onBoot: Boolean): Pair<SessionState, List<SessionEffect>> = when (state) {
    SessionState.Idle -> state to emptyList()
    is SessionState.Waiting -> restoreWaiting(state, now, onBoot)
    is SessionState.Riding -> restoreRiding(state, now, onBoot)
}

private fun restoreWaiting(state: SessionState.Waiting, now: Long, onBoot: Boolean): Pair<SessionState, List<SessionEffect>> = when {
    onBoot -> SessionState.Idle to listOf(
        SessionEffect.Alert(AlertContent.WaitingInterrupted),
        SessionEffect.CancelAllAlarms,
        SessionEffect.ClearOngoing,
        SessionEffect.Finished(EndReason.INTERRUPTED),
    )
    now >= state.deadlineAt -> SessionState.Idle to
        listOf(SessionEffect.CancelAllAlarms, SessionEffect.ClearOngoing, SessionEffect.Finished(EndReason.TIMEOUT))
    else -> state to buildList {
        add(SessionEffect.StartSessionService)
        // 지난 예측이나 잠금 중인 예측은 서비스의 다음 조회가 다시 계산한다.
        val plan = state.plan
        if (plan != null && state.lockedUntil == null && plan.alertAt > now) add(SessionEffect.Schedule(AlarmId.PreBoarding, plan.alertAt))
        add(SessionEffect.UpdateOngoing)
    }
}

private fun restoreRiding(state: SessionState.Riding, now: Long, onBoot: Boolean): Pair<SessionState, List<SessionEffect>> {
    // 탄 열차를 따라가던 중이면 세션 서비스를 다시 켠다. 재부팅 직후에는 포그라운드 서비스를 켜지 않고 알람만 되살린다
    // (위치 결합 스펙 7.3). 서비스가 없으면 알람은 마지막으로 옮겨진 시각대로 울린다.
    val service = listOfNotNull(SessionEffect.StartSessionService.takeIf { state.tracking != null && !onBoot })
    val points = state.points
    val first = (state.nextIndex until points.size).firstOrNull { points[it].alightAt > now }
    if (first == null) {
        // 남은 지점이 없다: 마지막 지점 알림 5분 뒤까지는 진행 중 알림을 두고, 그 뒤면 끝낸다.
        val lastEnd = state.endAt ?: points.lastOrNull()?.let { it.alertAt + END_AFTER_LAST_MS }
        return if (lastEnd != null && lastEnd > now) {
            state.copy(nextIndex = points.size, endAt = lastEnd) to
                service + listOf(SessionEffect.Schedule(AlarmId.SessionEnd, lastEnd), SessionEffect.UpdateOngoing)
        } else {
            SessionState.Idle to listOf(SessionEffect.ClearOngoing, SessionEffect.Finished(EndReason.ARRIVED))
        }
    }
    // 건너뛴 지점의 알람이 늦게 울려 이미 지난 역을 알리지 않게 취소한다.
    val skipped = (state.nextIndex until first).map { SessionEffect.CancelAlarm(AlarmId.Alight(it)) }
    val schedules = (first until points.size).map { SessionEffect.Schedule(AlarmId.Alight(it), maxOf(points[it].alertAt, now)) }
    return state.copy(nextIndex = first) to service + skipped + schedules + SessionEffect.UpdateOngoing
}
