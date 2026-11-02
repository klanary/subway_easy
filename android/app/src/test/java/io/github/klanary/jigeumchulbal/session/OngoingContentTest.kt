package io.github.klanary.jigeumchulbal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OngoingContentTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)

    private fun waiting(
        plan: TrainPlan?,
        failures: Int = 0,
        mode: WaitMode = WaitMode.DEPARTURE_TIMING,
        updatedAt: Long? = 0,
        target: WaitTarget = this.target,
    ) = SessionState.Waiting(
        target, mode, null, 420, 60, 180, 120, startedAt = 0, deadlineAt = 1_800_000,
        plan = plan, failures = failures, nextTrainSec = 240, updatedAt = updatedAt,
    )

    private fun point(name: String, alightAt: Long, stops: Int = 2) =
        AlightPoint(name, false, PointKind.FINAL, alightAt = alightAt, alertAt = alightAt - 120_000, stopsToGo = stops)

    @Test fun waitingDepartureText() {
        val c = ongoingContent(waiting(TrainPlan(420_000, 900_000, "성수")), now = 0, exactAlarms = true)!!
        assertEquals("홍대입구 2호선 합정 방면" to "다음 열차 4분 · 7분 뒤 출발", c.title to c.text)
        assertEquals(listOf(SessionAction.BOARDED, SessionAction.CANCEL), c.actions)
        assertNull(c.subText)
        assertNull(c.countdownTo)
    }

    @Test fun waitingWithoutPlan() = assertEquals("탈 수 있는 열차 정보가 없어요", ongoingContent(waiting(null), 0, true)!!.text)

    @Test fun threeFailuresShowDisconnected() =
        assertEquals("실시간 정보 연결 끊김", ongoingContent(waiting(TrainPlan(420_000, 900_000, null), failures = 3), 0, true)!!.text)

    @Test fun beforeTheFirstUpdateSaysLoading() =
        assertEquals("열차 정보를 불러오고 있어요", ongoingContent(waiting(null, updatedAt = null), 0, true)!!.text)

    @Test fun departureTimePassedSaysLeaveNow() =
        assertEquals(
            "다음 열차 4분 · 지금 출발하세요",
            ongoingContent(waiting(TrainPlan(420_000, 900_000, "성수")), now = 420_000, exactAlarms = true)!!.text,
        )

    @Test fun minutesBeforeText() =
        assertEquals(
            "다음 열차 4분 · 3분 전에 알려드려요",
            ongoingContent(waiting(TrainPlan(60_000, 240_000, "성수"), mode = WaitMode.MINUTES_BEFORE), 0, true)!!.text,
        )

    @Test fun titleWithoutTowards() =
        assertEquals(
            "홍대입구 2호선",
            ongoingContent(waiting(null, target = target.copy(towards = null)), 0, true)!!.title,
        )

    @Test fun ridingCountdown() {
        val p = AlightPoint("홍대입구", false, PointKind.FINAL, alightAt = 300_000, alertAt = 180_000, stopsToGo = 2)
        val c = ongoingContent(SessionState.Riding(listOf(p)), now = 0, exactAlarms = false)!!
        assertEquals(listOf("홍대입구역까지 2정거장", "약 5분", "알림이 늦을 수 있어요"), listOf(c.title, c.text, c.subText))
        assertEquals(300_000L, c.countdownTo)
        assertEquals(listOf(SessionAction.CANCEL), c.actions)
    }

    @Test fun ridingShowsTheNextPointThenTheLast() {
        val points = listOf(point("신도림", 300_000, stops = 3), point("홍대입구", 900_000, stops = 5))
        assertEquals("홍대입구역까지 5정거장", ongoingContent(SessionState.Riding(points, nextIndex = 1), 0, true)!!.title)
        assertEquals("홍대입구역까지 5정거장", ongoingContent(SessionState.Riding(points, nextIndex = 2), 0, true)!!.title)
    }

    @Test fun ridingPastTheAlightTimeStopsTheCountdown() {
        val c = ongoingContent(SessionState.Riding(listOf(point("홍대입구", 300_000)), nextIndex = 1), now = 300_000, exactAlarms = true)!!
        assertEquals("곧 도착해요", c.text)
        assertNull(c.countdownTo)
    }

    @Test fun idleHasNoOngoingNotification() = assertNull(ongoingContent(SessionState.Idle, 0, true))
}
