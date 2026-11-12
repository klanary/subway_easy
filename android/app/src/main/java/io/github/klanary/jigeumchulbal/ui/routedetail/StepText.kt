package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.ui.common.formatDuration

/** 단계 안내의 제목. 정거장 수는 탄 역을 뺀 정차 역 수다. */
fun stepTitle(step: StepDto): String {
    val stops = step.stops.orEmpty()
    val rides = (stops.size - 1).coerceAtLeast(0)
    return when (step.mode) {
        StepMode.WALK -> "도보 ${formatDuration(step.durationSec)}"
        StepMode.SUBWAY -> {
            val line = step.lineName ?: "지하철"
            // "5호선 마포 → 공덕 · 공덕 방면 · 1정거장 · 2분"(재설계 스펙 4.5: 탈 역과 방향, 정거장 수, 단계별 시간)
            if (stops.size >= 2) {
                "$line ${stops.first()} → ${stops.last()} · ${stops[1]} 방면 · ${rides}정거장 · ${formatDuration(step.durationSec)}"
            } else {
                line
            }
        }
        StepMode.BUS -> {
            val bus = "${step.routeName ?: ""}번 버스"
            if (stops.size >= 2) "$bus · ${rides}정거장" else bus
        }
    }
}

/**
 * 환승역 열차 줄(2026-11-12 사용자 요청): "을지로3가 도착 08:15 · 탈 수 있는 열차 08:18 대화행 · 놓치면 08:21 구파발행".
 * 걸어서 갈아타는 시간 때문에 첫 열차를 놓칠 수 있어 그다음 열차도 함께 보인다. 정보가 없으면 null.
 */
fun transferLine(step: StepDto): String? {
    val trains = step.transferTrains?.takeIf { it.isNotEmpty() } ?: return null
    fun train(t: io.github.klanary.jigeumchulbal.data.remote.TransferTrainDto) =
        listOfNotNull(t.departsAt.substring(11, 16), t.destination?.let { "${it}행" }).joinToString(" ")
    return listOfNotNull(
        step.boardAfter?.let { "${step.stops?.firstOrNull() ?: ""} 도착 ${it.substring(11, 16)}".trim() },
        "탈 수 있는 열차 ${train(trains[0])}",
        trains.getOrNull(1)?.let { "놓치면 ${train(it)}" },
    ).joinToString(" · ")
}
