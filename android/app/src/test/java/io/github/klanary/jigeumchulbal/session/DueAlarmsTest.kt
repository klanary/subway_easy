package io.github.klanary.jigeumchulbal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DueAlarmsTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)

    private fun waiting(plan: TrainPlan?, locked: Long? = null) = SessionState.Waiting(
        target, WaitMode.MINUTES_BEFORE, null,
        0, 60, 180, 120, 0, 1_800_000, plan = plan, lockedUntil = locked,
    )

    private fun pt(alert: Long) = AlightPoint("역", false, PointKind.TRANSFER, alert + 120_000, alert, 1)

    @Test fun waitingDueOnlyWhenUnlockedAndPast() {
        assertEquals(listOf(AlarmId.PreBoarding), dueAlarms(waiting(TrainPlan(100, 1_000, null)), now = 100))
        assertTrue(dueAlarms(waiting(TrainPlan(100, 1_000, null)), now = 99).isEmpty())
        assertTrue(dueAlarms(waiting(TrainPlan(100, 1_000, null), locked = 61_000), now = 200).isEmpty())
        assertTrue(dueAlarms(waiting(null), now = 200).isEmpty())
    }

    @Test fun ridingFiresMissedPointsInOrderThenEnd() {
        val s = SessionState.Riding(listOf(pt(100), pt(200), pt(900)), nextIndex = 0, endAt = null)
        assertEquals(listOf(AlarmId.Alight(0), AlarmId.Alight(1)), dueAlarms(s, now = 300))
        assertEquals(listOf(AlarmId.Alight(1)), dueAlarms(s.copy(nextIndex = 1), now = 300))
        assertEquals(listOf(AlarmId.SessionEnd), dueAlarms(s.copy(nextIndex = 3, endAt = 1_000), now = 1_000))
        assertTrue(dueAlarms(s.copy(nextIndex = 3, endAt = 1_000), now = 999).isEmpty())
    }

    @Test fun idleHasNothingDue() = assertTrue(dueAlarms(SessionState.Idle, now = 1_000).isEmpty())
}
