package io.github.klanary.jigeumchulbal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePlanTest {
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)

    private fun waiting(plan: TrainPlan? = TrainPlan(900_000, 1_200_000, "성수"), locked: Long? = null) = SessionState.Waiting(
        target,
        WaitMode.DEPARTURE_TIMING, null, 420, 60, 180, 120, startedAt = 0, deadlineAt = 1_800_000, plan = plan, lockedUntil = locked,
    )

    private fun pt(alight: Long, alert: Long) = AlightPoint("역", false, PointKind.TRANSFER, alight, alert, 1)

    @Test fun restoreRidingSkipsPassedAndFiresDuePoints() {
        val riding = SessionState.Riding(listOf(pt(100_000, 0), pt(700_000, 580_000), pt(1_300_000, 1_180_000)))
        val (state, effects) = planRestore(riding, now = 650_000, onBoot = true)
        assertEquals(1, (state as SessionState.Riding).nextIndex)
        assertEquals(
            listOf(SessionEffect.Schedule(AlarmId.Alight(1), 650_000), SessionEffect.Schedule(AlarmId.Alight(2), 1_180_000)),
            effects.filterIsInstance<SessionEffect.Schedule>(),
        )
        // 건너뛴 지점의 알람이 늦게 울려 지난 역을 알리지 않게 취소한다.
        assertEquals(listOf(SessionEffect.CancelAlarm(AlarmId.Alight(0))), effects.filterIsInstance<SessionEffect.CancelAlarm>())
        assertEquals(SessionEffect.UpdateOngoing, effects.last())
    }

    @Test fun restoreRidingAllPassedEnds() =
        assertEquals(SessionState.Idle, planRestore(SessionState.Riding(listOf(pt(100_000, 0))), now = 1_000_000, onBoot = false).first)

    @Test fun restoreRidingAfterTheLastPointWaitsForTheEnd() {
        val (state, effects) = planRestore(SessionState.Riding(listOf(pt(100_000, 0))), now = 200_000, onBoot = false)
        assertEquals(SessionState.Riding(listOf(pt(100_000, 0)), nextIndex = 1, endAt = 300_000), state)
        assertEquals(listOf(SessionEffect.Schedule(AlarmId.SessionEnd, 300_000), SessionEffect.UpdateOngoing), effects)
    }

    @Test fun restoreWaitingOnBootInterrupts() {
        val (state, effects) = planRestore(waiting(), now = 10_000, onBoot = true)
        assertEquals(SessionState.Idle, state)
        assertEquals(SessionEffect.Alert(AlertContent.WaitingInterrupted), effects.first())
        assertFalse(SessionEffect.StartWaitingService in effects)
        assertEquals(SessionEffect.Finished(EndReason.INTERRUPTED), effects.last())
    }

    @Test fun restoreWaitingAfterDeadlineEnds() =
        assertEquals(SessionEffect.Finished(EndReason.TIMEOUT), planRestore(waiting(), now = 1_800_000, onBoot = false).second.last())

    @Test fun restoreWaitingRestartsServiceAndAlarm() = assertEquals(
        listOf(SessionEffect.StartWaitingService, SessionEffect.Schedule(AlarmId.PreBoarding, 900_000), SessionEffect.UpdateOngoing),
        planRestore(waiting(), now = 10_000, onBoot = false).second,
    )

    @Test fun restoreWaitingWhileLockedOrPastDoesNotReschedule() {
        assertTrue(planRestore(waiting(locked = 1_260_000), now = 10_000, onBoot = false).second.none { it is SessionEffect.Schedule })
        assertTrue(planRestore(waiting(), now = 900_000, onBoot = false).second.none { it is SessionEffect.Schedule })
    }

    @Test fun restoreIdleDoesNothing() = assertEquals(SessionState.Idle to emptyList<SessionEffect>(), planRestore(SessionState.Idle, 0, true))
}
