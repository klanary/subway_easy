package io.github.klanary.jigeumchulbal.session

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 세션 서비스가 지금 상태에서 할 일과 서비스 유형(재설계 스펙 5.5, 7). */
class SessionJobsTest {
    private val target = WaitTarget("광화문", "광화문", "5호선", "서대문", null)
    private val waiting = SessionState.Waiting(target, WaitMode.DEPARTURE_TIMING, null, 420, 60, 180, 120, 0, 1_800_000)
    private val riding = SessionState.Riding(listOf(AlightPoint("공덕", false, PointKind.FINAL, 600_000, 480_000, 3)))

    @Test fun onlyWaitingNeedsTheService() {
        assertEquals(SessionJob.WAIT, sessionJob(waiting))
        assertEquals(SessionJob.NONE, sessionJob(riding))
        assertEquals(SessionJob.NONE, sessionJob(SessionState.Idle))
    }

    @Test fun serviceTypeIsDataSyncOnly() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, sessionServiceType(sdkInt = 34))
        assertEquals(0, sessionServiceType(sdkInt = 28))
    }
}
