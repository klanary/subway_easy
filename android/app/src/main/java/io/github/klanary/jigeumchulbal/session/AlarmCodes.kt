package io.github.klanary.jigeumchulbal.session

// 알람 하나하나를 PendingIntent로 구분하는 요청 코드와, 인텐트에 실어 보내는 알람 이름.

/** 한 세션의 하차·환승 지점 최대 개수. 요청 코드 범위(2000~2049)와 cancelAll이 이 값을 쓴다. */
const val MAX_ALIGHT_POINTS = 50

const val ACTION_ALARM = "io.github.klanary.jigeumchulbal.ALARM"
const val EXTRA_ALARM_ID = "alarm_id"

/** 알람마다 고유한 요청 코드(스펙 8.4). 같은 코드로 다시 예약하면 이전 예약을 덮어쓴다. */
fun AlarmId.requestCode(): Int = when (this) {
    AlarmId.PreBoarding -> 1000
    is AlarmId.Alight -> 2000 + index
    AlarmId.SessionEnd -> 3000
}

fun AlarmId.encode(): String = when (this) {
    AlarmId.PreBoarding -> "PRE_BOARDING"
    is AlarmId.Alight -> "ALIGHT:$index"
    AlarmId.SessionEnd -> "SESSION_END"
}

fun decodeAlarmId(value: String?): AlarmId? = when {
    value == null -> null
    value == "PRE_BOARDING" -> AlarmId.PreBoarding
    value == "SESSION_END" -> AlarmId.SessionEnd
    value.startsWith("ALIGHT:") ->
        value.removePrefix("ALIGHT:").toIntOrNull()?.takeIf { it in 0 until MAX_ALIGHT_POINTS }?.let { AlarmId.Alight(it) }
    else -> null
}
