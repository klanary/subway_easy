package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.CandidateDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 경로 상세 단계 줄·열차 고르기 줄을 큰 글과 작은 글로 나눈다(Task 7 합의). */
class StepPartsTest {
    private val subway = StepDto(
        mode = StepMode.SUBWAY, durationSec = 240, distanceM = 0, lineName = "5호선", stops = listOf("마포", "공덕", "애오개"),
    )

    @Test fun subwayHeadAndDetail() {
        assertEquals("마포 → 애오개", stepHead(subway))
        assertEquals("공덕 방면 · 2정거장 · 4분", stepDetail(subway))
    }

    @Test fun walkKeepsTitle() {
        val walk = StepDto(mode = StepMode.WALK, durationSec = 60, distanceM = 80)
        assertEquals("도보 1분", stepHead(walk))
        assertEquals(null, stepDetail(walk))
    }

    @Test fun pickTimesAndMeta() {
        val t = CandidateDto(trainNo = "9501", departsAt = "2026-11-18T08:20:00+09:00", arrivesAt = "2026-11-18T08:24:00+09:00", isExpress = true)
        assertEquals("08:20 → 08:24", pickTimes(t))
        assertEquals("9501 · 급행", pickMeta(t))
    }
}
