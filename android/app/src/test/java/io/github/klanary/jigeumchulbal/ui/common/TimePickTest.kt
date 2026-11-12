package io.github.klanary.jigeumchulbal.ui.common

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimePickTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 13, 23, 0, 0, 0, kst) // 금요일 밤

    @Test fun laterTodayStaysToday() =
        assertEquals(ZonedDateTime.of(2026, 11, 13, 23, 30, 0, 0, kst).toInstant(), pickedTime(23, 30, now))

    @Test fun earlierTimeMeansTomorrow() = // 밤에 아침 시각을 고르면 내일 아침이다(리뷰 사소한 문제 2)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 7, 0, 0, 0, kst).toInstant(), pickedTime(7, 0, now))

    @Test fun sameMinuteIsNow() =
        assertEquals(now.toInstant(), pickedTime(23, 0, now))
}
