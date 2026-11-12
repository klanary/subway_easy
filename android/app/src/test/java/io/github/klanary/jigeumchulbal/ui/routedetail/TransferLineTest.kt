package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.data.remote.StepDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.data.remote.TransferTrainDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferLineTest {
    private fun step(trains: List<TransferTrainDto>?) = StepDto(
        mode = StepMode.SUBWAY, durationSec = 330, distanceM = 0, lineName = "3호선",
        stops = listOf("을지로3가", "종로3가", "안국"), boardAfter = "2026-11-13T08:15:58+09:00", transferTrains = trains,
    )

    @Test fun showsCatchableAndNextTrain() = assertEquals(
        "을지로3가 도착 08:15 · 탈 수 있는 열차 08:18 대화행 · 놓치면 08:21 구파발행",
        transferLine(step(listOf(TransferTrainDto("2026-11-13T08:18:00+09:00", "대화"), TransferTrainDto("2026-11-13T08:21:00+09:00", "구파발")))),
    )

    @Test fun oneTrainOnly() = assertEquals(
        "을지로3가 도착 08:15 · 탈 수 있는 열차 08:18 대화행",
        transferLine(step(listOf(TransferTrainDto("2026-11-13T08:18:00+09:00", "대화")))),
    )

    @Test fun noTransferInfo() = assertNull(transferLine(step(null)))
}
