package io.github.klanary.jigeumchulbal.ui.home

import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.routine.dayBit
import io.github.klanary.jigeumchulbal.ui.RouteFlowGraph
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/** 홈 아래 패널의 오늘 루틴(2차 다듬기 Task 3). */
class TodayRoutinesTest {
    private val mon = DayOfWeek.MONDAY
    private fun r(id: Long, min: Int, days: Int = dayBit(mon), on: Boolean = true, viaId: String? = null) = RoutineEntity(
        id = id, name = "r$id", fromId = "마포", fromName = "마포", viaId = viaId, viaName = viaId,
        toId = "공덕", toName = "공덕", routeJson = "", days = days, minuteOfDay = min, enabled = on,
    )

    @Test fun onlyEnabledTodayNotPassedInTimeOrder() = assertEquals(
        listOf(3L, 1L),
        todayRoutines(
            listOf(r(1, 600), r(2, 400), r(3, 500), r(4, 700, on = false), r(5, 800, days = dayBit(DayOfWeek.TUESDAY))),
            nowMinute = 450,
            today = mon,
        ).map { it.id },
    )

    @Test fun limitsToTwo() = assertEquals(2, todayRoutines((1L..5L).map { r(it, 600 + it.toInt()) }, 0, mon).size)

    @Test fun routineWithViaOpensRouteWithVia() = assertEquals(
        RouteFlowGraph(fromId = "마포", fromName = "마포", viaId = "홍대입구", viaName = "홍대입구", toId = "공덕", toName = "공덕"),
        r(1, 600, viaId = "홍대입구").toRouteGraph(),
    )
}
