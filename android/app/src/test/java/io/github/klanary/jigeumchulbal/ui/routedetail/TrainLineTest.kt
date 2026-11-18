package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
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

    // 열차 고르기 줄(열차 고르기 설계 §5.3): 이전/다음 버튼 사이라 "열차"는 붙이지 않는다.
    private fun candidate(no: String? = "5301", express: Boolean = false, estimated: Boolean = false) = CandidateDto(
        trainNo = no, departsAt = "2026-11-18T08:05:00+09:00", arrivesAt = "2026-11-18T08:09:00+09:00",
        isExpress = express, estimated = estimated,
    )

    @Test fun pickLineFormatsCandidate() {
        assertEquals("08:05 출발 → 08:09 도착 · 5301", pickLine(candidate()))
        assertEquals("08:05 출발 → 08:09 도착 · 5301 · 급행", pickLine(candidate(express = true)))
        assertEquals("08:05 출발 → 08:09 도착 (어림)", pickLine(candidate(no = null, estimated = true)))
    }
}
