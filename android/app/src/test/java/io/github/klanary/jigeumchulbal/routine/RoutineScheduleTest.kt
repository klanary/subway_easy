package io.github.klanary.jigeumchulbal.routine

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutineScheduleTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, kst)
    private val mon = dayBit(DayOfWeek.MONDAY)
    private val fri = dayBit(DayOfWeek.FRIDAY)

    // 2026-11-13은 금요일이다. 490분 = 08:10.

    @Test fun laterTodayIfDayMatches() = assertEquals(at(2026, 11, 13, 8, 10), nextOccurrence(fri, 490, at(2026, 11, 13, 7, 0)))

    @Test fun alreadyPassedTodayGoesToNextMatchingDay() =
        assertEquals(at(2026, 11, 16, 8, 10), nextOccurrence(mon or fri, 490, at(2026, 11, 13, 9, 0)))

    @Test fun sameMinuteIsNextCycle() = assertEquals(at(2026, 11, 20, 8, 10), nextOccurrence(fri, 490, at(2026, 11, 13, 8, 10)))

    @Test fun noDaysNeverFires() = assertNull(nextOccurrence(0, 490, at(2026, 11, 13, 7, 0)))

    @Test fun sundayNightWrapsToMonday() = assertEquals(at(2026, 11, 16, 0, 10), nextOccurrence(mon, 10, at(2026, 11, 15, 23, 55)))

    @Test fun dayBitsAreMondayFirst() {
        assertEquals(1, dayBit(DayOfWeek.MONDAY))
        assertEquals(64, dayBit(DayOfWeek.SUNDAY))
        assertEquals("월 수 금", daysLabel(dayBit(DayOfWeek.MONDAY) or dayBit(DayOfWeek.WEDNESDAY) or fri))
        assertEquals("매일", daysLabel(ALL_DAYS))
    }
}
