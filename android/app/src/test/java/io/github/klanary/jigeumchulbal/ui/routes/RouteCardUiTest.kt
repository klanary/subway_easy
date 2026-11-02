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
            listOf("33분", "환승 1회", "1,550원", "다음 열차 3분"),
            listOf(ui.totalText, ui.transfersText, ui.fareText, ui.nextTrainText),
        )
    }

    @Test fun segmentWeightsAndColors() {
        val s = route.toCardUi(0, 0).segments
        assertEquals(0.41f, s[0].weight, 0.01f)
        assertEquals(0.59f, s[1].weight, 0.01f)
        assertEquals(listOf(WALK_COLOR, lineColor("2호선")), s.map { it.color })
    }

    @Test fun nextTrainCountsDownFromReceivedAt() =
        assertEquals("다음 열차 2분", route.toCardUi(receivedAt = 0, now = 60_000).nextTrainText)

    @Test fun noFirstBoardingHidesNextTrain() =
        assertNull(route.copy(firstBoarding = null).toCardUi(0, 0).nextTrainText)

    @Test fun lineColorTable() {
        assertEquals(0xFF00A84D, lineColor("2호선"))
        assertEquals(0xFF888888, lineColor("없는선"))
    }
}
