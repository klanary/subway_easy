package io.github.klanary.jigeumchulbal.ui.common

import java.time.Instant
import java.time.ZonedDateTime

/**
 * 시각 선택 대화상자에서 고른 시:분을 실제 시각으로. 지금보다 이른 시각이면 내일이다
 * (밤에 내일 아침 시각을 고르면 이미 지난 오늘 아침이 아니라 내일 아침 열차를 본다).
 */
fun pickedTime(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Instant {
    val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    val nowMinute = now.withSecond(0).withNano(0)
    return (if (today.isBefore(nowMinute)) today.plusDays(1) else today).toInstant()
}
