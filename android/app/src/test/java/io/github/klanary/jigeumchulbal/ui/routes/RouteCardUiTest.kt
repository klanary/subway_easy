package io.github.klanary.jigeumchulbal.ui.routes

import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.ui.common.WALK_COLOR
import io.github.klanary.jigeumchulbal.ui.common.lineColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCardUiTest {
    private val route = specRoute() // 스펙 6.2 예시: 1980초, 환승 1, 1550원, 도보 420 + 2호선 600

    @Test fun cardTexts() {
        val ui = route.toCardUi(receivedAt = 0, now = 0)
        assertEquals(
            listOf("33분", "환승 1회", "1,550원", "2호선 홍대입구 승차 · 다음 열차 3분"),
            listOf(ui.totalText, ui.transfersText, ui.fareText, ui.boardingText),
        )
    }

    @Test fun segmentWeightsAndColors() {
        val s = route.toCardUi(0, 0).segments
        assertEquals(0.41f, s[0].weight, 0.01f)
        assertEquals(0.59f, s[1].weight, 0.01f)
        assertEquals(listOf(WALK_COLOR, lineColor("2호선")), s.map { it.color })
    }

    @Test fun nextTrainCountsDownFromReceivedAt() =
        assertEquals("2호선 홍대입구 승차 · 다음 열차 2분", route.toCardUi(receivedAt = 0, now = 60_000).boardingText)

    @Test fun noFirstBoardingHidesBoardingLine() =
        assertNull(route.copy(firstBoarding = null).toCardUi(0, 0).boardingText)

    @Test fun noNextTrainShowsBoardingOnly() {
        val noTrains = route.copy(firstBoarding = route.firstBoarding!!.copy(nextTrains = null))
        assertEquals("2호선 홍대입구 승차", noTrains.toCardUi(0, 0).boardingText)
    }

    @Test fun cardShowsDepartAndArriveWhenScheduled() {
        val scheduled = route.copy(departAt = "2026-11-13T08:14:00+09:00", arriveAt = "2026-11-13T08:46:30+09:00")
        assertEquals("08:14 출발 · 도착 약 08:46", scheduled.toCardUi(0, 0).boardingText)
    }

    @Test fun lineColorTable() {
        assertEquals(0xFF00A84D, lineColor("2호선"))
        assertEquals(0xFF888888, lineColor("없는선"))
    }
}
