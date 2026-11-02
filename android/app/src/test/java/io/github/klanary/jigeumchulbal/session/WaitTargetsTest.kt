package io.github.klanary.jigeumchulbal.session

import io.github.klanary.jigeumchulbal.data.remote.ArrivalGroupDto
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaitTargetsTest {
    @Test fun routeWithoutFirstBoardingHasNoWaitStart() = assertNull(waitStartFromRoute(specRoute().copy(firstBoarding = null)))

    @Test fun firstBoardingWithoutStationIdHasNoWaitStart() {
        val route = specRoute()
        assertNull(waitStartFromRoute(route.copy(firstBoarding = route.firstBoarding!!.copy(stationId = null))))
    }

    @Test fun routeWaitStartUsesFirstBoarding() = assertEquals(
        WaitStart(WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null), WaitMode.DEPARTURE_TIMING, 420),
        waitStartFromRoute(specRoute()),
    )

    @Test fun busBeforeBoardingFallsBackToMinutesBefore() {
        val route = specRoute()
        val start = waitStartFromRoute(route.copy(firstBoarding = route.firstBoarding!!.copy(allWalkBeforeBoarding = false)))
        assertEquals(WaitMode.MINUTES_BEFORE, start!!.mode)
    }

    @Test fun stationGroupWaitsMinutesBeforeWithDirection() = assertEquals(
        WaitStart(WaitTarget("홍대입구", "홍대입구", "2호선", "합정", "외선"), WaitMode.MINUTES_BEFORE, 0),
        waitStartFromGroup("홍대입구", ArrivalGroupDto("2호선", "외선", "합정", emptyList())),
    )
}
