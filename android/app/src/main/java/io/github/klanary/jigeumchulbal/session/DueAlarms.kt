package io.github.klanary.jigeumchulbal.session

/**
 * 앱 안 모드(알림 권한 없음, 스펙 8.6)에서 AlarmManager 대신 세션 화면이 1초마다 확인하는 "지금 울릴 알람".
 * 대기 단계는 출발 알림 뒤 그 열차를 기다리는 잠금이 없을 때만 승차 전 알람을, 탑승 단계는 놓친 지점을 순서대로 모두,
 * 마지막 지점 뒤 종료 시각이 지났으면 세션 종료를 돌려준다.
 */
fun dueAlarms(state: SessionState, now: Long): List<AlarmId> = when (state) {
    SessionState.Idle -> emptyList()
    is SessionState.Waiting -> {
        val plan = state.plan
        if (plan != null && state.lockedUntil == null && plan.alertAt <= now) listOf(AlarmId.PreBoarding) else emptyList()
    }
    is SessionState.Riding -> buildList {
        state.points.withIndex().drop(state.nextIndex)
            .takeWhile { it.value.alertAt <= now }
            .forEach { add(AlarmId.Alight(it.index)) }
        val endAt = state.endAt
        if (endAt != null && endAt <= now) add(AlarmId.SessionEnd)
    }
}
