package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 구간마다 운행표로 고른 열차(경로 설계 2026-11-18): "08:03 출발 → 08:17 도착 · 5101 열차". */
class TrainLineTest {
    private fun step(
        trainNo: String? = "5101",
        express: Boolean = false,
        estimated: Boolean = false,
        departsAt: String? = "2026-11-18T08:03:00+09:00",
    ) = StepDto(
        mode = StepMode.SUBWAY, durationSec = 840, distanceM = 0, lineName = "5호선",
        stops = listOf("마포", "공덕"), departsAt = departsAt, arrivesAt = "2026-11-18T08:17:00+09:00",
        trainNo = trainNo, isExpress = express, estimated = estimated,
    )

    @Test fun showsDepartureArrivalAndTrain() =
        assertEquals("08:03 출발 → 08:17 도착 · 5101 열차", trainLine(step()))

    @Test fun marksExpress() =
        assertEquals("08:03 출발 → 08:17 도착 · 5101 열차 · 급행", trainLine(step(express = true)))

    @Test fun marksEstimatedWithoutTrainNo() =
        assertEquals("08:03 출발 → 08:17 도착 (어림)", trainLine(step(trainNo = null, estimated = true)))

    @Test fun noTimesNoLine() = assertNull(trainLine(step(departsAt = null)))
}
