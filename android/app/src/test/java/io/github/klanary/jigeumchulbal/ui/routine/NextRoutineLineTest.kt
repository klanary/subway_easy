package io.github.klanary.jigeumchulbal.ui.routine

import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.routine.dayBit
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextRoutineLineTest {
    private fun r(minute: Int, day: DayOfWeek = DayOfWeek.FRIDAY, enabled: Boolean = true) = RoutineEntity(
        name = "r", fromId = "마포", fromName = "마포", viaId = null, viaName = null, toId = "공덕", toName = "공덕",
        routeJson = "{}", days = dayBit(day), minuteOfDay = minute, enabled = enabled,
    )

    @Test fun earliestUpcomingEnabledRoutineToday() =
        assertEquals("08:10 마포 → 공덕", nextRoutineLine(listOf(r(1000), r(490), r(300), r(480, enabled = false)), 420, DayOfWeek.FRIDAY))

    @Test fun noneLeftToday() = assertNull(nextRoutineLine(listOf(r(490), r(500, DayOfWeek.MONDAY)), 600, DayOfWeek.FRIDAY))
}
