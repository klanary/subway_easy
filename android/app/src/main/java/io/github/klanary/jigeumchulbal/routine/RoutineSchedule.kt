package io.github.klanary.jigeumchulbal.routine

import java.time.DayOfWeek
import java.time.ZonedDateTime

// 루틴 반복 시각 계산(재설계 스펙 5.4). 안드로이드 API 없이 테스트한다.

/** 요일 비트: 월 = 1, 화 = 2, … 일 = 64. */
fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

const val ALL_DAYS = 0b111_1111

private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

/** "월 수 금", 7일 모두면 "매일". */
fun daysLabel(days: Int): String =
    if (days and ALL_DAYS == ALL_DAYS) "매일" else DayOfWeek.entries.filter { days and dayBit(it) != 0 }.joinToString(" ") { DAY_NAMES[it.value - 1] }

/**
 * [after] 이후 처음 오는 실행 시각. 고른 요일의 [minuteOfDay](0~1439분)이다. 같은 분이면 다음 주기다.
 * 요일을 하나도 고르지 않았으면 null.
 */
fun nextOccurrence(days: Int, minuteOfDay: Int, after: ZonedDateTime): ZonedDateTime? {
    if (days and ALL_DAYS == 0) return null
    val start = after.toLocalDate()
    for (offset in 0..7) {
        val date = start.plusDays(offset.toLong())
        if (days and dayBit(date.dayOfWeek) == 0) continue
        val candidate = date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(after.zone)
        if (candidate.isAfter(after)) return candidate
    }
    return null
}

/** "08:10" */
fun minuteLabel(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
