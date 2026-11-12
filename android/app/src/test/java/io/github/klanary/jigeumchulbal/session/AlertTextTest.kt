package io.github.klanary.jigeumchulbal.session

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertTextTest {
    @Test fun leaveNow() = assertEquals("지금 출발하세요" to "8분 뒤 성수행 열차", alertText(AlertContent.LeaveNow(8, "성수")))

    @Test fun leaveNowWithoutDestination() = assertEquals("8분 뒤 열차", alertText(AlertContent.LeaveNow(8, null)).second)

    @Test fun trainSoon() = assertEquals("열차가 곧 도착해요" to "3분 뒤 성수행 열차", alertText(AlertContent.TrainSoon(3, "성수")))

    @Test fun transferAlight() = assertEquals(
        "곧 시청역이에요" to "1호선 환승 준비를 하세요",
        alertText(AlertContent.AlightSoon(AlightPoint("시청", false, PointKind.TRANSFER, 0, 0, 3, nextLine = "1호선"))),
    )

    @Test fun transferWithoutNextLine() = assertEquals(
        "환승 준비를 하세요",
        alertText(AlertContent.AlightSoon(AlightPoint("시청", false, PointKind.TRANSFER, 0, 0, 3))).second,
    )

    @Test fun busFinalAlight() = assertEquals(
        "곧 신촌오거리 정류장이에요" to "내릴 준비를 하세요",
        alertText(AlertContent.AlightSoon(AlightPoint("신촌오거리", true, PointKind.FINAL, 0, 0, 4))),
    )

    @Test fun viaAlight() = assertEquals(
        "곧 강남역이에요" to "내릴 준비를 하세요",
        alertText(AlertContent.AlightSoon(AlightPoint("강남", false, PointKind.VIA, 0, 0, 2))),
    )

    @Test fun replanned() = assertEquals(
        "다음 열차 기준으로 다시 안내할게요" to "출발할 시각이 되면 다시 알려드려요",
        alertText(AlertContent.Replanned),
    )

    @Test fun waitingInterrupted() = assertEquals(
        "대기 알림이 중단됐어요" to "기기가 다시 켜져 승차 전 알림을 멈췄어요. 필요하면 다시 시작해 주세요",
        alertText(AlertContent.WaitingInterrupted),
    )

}
