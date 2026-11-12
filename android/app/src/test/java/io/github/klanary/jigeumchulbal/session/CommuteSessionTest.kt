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

    private fun waiting(route: RouteDto? = specRoute(), alightAlerts: Boolean = true) = CommuteSession({ now }).also {
        it.handle(
            SessionEvent.StartWaiting(target, WaitMode.DEPARTURE_TIMING, route, travelSec = 420, settings = settings, alightAlerts = alightAlerts),
        )
    }

    @Test fun staleAlightAlarmIsIgnored() {
        val points = listOf(
            AlightPoint("합정", false, PointKind.TRANSFER, 300_000, 180_000, 1, nextLine = "6호선"),
            AlightPoint("당산", false, PointKind.FINAL, 900_000, 780_000, 2),
        )
        val s = CommuteSession({ now }, SessionState.Riding(points, nextIndex = 1))
        assertEquals(emptyList<SessionEffect>(), s.handle(SessionEvent.AlarmFired(AlarmId.Alight(0))))
        assertEquals(1, (s.state as SessionState.Riding).nextIndex)
    }

    @Test fun startWaitingStartsServiceAndSetsDeadline() {
        val s = CommuteSession({ now })
        assertEquals(
            listOf(SessionEffect.StartSessionService, SessionEffect.UpdateOngoing),
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
        assertEquals(SessionEffect.StopSessionService, effects.first())
        assertEquals(500_000L + 600_000L, (s.state as SessionState.Riding).points.single().alightAt) // 스펙 예시: 지하철 600초
    }

    @Test fun boardedWithoutAlightAlertsEndsSession() { // 재설계 스펙 D2: 진동 알림을 켰을 때만 이어 간다
        val s = waiting(alightAlerts = false)
        val effects = s.handle(SessionEvent.Boarded)
        assertEquals(SessionEffect.Finished(EndReason.BOARDED_AT_STATION), effects.last())
        assertTrue(effects.none { it is SessionEffect.Schedule })
        assertEquals(SessionState.Idle, s.state)
    }

    @Test fun boardedWithAlightAlertsStartsRiding() {
        val s = waiting(alightAlerts = true)
        val effects = s.handle(SessionEvent.Boarded)
        assertTrue(s.state is SessionState.Riding)
        assertTrue(SessionEffect.Schedule(AlarmId.Alight(0), (s.state as SessionState.Riding).points[0].alertAt) in effects)
    }

    @Test fun ridingNeverUsesSessionService() { // 재설계 스펙 5.5
        val s = CommuteSession({ now })
        val start = s.handle(SessionEvent.StartRiding(specRoute(), settings))
        assertTrue(start.none { it == SessionEffect.StartSessionService })
        assertTrue(s.handle(SessionEvent.Cancel).none { it == SessionEffect.StopSessionService })
    }

    @Test fun oldWaitingJsonDefaultsToNoAlerts() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString<SessionState>(waiting().state).replace(",\"alightAlerts\":true", "")
        assertFalse(encoded.contains("alightAlerts"))
        assertFalse((json.decodeFromString<SessionState>(encoded) as SessionState.Waiting).alightAlerts)
    }

    @Test fun oldRidingJsonWithTrackingStillLoads() { // 2026-11-05 형식: tracking 칸이 있다
        val old = """{"type":"riding","points":[{"name":"공덕","isBus":false,"kind":"FINAL","alightAt":600000,""" +
            """"alertAt":480000,"stopsToGo":3,"line":"5호선"}],"nextIndex":0,"alightLeadSec":120,""" +
            """"tracking":{"pointIndex":0,"line":"5호선","alightStationId":"공덕","trainNo":"5123","source":"AUTO"}}"""
        val state = Json { ignoreUnknownKeys = true }.decodeFromString<SessionState>(old)
        assertEquals("공덕", (state as SessionState.Riding).points.single().name)
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
