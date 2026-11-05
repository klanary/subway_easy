package io.github.klanary.jigeumchulbal.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import io.github.klanary.jigeumchulbal.R
import io.github.klanary.jigeumchulbal.ui.common.Copy
import java.util.concurrent.atomic.AtomicInteger

/** 진행 중 알림은 이 id 하나를 계속 고쳐 쓴다. 대기 서비스도 같은 id로 포그라운드 알림을 띄운다. */
const val ONGOING_NOTIFICATION_ID = 1

/** 출발·하차 알림은 id 100부터 최근 50개를 돌려 쓴다. */
const val ALERT_ID_BASE = 100
const val ALERT_SLOTS = 50

/**
 * 세션 알림을 안드로이드 알림으로 보인다(스펙 8.2~8.5). 알림을 누르면 통학 세션 화면이 열리고,
 * 진행 중 알림의 버튼은 [SessionActionReceiver]로 간다.
 * 알림 권한이 없으면 시스템이 알림을 조용히 버린다(그런 세션은 앱 안 모드라 여기까지 오지 않는다).
 */
class SessionNotifier(
    private val context: Context,
    private val scheduler: AlarmScheduler,
) : SessionNotifierPort {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alertCount = AtomicInteger()

    override fun showOngoing(state: SessionState, now: Long) {
        val content = ongoingContent(state, now, scheduler.canScheduleExact())
        if (content == null) clearOngoing() else manager.notify(ONGOING_NOTIFICATION_ID, ongoing(content))
    }

    override fun clearOngoing() = manager.cancel(ONGOING_NOTIFICATION_ID)

    override fun alert(content: AlertContent) {
        val (title, text) = alertText(content)
        val builder = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // 탄 열차를 모를 때는 누르면 바로 고르기 목록을 연다(위치 결합 스펙 7.1).
            .setContentIntent(if (content == AlertContent.PickTrain) pickTrainPendingIntent(context) else openSessionPendingIntent(context))
            .setAutoCancel(true)
        if (content is AlertContent.TrainDetected) {
            // 자동 감지가 틀렸을 때 바로 고칠 수 있게 [다른 열차였어요]를 단다.
            val icon = Icon.createWithResource(context, R.drawable.ic_notification)
            builder.addAction(Notification.Action.Builder(icon, Copy.OTHER_TRAIN, pickTrainPendingIntent(context)).build())
        }
        manager.notify(ALERT_ID_BASE + alertCount.getAndIncrement() % ALERT_SLOTS, builder.build())
    }

    /** 대기 서비스가 startForeground에 쓴다. 세션이 그새 끝났어도 띄울 알림은 있어야 한다. */
    fun foregroundNotification(state: SessionState, now: Long): Notification = ongoing(
        ongoingContent(state, now, scheduler.canScheduleExact())
            ?: OngoingContent("통학 알림", "알림을 정리하고 있어요", null, null, emptyList()),
    )

    private fun ongoing(content: OngoingContent): Notification {
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSessionPendingIntent(context))
        content.countdownTo?.let {
            // 시스템이 남은 시간을 직접 세므로 앱이 꺼져 있어도 갱신된다(스펙 8.3).
            builder.setWhen(it).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        content.actions.forEach { action ->
            val icon = Icon.createWithResource(context, R.drawable.ic_notification)
            builder.addAction(Notification.Action.Builder(icon, action.label, actionIntent(action)).build())
        }
        return builder.build()
    }

    private fun actionIntent(action: SessionAction): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.requestCode,
        Intent(action.intentAction).setClass(context, SessionActionReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
