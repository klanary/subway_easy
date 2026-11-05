package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.ui.common.Copy
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
    val subText = if (exactAlarms) null else Copy.MAY_BE_LATE
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
            val point = state.currentPoint() ?: return null
            OngoingContent(
                title = "${stopLabel(point)}까지 ${point.stopsToGo}정거장",
                text = timeToAlight(point, now),
                // 추적이 끊겨도 알람은 마지막 시각대로 울린다는 것을 함께 보여 준다(위치 결합 스펙 7.2).
                subText = listOfNotNull(Copy.TRACKING_LOST.takeIf { state.tracking?.lost == true }, subText)
                    .joinToString(" · ")
                    .ifEmpty { null },
                // 하차 예정 시각이 지나면 카운트다운이 음수로 넘어가지 않게 멈춘다.
                countdownTo = point.alightAt.takeIf { it > now },
                actions = listOf(SessionAction.CANCEL),
            )
        }
    }
}

/** 열차 계획이 없을 때: 첫 응답 전에는 아직 모르고(서버가 깨어나는 데 1분 가까이 걸릴 수 있다), 응답 뒤에는 탈 열차가 없다. */
fun noPlanText(state: SessionState.Waiting): String =
    if (state.updatedAt == null) "열차 정보를 불러오고 있어요" else "탈 수 있는 열차 정보가 없어요"

/** 지점까지 남은 시간. 하차 예정 시각이 지나면 음수 대신 "곧 도착해요". */
fun timeToAlight(point: AlightPoint, now: Long): String {
    val left = point.alightAt - now
    return if (left > 0) "약 ${ceilMinutes(left)}분" else "곧 도착해요"
}

private fun waitingTitle(target: WaitTarget): String =
    listOfNotNull(target.stationName, target.line, target.towards?.let { "$it 방면" }).joinToString(" ")

private fun waitingText(state: SessionState.Waiting, now: Long): String {
    val plan = state.plan
    return when {
        state.failures >= DISCONNECTED_AFTER_FAILURES -> "실시간 정보 연결 끊김"
        plan == null -> noPlanText(state)
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
