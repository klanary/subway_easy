package io.github.klanary.jigeumchulbal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 탄 열차의 남은 시간으로 하차·환승 시각을 맞추기(⑪, 위치 결합 스펙 7.2). 지점은 공덕 600초·삼각지 1,020초, 알림 120초 전. */
class TrackingEtaTest {
    private var now = 0L

    private fun riding(nextIndex: Int = 0, endAt: Long? = null, tracking: Tracking? = Tracking(0, "5호선", "공덕", "5123")) =
        CommuteSession({ now }, SessionState.Riding(trackedPoints(), nextIndex, endAt, alightLeadSec = 120, tracking = tracking))

    private fun CommuteSession.riding() = state as SessionState.Riding

    @Test fun delayOver20sShiftsThisAndLaterPoints() {
        val s = riding()
        now = 100_000
        val effects = s.handle(SessionEvent.TrainEtaUpdated(etaSec = 530, receivedAt = 100_000))
        assertEquals(listOf(630_000L, 1_050_000L), s.riding().points.map { it.alightAt })
        assertEquals(
            listOf(
                SessionEffect.Schedule(AlarmId.Alight(0), 510_000),
                SessionEffect.Schedule(AlarmId.Alight(1), 930_000),
                SessionEffect.UpdateOngoing,
            ),
            effects,
        )
    }

    @Test fun changeWithin20sKeepsTimes() {
        val s = riding()
        now = 100_000
        assertTrue(s.handle(SessionEvent.TrainEtaUpdated(515, 100_000)).isEmpty())
        assertEquals(600_000L, s.riding().points[0].alightAt)
    }

    @Test fun earlierTrainAlertsNoEarlierThanNow() {
        val s = riding()
        now = 500_000
        assertTrue(SessionEffect.Schedule(AlarmId.Alight(0), 500_000) in s.handle(SessionEvent.TrainEtaUpdated(30, 500_000)))
    }

    @Test fun firedPointIsNotRescheduledButCountdownMoves() {
        val s = riding(nextIndex = 1)
        now = 500_000
        val effects = s.handle(SessionEvent.TrainEtaUpdated(160, 500_000))
        assertEquals(660_000L, s.riding().points[0].alightAt)
        assertFalse(effects.any { it is SessionEffect.Schedule && it.id == AlarmId.Alight(0) })
        assertTrue(SessionEffect.Schedule(AlarmId.Alight(1), 960_000) in effects)
    }

    @Test fun sessionEndMovesWithDelay() {
        val s = riding(nextIndex = 2, endAt = 1_200_000, tracking = Tracking(1, "6호선", "삼각지", "6201"))
        now = 1_000_000
        assertTrue(SessionEffect.Schedule(AlarmId.SessionEnd, 1_260_000) in s.handle(SessionEvent.TrainEtaUpdated(80, 1_000_000)))
    }

    @Test fun passedAlightStationMovesToNextLine() {
        val s = riding()
        now = 610_000
        s.handle(SessionEvent.TrainEtaUpdated(null, 610_000))
        assertEquals(Tracking(1, "6호선", "삼각지"), s.riding().tracking)
    }

    @Test fun passingLastTrackedPointStopsService() {
        val s = riding(tracking = Tracking(1, "6호선", "삼각지", "6201"))
        assertTrue(SessionEffect.StopSessionService in s.handle(SessionEvent.TrainEtaUpdated(null, 0)))
        assertNull(s.riding().tracking)
    }

    @Test fun threeFailuresMarkLostThenEtaClearsIt() {
        val s = riding()
        repeat(3) { s.handle(SessionEvent.TrainLookupFailed) }
        assertTrue(s.riding().tracking!!.lost)
        assertEquals(600_000L, s.riding().points[0].alightAt)
        s.handle(SessionEvent.TrainEtaUpdated(600, 0))
        assertFalse(s.riding().tracking!!.lost)
    }

    @Test fun failureAfterAlightTimeMovesOn() {
        val s = riding()
        now = 600_000
        s.handle(SessionEvent.TrainLookupFailed)
        assertEquals(1, s.riding().tracking!!.pointIndex)
    }

    @Test fun etaWithoutIdentifiedTrainIsIgnored() =
        assertTrue(riding(tracking = Tracking(0, "5호선", "공덕")).handle(SessionEvent.TrainEtaUpdated(530, 0)).isEmpty())
}
