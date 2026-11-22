package io.github.klanary.jigeumchulbal.ui.routes

import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.ui.common.color
import io.github.klanary.jigeumchulbal.ui.common.formatDuration
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining

/** 구간 막대의 한 칸. [weight]는 전체 단계 소요 시간에서 이 단계가 차지하는 비율이다. */
data class Segment(val weight: Float, val color: Long, val label: String?)

data class RouteCardUi(
    val id: String,
    val totalText: String,
    val transfersText: String,
    val segments: List<Segment>,
    /** "5호선 마포 승차 · 다음 열차 3분", 출발 시각을 골랐으면 "08:14 출발 · 도착 약 08:46"(재설계 스펙 4.4). */
    val boardingText: String?,
    /** 첫 환승역에서 도착 뒤 출발하는 열차 두 대: "을지로3가 환승 08:18 · 그다음 08:21". */
    val transferText: String? = null,
    /** 탈 호선(·버스) 순서. 도보는 빼고 이어지는 같은 호선은 한 번만(Task 6 합의). */
    val lines: List<String> = emptyList(),
    /** "도보 2분 + 2호선 10분 + 환승 4분 + 5호선 15분". 합이 [totalText]다(수동 검토 2026-11-22). */
    val breakdownText: String? = null,
)

/**
 * 경로 카드에 보일 문구. 다음 열차까지 남은 시간은 서버 응답을 받은 시각([receivedAt])부터
 * 지금([now])까지 지난 시간을 빼서 줄어든다(남은 초는 응답 시점 기준이다 — 스펙 6.2).
 */
fun RouteDto.toCardUi(receivedAt: Long, now: Long): RouteCardUi {
    val steps = legs.flatMap { it.steps }
    val stepsTotal = steps.sumOf { it.durationSec }.coerceAtLeast(1)
    val elapsedSec = ((now - receivedAt) / 1000).toInt()
    val parts = breakdownParts(steps)
    val partsMin = parts.sumOf { it.second }
    // 서버 총 시간이 나눈 시간의 합보다 길면 그 차이는 열차를 기다리는 시간이다.
    val totalMin = maxOf(partsMin, (totalSec + 30) / 60)
    val shown = parts + listOfNotNull(("대기" to totalMin - partsMin).takeIf { it.second > 0 })
    return RouteCardUi(
        id = id,
        totalText = formatDuration(totalMin * 60),
        transfersText = if (transfers == 0) "환승 없음" else "환승 ${transfers}회",
        segments = steps.map { step ->
            Segment(step.durationSec.toFloat() / stepsTotal, step.color(), step.lineName ?: step.routeName)
        },
        boardingText = boardingLine(elapsedSec),
        transferText = transferSummary(),
        lines = steps.mapNotNull { it.lineName ?: it.routeName }.fold(emptyList()) { acc, l -> if (acc.lastOrNull() == l) acc else acc + l },
        breakdownText = shown.takeIf { it.isNotEmpty() }?.joinToString(" + ") { (label, min) -> "$label ${min}분" },
    )
}

/** 단계를 (이름, 분)으로: 처음·끝 걷기는 "도보", 사이 걷기는 "환승", 이어지는 같은 이름은 합친다. */
private fun breakdownParts(steps: List<StepDto>): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    steps.forEachIndexed { i, step ->
        val label = when {
            step.mode == StepMode.WALK && (i == 0 || i == steps.lastIndex) -> "도보"
            step.mode == StepMode.WALK -> "환승"
            else -> step.lineName ?: step.routeName ?: "버스"
        }
        // 지하철끼리 걷기 단계 없이 이어지면 앞 열차 도착 ~ 이 열차 출발이 환승(걷기 + 기다림)이다.
        val before = steps.getOrNull(i - 1)
        if (step.mode == StepMode.SUBWAY && before?.mode == StepMode.SUBWAY) {
            val gap = gapMinutes(before.arrivesAt, step.departsAt)
            if (gap != null && gap > 0) out += "환승" to gap
        }
        val min = maxOf(1, (step.durationSec + 30) / 60)
        val last = out.lastOrNull()
        if (last != null && last.first == label) out[out.lastIndex] = label to last.second + min else out += label to min
    }
    return out
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
        trains.getOrNull(1)?.let { "그다음 ${clockOf(it.departsAt)}" },
    ).joinToString(" · ")
}

/** 두 ISO 시각 사이 분(반올림). 하나라도 없거나 읽지 못하면 null. */
private fun gapMinutes(fromIso: String?, toIso: String?): Int? {
    if (fromIso == null || toIso == null) return null
    return runCatching {
        val sec = java.time.Duration.between(java.time.OffsetDateTime.parse(fromIso), java.time.OffsetDateTime.parse(toIso)).seconds
        ((sec + 30) / 60).toInt()
    }.getOrNull()
}
