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
    /** "5호선 마포 승차 · 다음 열차 3분", 출발 시각을 골랐으면 "08:14 출발 · 도착 약 08:46"(재설계 스펙 4.4). */
    val boardingText: String?,
    /** 첫 환승역의 탈 수 있는 열차와 그다음 열차: "을지로3가 환승 08:18 · 놓치면 08:21". */
    val transferText: String? = null,
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
        boardingText = boardingLine(elapsedSec),
        transferText = transferSummary(),
    )
}

/** ISO 시각에서 "HH:mm"(서버는 한국 시간 오프셋을 붙여 보낸다). */
private fun clockOf(iso: String): String = iso.substring(11, 16)

private fun RouteDto.boardingLine(elapsedSec: Int): String? {
    val leave = departAt
    val arrive = arriveAt
    if (leave != null && arrive != null) return "${clockOf(leave)} 출발 · 도착 약 ${clockOf(arrive)}"
    val boarding = firstBoarding ?: return null
    val board = listOfNotNull(boarding.lineName, boarding.stationId).joinToString(" ") + " 승차"
    val next = boarding.nextTrains?.firstOrNull()?.remainingSec?.let { "다음 열차 " + formatRemaining(it - elapsedSec) }
    return listOfNotNull(board, next).joinToString(" · ")
}

private fun RouteDto.transferSummary(): String? {
    val step = legs.flatMap { it.steps }.firstOrNull { !it.transferTrains.isNullOrEmpty() } ?: return null
    val trains = step.transferTrains.orEmpty()
    val station = step.stops?.firstOrNull() ?: return null
    return listOfNotNull(
        "$station 환승 ${clockOf(trains[0].departsAt)}",
        trains.getOrNull(1)?.let { "놓치면 ${clockOf(it.departsAt)}" },
    ).joinToString(" · ")
}
