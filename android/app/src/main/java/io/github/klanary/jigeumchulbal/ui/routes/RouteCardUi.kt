package io.github.klanary.jigeumchulbal.ui.routes

import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.ui.common.color
import io.github.klanary.jigeumchulbal.ui.common.formatDuration
import io.github.klanary.jigeumchulbal.ui.common.formatFare
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining

/** 구간 막대의 한 칸. [weight]는 전체 단계 소요 시간에서 이 단계가 차지하는 비율이다. */
data class Segment(val weight: Float, val color: Long, val label: String?)

data class RouteCardUi(
    val id: String,
    val totalText: String,
    val transfersText: String,
    val fareText: String,
    val segments: List<Segment>,
    val nextTrainText: String?,
)

/**
 * 경로 카드에 보일 문구. 다음 열차까지 남은 시간은 서버 응답을 받은 시각([receivedAt])부터
 * 지금([now])까지 지난 시간을 빼서 줄어든다(남은 초는 응답 시점 기준이다 — 스펙 6.2).
 */
fun RouteDto.toCardUi(receivedAt: Long, now: Long): RouteCardUi {
    val steps = legs.flatMap { it.steps }
    val stepsTotal = steps.sumOf { it.durationSec }.coerceAtLeast(1)
    val elapsedSec = ((now - receivedAt) / 1000).toInt()
    return RouteCardUi(
        id = id,
        totalText = formatDuration(totalSec),
        transfersText = if (transfers == 0) "환승 없음" else "환승 ${transfers}회",
        fareText = formatFare(fare),
        segments = steps.map { step ->
            Segment(step.durationSec.toFloat() / stepsTotal, step.color(), step.lineName ?: step.routeName)
        },
        nextTrainText = firstBoarding?.nextTrains?.firstOrNull()?.remainingSec
            ?.let { "다음 열차 " + formatRemaining(it - elapsedSec) },
    )
}
