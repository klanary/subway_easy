package io.github.klanary.jigeumchulbal.ui.common

import java.util.Locale

/** 소요 시간. 분은 올림이고 최소 1분이다(예: 59초 → "1분", 3900초 → "1시간 5분"). */
fun formatDuration(sec: Int): String {
    val minutes = maxOf(1, (sec + 59) / 60)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "${rest}분"
        rest == 0 -> "${hours}시간"
        else -> "${hours}시간 ${rest}분"
    }
}

/** 열차 도착까지 남은 시간. 1분 미만은 "곧 도착", 분은 내림이다(곧 올 열차를 늦게 보이게 하지 않는다). */
fun formatRemaining(sec: Int?): String = when {
    sec == null -> Copy.NO_INFO
    sec < 60 -> Copy.ARRIVING
    else -> "${sec / 60}분"
}

fun formatFare(fare: Int?): String =
    if (fare == null) "요금 정보 없음" else String.format(Locale.KOREA, "%,d원", fare)

fun formatUpdatedAgo(sec: Long): String =
    if (sec < 60) "${sec}초 전 갱신" else "${sec / 60}분 전 갱신"

fun formatDistance(m: Int): String =
    if (m < 1000) "${m}m" else String.format(Locale.US, "%.1fkm", m / 1000.0)
