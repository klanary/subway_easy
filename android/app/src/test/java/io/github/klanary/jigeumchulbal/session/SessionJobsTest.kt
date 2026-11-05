package io.github.klanary.jigeumchulbal.session

import android.content.pm.ServiceInfo
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 세션 서비스가 지금 상태에서 할 일과 서비스 유형(위치 결합 스펙 7.3). */
class SessionJobsTest {
    private val target = WaitTarget("광화문", "광화문", "5호선", "서대문", null)

    private fun waiting(route: RouteDto?) =
        SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, route, 420, 60, 180, 120, 0, 1_800_000)

    private fun riding(tracking: Tracking?) = SessionState.Riding(trackedPoints(), tracking = tracking)

    @Test fun jobFromState() {
        assertEquals(SessionJob.WAIT_AND_DETECT, sessionJob(waiting(line5to6Route()), canDetect = true))
        assertEquals(SessionJob.WAIT, sessionJob(waiting(line5to6Route()), canDetect = false))
        assertEquals(SessionJob.WAIT, sessionJob(waiting(null), canDetect = true))
        assertEquals(SessionJob.TRACK, sessionJob(riding(Tracking(0, "5호선", "공덕", "5123")), true))
        assertEquals(SessionJob.DETECT, sessionJob(riding(Tracking(0, "5호선", "공덕")), true))
        assertEquals(SessionJob.NONE, sessionJob(riding(Tracking(0, "5호선", "공덕")), false))
        assertEquals(SessionJob.NONE, sessionJob(riding(null), true))
        assertEquals(SessionJob.NONE, sessionJob(SessionState.Idle, true))
    }

    @Test fun serviceTypeWithoutLocationPermission() { // Review Focus 4
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, sessionServiceType(hasLocationPermission = false, sdkInt = 34))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            sessionServiceType(true, 34),
        )
        assertEquals(0, sessionServiceType(true, 28))
    }

    @Test fun jobsRunUntilNothingLeft() = runTest {
        var state: SessionState = waiting(line5to6Route())
        val ran = mutableListOf<String>()
        runSessionJobs(
            { state },
            { false },
            wait = { ran += "wait"; state = riding(Tracking(0, "5호선", "공덕", "5123")) },
            track = { ran += "track"; state = SessionState.Idle },
            detect = { error("감지하지 않는다") },
        )
        assertEquals(listOf("wait", "track"), ran)
    }
}
