package io.github.klanary.jigeumchulbal.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 대기·탑승 진행 중 알림. 소리 없이 알림창에만 둔다(스펙 8.5). */
const val CHANNEL_ONGOING = "session_ongoing"

/** 출발 시각, 하차·환승 알림. 진동과 함께 잠금 화면에도 내용을 보인다(스펙 8.5). */
const val CHANNEL_ALERTS = "session_alerts"

/** 앱 시작 때마다 부른다. 이미 있는 채널은 그대로 두므로 사용자가 바꾼 채널 설정도 유지된다. */
fun createNotificationChannels(context: Context) {
    val ongoing = NotificationChannel(CHANNEL_ONGOING, "진행 중인 통학", NotificationManager.IMPORTANCE_LOW)
    val alerts = NotificationChannel(CHANNEL_ALERTS, "출발·하차 알림", NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(ongoing, alerts))
}
