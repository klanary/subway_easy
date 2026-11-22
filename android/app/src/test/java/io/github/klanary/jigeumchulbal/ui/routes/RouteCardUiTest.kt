package io.github.klanary.jigeumchulbal.ui.routes

import io.github.klanary.jigeumchulbal.data.remote.LegDto
import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.remote.TransferTrainDto
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.ui.common.WALK_COLOR
import io.github.klanary.jigeumchulbal.ui.common.lineColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCardUiTest {
    private val route = specRoute() // 스펙 6.2 예시: 1980초, 환승 1(요금은 2026-11-18부터 보이지 않음), 도보 420 + 2호선 600

    @Test fun cardTexts() {
        val ui = route.toCardUi(receivedAt = 0, now = 0)
        assertEquals(
            listOf("33분", "환승 1회", "2호선 홍대입구 승차 · 다음 열차 3분"),
            listOf(ui.totalText, ui.transfersText, ui.boardingText),
        )
    }

    /** 경로 카드에 탈 호선을 순서대로(Task 6 합의). 도보는 빼고, 같은 호선이 이어지면 한 번만. */
    @Test fun ridesInOrder() = assertEquals(listOf("2호선"), route.toCardUi(0, 0).lines)

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

    @Test fun transferTrainsOnCard() {
        val transfer = StepDto(
            mode = StepMode.SUBWAY, durationSec = 330, distanceM = 0, lineName = "3호선",
            stops = listOf("을지로3가", "종로3가"), boardAfter = "2026-11-13T08:15:58+09:00",
            transferTrains = listOf(TransferTrainDto("2026-11-13T08:18:00+09:00", "대화"), TransferTrainDto("2026-11-13T08:21:00+09:00", "구파발")),
        )
        val withTransfer = route.copy(legs = listOf(LegDto(route.legs[0].steps + transfer)))
        assertEquals("을지로3가 환승 08:18 · 그다음 08:21", withTransfer.toCardUi(0, 0).transferText)
        assertNull(route.toCardUi(0, 0).transferText)
    }

    // 경로 카드에 호선·환승 시간을 나눠 적고 합친다(수동 검토 2026-11-22)
    private fun s(mode: StepMode, sec: Int, line: String? = null) = StepDto(mode = mode, durationSec = sec, distanceM = 0, lineName = line)

    @Test fun breakdownSumsLinesAndTransfers() {
        val r = route.copy(
            totalSec = 1920,
            legs = listOf(LegDto(steps = listOf(
                s(StepMode.WALK, 120), s(StepMode.SUBWAY, 600, "2호선"), s(StepMode.WALK, 240),
                s(StepMode.SUBWAY, 900, "5호선"), s(StepMode.WALK, 60),
            ))),
        )
        val ui = r.toCardUi(0, 0)
        assertEquals("도보 2분 + 2호선 10분 + 환승 4분 + 5호선 15분 + 도보 1분", ui.breakdownText)
        assertEquals("32분", ui.totalText)
    }

    @Test fun waitingShownWhenTotalIsLonger() {
        val r = route.copy(totalSec = 1500, legs = listOf(LegDto(steps = listOf(s(StepMode.SUBWAY, 900, "2호선")))))
        assertEquals("2호선 15분 + 대기 10분", r.toCardUi(0, 0).breakdownText)
        assertEquals("25분", r.toCardUi(0, 0).totalText)
    }

    /** 지하철끼리 걷기 단계 없이 이어지면, 앞 열차 도착 ~ 다음 열차 출발을 환승 시간으로 적는다. */
    @Test fun transferGapFromTrainTimes() {
        fun t(hhmm: String) = "2026-11-22T$hhmm:00+09:00"
        val r = route.copy(
            totalSec = 4380,
            legs = listOf(LegDto(steps = listOf(
                StepDto(mode = StepMode.SUBWAY, durationSec = 3060, distanceM = 0, lineName = "5호선", departsAt = t("17:16"), arrivesAt = t("18:07")),
                StepDto(mode = StepMode.SUBWAY, durationSec = 1140, distanceM = 0, lineName = "1호선", departsAt = t("18:11"), arrivesAt = t("18:30")),
            ))),
        )
        assertEquals("5호선 51분 + 환승 4분 + 1호선 19분", r.toCardUi(0, 0).breakdownText)
        assertEquals("1시간 14분", r.toCardUi(0, 0).totalText)
    }
}
