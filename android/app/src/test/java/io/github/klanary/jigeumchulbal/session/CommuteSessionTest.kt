package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.TrainDto
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteSessionTest {
    private var now = 0L
    private val settings = AppSettings()
    private fun t(sec: Int) = TrainDto("성수", sec)
    private val target = WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null)

    private fun waiting(route: RouteDto? = specRoute()) = CommuteSession({ now }).also {
        it.handle(SessionEvent.StartWaiting(target, WaitMode.DEPARTURE_TIMING, route, travelSec = 420, settings = settings))
    }

    @Test fun startWaitingStartsServiceAndSetsDeadline() {
        val s = CommuteSession({ now })
        assertEquals(
            listOf(SessionEffect.StartWaitingService, SessionEffect.UpdateOngoing),
            s.handle(SessionEvent.StartWaiting(target, WaitMode.DEPARTURE_TIMING, null, 420, settings)),
        )
        assertEquals(1_800_000L, (s.state as SessionState.Waiting).deadlineAt)
    }

    @Test fun arrivalsScheduleDepartureAlarm() =
        assertTrue(
            SessionEffect.Schedule(AlarmId.PreBoarding, 120_000) in
                waiting().handle(SessionEvent.ArrivalsUpdated(listOf(t(300), t(600)), receivedAt = 0)),
        )

    @Test fun noCatchableTrainCancelsAlarm() {
        val s = waiting()
        s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        assertTrue(SessionEffect.CancelAlarm(AlarmId.PreBoarding) in s.handle(SessionEvent.ArrivalsUpdated(listOf(t(100)), 0)))
        assertNull((s.state as SessionState.Waiting).plan)
    }

    @Test fun departureAlarmLocksPlanUntilTrainArrivalPlus60s() {
        val s = waiting()
        s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        now = 120_000
        assertEquals(
            SessionEffect.Alert(AlertContent.LeaveNow(8, "성수")),
            s.handle(SessionEvent.AlarmFired(AlarmId.PreBoarding)).first(),
        )
        now = 150_000
        assertEquals(listOf(SessionEffect.UpdateOngoing), s.handle(SessionEvent.ArrivalsUpdated(listOf(t(450), t(750)), now)))
    }

    @Test fun missedTrainReplansOnceAfterLock() {
        val s = waiting()
        s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        now = 120_000
        s.handle(SessionEvent.AlarmFired(AlarmId.PreBoarding))
        now = 660_001
        val effects = s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), now))
        assertEquals(
            listOf(
                SessionEffect.Alert(AlertContent.Replanned),
                SessionEffect.Schedule(AlarmId.PreBoarding, 780_001),
                SessionEffect.UpdateOngoing,
            ),
            effects,
        )
        assertFalse(SessionEffect.Alert(AlertContent.Replanned) in s.handle(SessionEvent.ArrivalsUpdated(listOf(t(570)), now + 30_000)))
    }

    @Test fun threeFailuresAreCounted() {
        val s = waiting()
        repeat(3) { s.handle(SessionEvent.ArrivalsFailed) }
        assertEquals(3, (s.state as SessionState.Waiting).failures)
    }

    @Test fun successResetsFailuresEvenWhileLocked() {
        val s = waiting()
        s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        now = 120_000
        s.handle(SessionEvent.AlarmFired(AlarmId.PreBoarding))
        repeat(2) { s.handle(SessionEvent.ArrivalsFailed) }
        s.handle(SessionEvent.ArrivalsUpdated(listOf(t(450)), now))
        assertEquals(0, (s.state as SessionState.Waiting).failures)
    }

    @Test fun boardedFromRouteStartsRidingFromFirstSubway() {
        val s = waiting()
        now = 500_000
        val effects = s.handle(SessionEvent.Boarded)
        assertEquals(SessionEffect.StopWaitingService, effects.first())
        assertEquals(500_000L + 600_000L, (s.state as SessionState.Riding).points.single().alightAt) // 스펙 예시: 지하철 600초
    }

    @Test fun boardedFromStationEnds() {
        val s = waiting(route = null)
        assertEquals(SessionEffect.Finished(EndReason.BOARDED_AT_STATION), s.handle(SessionEvent.Boarded).last())
        assertEquals(SessionState.Idle, s.state)
    }

    @Test fun timeoutAndCancelEnd() {
        assertEquals(SessionEffect.Finished(EndReason.TIMEOUT), waiting().handle(SessionEvent.Timeout).last())
        assertEquals(SessionEffect.Finished(EndReason.CANCELLED), waiting().handle(SessionEvent.Cancel).last())
    }

    @Test fun ridingAlertsEachPointThenEndsFiveMinutesAfterLast() {
        val s = CommuteSession({ now })
        s.handle(SessionEvent.StartRiding(specRoute(), settings))
        now = 480_000
        val effects = s.handle(SessionEvent.AlarmFired(AlarmId.Alight(0)))
        assertTrue(SessionEffect.Schedule(AlarmId.SessionEnd, 780_000) in effects)
        now = 780_000
        assertEquals(SessionEffect.Finished(EndReason.ARRIVED), s.handle(SessionEvent.AlarmFired(AlarmId.SessionEnd)).last())
    }

    @Test fun unrelatedEventsAreIgnored() = assertTrue(CommuteSession({ now }).handle(SessionEvent.Boarded).isEmpty())

    @Test fun stateRoundTripsThroughJson() {
        val s = waiting()
        s.handle(SessionEvent.ArrivalsUpdated(listOf(t(600)), 0))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(s.state, json.decodeFromString<SessionState>(json.encodeToString<SessionState>(s.state)))
    }
}
