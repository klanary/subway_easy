package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 탑승 중 추적 상태와 탄 열차 정하기(위치 결합 스펙 7.1, 7.2). */
class TrackingSessionTest {
    private var now = 0L
    private val settings = AppSettings()
    private val target = WaitTarget("광화문", "광화문", "5호선", "서대문", null)

    private fun waiting(
        route: RouteDto = line5to6Route(),
        plan: TrainPlan? = TrainPlan(100_000, 400_000, "하남검단산", trainNo = "5123"),
    ) = CommuteSession(
        { now },
        SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, route, 420, 60, 180, 120, 0, 1_800_000, plan = plan),
    )

    private fun CommuteSession.tracking() = (state as SessionState.Riding).tracking

    @Test fun alightPointsCarryStepStations() =
        assertEquals(trackedPoints(), alightPoints(line5to6Route(), boardedAt = 0, from = StartFrom.FIRST_SUBWAY, leadSec = 120))

    @Test fun boardedUsesPlannedTrainAndKeepsService() {
        val s = waiting()
        val effects = s.handle(SessionEvent.Boarded)
        assertEquals(Tracking(0, "5호선", "공덕", "5123", "하남검단산", TrainSource.PLANNED), s.tracking())
        assertFalse(SessionEffect.StopSessionService in effects)
    }

    @Test fun untrackedRouteStopsServiceAsBefore() {
        val s = waiting(route = specRoute()) // 2호선
        assertEquals(SessionEffect.StopSessionService, s.handle(SessionEvent.Boarded).first())
        assertNull(s.tracking())
    }

    @Test fun detectedTrainOverridesPlanAndAlerts() {
        val s = waiting()
        val effects = s.handle(SessionEvent.BoardedDetected(DetectedTrain("5127", "마천")))
        assertEquals("5127" to TrainSource.AUTO, s.tracking()!!.trainNo to s.tracking()!!.source)
        assertTrue(SessionEffect.Alert(AlertContent.TrainDetected("마천")) in effects)
    }

    @Test fun detectedWithoutTrainUsesPlanOrAsksToPick() {
        assertFalse(SessionEffect.Alert(AlertContent.PickTrain) in waiting().handle(SessionEvent.BoardedDetected(null)))
        assertTrue(SessionEffect.Alert(AlertContent.PickTrain) in waiting(plan = null).handle(SessionEvent.BoardedDetected(null)))
    }

    @Test fun manualPickReplacesTrainAndStartsService() {
        val s = waiting()
        s.handle(SessionEvent.Boarded)
        val effects = s.handle(SessionEvent.TrainIdentified(0, "5131", TrainSource.MANUAL, "하남검단산"))
        assertEquals("5131" to TrainSource.MANUAL, s.tracking()!!.trainNo to s.tracking()!!.source)
        assertTrue(SessionEffect.StartSessionService in effects)
        assertTrue(s.handle(SessionEvent.TrainIdentified(1, "6201", TrainSource.MANUAL, null)).isEmpty()) // 다른 단계
    }

    @Test fun startRidingTracksFirstTrackedPointWithoutTrain() {
        val s = CommuteSession({ now })
        assertTrue(SessionEffect.StartSessionService in s.handle(SessionEvent.StartRiding(line5to6Route(), settings)))
        assertEquals(Tracking(0, "5호선", "공덕"), s.tracking())
    }

    @Test fun cancelWhileTrackingStopsService() {
        val s = waiting()
        s.handle(SessionEvent.Boarded)
        assertEquals(SessionEffect.StopSessionService, s.handle(SessionEvent.Cancel).first())
    }

    @Test fun oldSavedRidingStateStillLoads() { // Review Focus 5
        val old = """{"type":"riding","points":[{"name":"공덕","isBus":false,"kind":"FINAL","alightAt":600000,"alertAt":480000,"stopsToGo":4}],"nextIndex":0}"""
        val riding = Json { ignoreUnknownKeys = true }.decodeFromString<SessionState>(old) as SessionState.Riding
        assertNull(riding.tracking)
        assertEquals(120, riding.alightLeadSec)
        assertNull(riding.points.single().line)
    }

    @Test fun restoreRestartsTrackingServiceExceptOnBoot() {
        val riding = SessionState.Riding(trackedPoints(), tracking = Tracking(0, "5호선", "공덕", "5123"))
        assertEquals(SessionEffect.StartSessionService, planRestore(riding, now = 100_000, onBoot = false).second.first())
        assertFalse(SessionEffect.StartSessionService in planRestore(riding, now = 100_000, onBoot = true).second)
    }
}
