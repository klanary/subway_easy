package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.session.CommuteSession.Companion.MINUTE_MS
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining

/** 진행 중 알림의 버튼(스펙 8.5). 대기 단계는 [탑승했어요] [종료], 탑승 단계는 [종료]. */
enum class SessionAction(val label: String) {
    BOARDED("탑승했어요"),
    CANCEL("종료"),
}

/** 진행 중 알림 한 장. [countdownTo]가 있으면 시스템이 그 시각까지 남은 시간을 직접 센다(스펙 8.3). */
data class OngoingContent(
    val title: String,
    val text: String,
    val subText: String?,
    val countdownTo: Long?,
    val actions: List<SessionAction>,
)

/** 조회가 이만큼 연달아 실패하면 "연결 끊김"을 보인다(스펙 8.2). */
private const val DISCONNECTED_AFTER_FAILURES = 3

/** 진행 중 알림 문구(스펙 8.2~8.4). 세션이 없으면 null이다. */
fun ongoingContent(state: SessionState, now: Long, exactAlarms: Boolean): OngoingContent? {
    val subText = if (exactAlarms) null else "알림이 늦을 수 있어요"
    return when (state) {
        SessionState.Idle -> null
        is SessionState.Waiting -> OngoingContent(
            title = waitingTitle(state.target),
            text = waitingText(state, now),
            subText = subText,
            countdownTo = null,
            actions = listOf(SessionAction.BOARDED, SessionAction.CANCEL),
        )
        is SessionState.Riding -> {
            // 다음 지점을 보이고, 마지막 지점 알림까지 지났으면 마지막 지점을 계속 보인다.
            val point = state.points.getOrNull(state.nextIndex) ?: state.points.lastOrNull() ?: return null
            val left = point.alightAt - now
            OngoingContent(
                title = "${stopLabel(point)}까지 ${point.stopsToGo}정거장",
                // 하차 예정 시각이 지나면 카운트다운이 음수로 넘어가지 않게 멈춘다.
                text = if (left > 0) "약 ${ceilMinutes(left)}분" else "곧 도착해요",
                subText = subText,
                countdownTo = point.alightAt.takeIf { left > 0 },
                actions = listOf(SessionAction.CANCEL),
            )
        }
    }
}

private fun waitingTitle(target: WaitTarget): String =
    listOfNotNull(target.stationName, target.line, target.towards?.let { "$it 방면" }).joinToString(" ")

private fun waitingText(state: SessionState.Waiting, now: Long): String {
    val plan = state.plan
    return when {
        state.failures >= DISCONNECTED_AFTER_FAILURES -> "실시간 정보 연결 끊김"
        // 첫 응답 전에는 탈 열차가 없는지 아직 모른다(서버가 깨어나는 데 1분 가까이 걸릴 수 있다).
        plan == null && state.updatedAt == null -> "열차 정보를 불러오고 있어요"
        plan == null -> "탈 수 있는 열차 정보가 없어요"
        else -> {
            val timing = when (state.mode) {
                WaitMode.DEPARTURE_TIMING -> {
                    val left = plan.alertAt - now
                    if (left > 0) "${ceilMinutes(left)}분 뒤 출발" else "지금 출발하세요"
                }
                WaitMode.MINUTES_BEFORE -> "${state.leadSec / 60}분 전에 알려드려요"
            }
            "다음 열차 ${formatRemaining(state.nextTrainSec)} · $timing"
        }
    }
}

private fun ceilMinutes(ms: Long): Long = (ms + MINUTE_MS - 1) / MINUTE_MS
