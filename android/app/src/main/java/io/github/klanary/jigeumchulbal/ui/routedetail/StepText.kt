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
            if (stops.size >= 2) "$line ${stops.first()} → ${stops.last()} · ${rides}정거장" else line
        }
        StepMode.BUS -> {
            val bus = "${step.routeName ?: ""}번 버스"
            if (stops.size >= 2) "$bus · ${rides}정거장" else bus
        }
    }
}
