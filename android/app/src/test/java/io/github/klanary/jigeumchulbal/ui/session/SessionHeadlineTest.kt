package io.github.klanary.jigeumchulbal.ui.session

import io.github.klanary.jigeumchulbal.session.AlightPoint
import io.github.klanary.jigeumchulbal.session.PointKind
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.TrainPlan
import io.github.klanary.jigeumchulbal.session.WaitMode
import io.github.klanary.jigeumchulbal.session.WaitTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionHeadlineTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)
    private val p = AlightPoint("홍대입구", false, PointKind.FINAL, alightAt = 300_000, alertAt = 180_000, stopsToGo = 2)

    private fun waiting(plan: TrainPlan?, mode: WaitMode = WaitMode.DEPARTURE_TIMING, updatedAt: Long? = 0) =
        SessionState.Waiting(target, mode, null, 420, 60, 180, 120, 0, 1_800_000, plan = plan, updatedAt = updatedAt)

    @Test fun waitingHeadline() {
        val s = SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, null, 420, 60, 180, 120, 0, 1_800_000,
            plan = TrainPlan(120_000, 600_000, "성수"))
        assertEquals("지금 출발하면 10분 뒤 성수행 열차를 탈 수 있어요", sessionHeadline(s, now = 0))
    }

    @Test fun waitingWithoutDestination() =
        assertEquals("지금 출발하면 10분 뒤 열차를 탈 수 있어요", sessionHeadline(waiting(TrainPlan(120_000, 600_000, null)), 0))

    @Test fun stationWaitingSaysWhenTheTrainComes() = assertEquals(
        "4분 뒤 성수행 열차가 도착해요",
        sessionHeadline(waiting(TrainPlan(60_000, 240_000, "성수"), WaitMode.MINUTES_BEFORE), 0),
    )

    @Test fun trainAlreadyDue() =
        assertEquals("열차가 곧 도착해요", sessionHeadline(waiting(TrainPlan(60_000, 240_000, "성수")), now = 240_000))

    @Test fun waitingWithoutPlan() = assertEquals("탈 수 있는 열차 정보가 없어요", sessionHeadline(waiting(null), 0))

    @Test fun waitingBeforeTheFirstUpdate() =
        assertEquals("열차 정보를 불러오고 있어요", sessionHeadline(waiting(null, updatedAt = null), 0))

    @Test fun ridingHeadline() =
        assertEquals("홍대입구역까지 2정거장, 약 5분", sessionHeadline(SessionState.Riding(listOf(p)), now = 0))

    @Test fun ridingPastTheAlightTime() =
        assertEquals("홍대입구역까지 2정거장, 곧 도착해요", sessionHeadline(SessionState.Riding(listOf(p), nextIndex = 1), now = 300_000))

    @Test fun idleHasNoHeadline() = assertNull(sessionHeadline(SessionState.Idle, 0))
}
