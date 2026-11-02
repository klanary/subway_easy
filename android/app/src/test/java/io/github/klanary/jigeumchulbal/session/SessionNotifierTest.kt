package io.github.klanary.jigeumchulbal.session

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.klanary.jigeumchulbal.MainActivity
import io.github.klanary.jigeumchulbal.fakes.FakeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Robolectric ShadowNotificationManager로 실제로 올라간 알림의 채널·버튼·카운트다운을 읽는다. */
@RunWith(RobolectricTestRunner::class)
class SessionNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = SessionNotifier(context, FakeAlarmScheduler(exact = true))

    private val waiting = SessionState.Waiting(
        WaitTarget("홍대입구", "홍대입구", "2호선", "합정", null), WaitMode.DEPARTURE_TIMING, null,
        420, 60, 180, 120, startedAt = 0, deadlineAt = 1_800_000,
        plan = TrainPlan(420_000, 900_000, "성수"), nextTrainSec = 240, updatedAt = 0,
    )
    private val riding = SessionState.Riding(
        listOf(AlightPoint("홍대입구", false, PointKind.FINAL, alightAt = 300_000, alertAt = 180_000, stopsToGo = 2)),
    )

    private fun ongoing(): Notification? = shadowOf(manager).getNotification(ONGOING_NOTIFICATION_ID)

    @Before fun channels() = createNotificationChannels(context)

    @Test fun channelsMatchTheSpec() {
        val ongoing = manager.getNotificationChannel(CHANNEL_ONGOING)
        val alerts = manager.getNotificationChannel(CHANNEL_ALERTS)
        assertEquals("진행 중인 통학" to NotificationManager.IMPORTANCE_LOW, ongoing.name to ongoing.importance)
        assertEquals("출발·하차 알림" to NotificationManager.IMPORTANCE_HIGH, alerts.name to alerts.importance)
        assertTrue(alerts.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, alerts.lockscreenVisibility)
    }

    @Test fun waitingOngoingHasBoardedAndCancelButtons() {
        notifier.showOngoing(waiting, now = 0)
        val n = ongoing()!!
        assertEquals(CHANNEL_ONGOING, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("홍대입구 2호선 합정 방면", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("다음 열차 4분 · 7분 뒤 출발", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("탑승했어요", "종료"), n.actions.map { it.title.toString() })
        val sent = n.actions.map { shadowOf(it.actionIntent) }
        assertTrue(sent.all { it.isBroadcast })
        assertEquals(listOf(4001, 4002), sent.map { it.requestCode })
        assertEquals(listOf(SessionAction.BOARDED, SessionAction.CANCEL), sent.map { sessionActionOf(it.savedIntent.action) })
    }

    @Test fun ridingOngoingCountsDownToTheNextPoint() {
        notifier.showOngoing(riding, now = 0)
        val n = ongoing()!!
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(300_000L, n.`when`)
        assertEquals(listOf("종료"), n.actions.map { it.title.toString() })
    }

    @Test fun inexactAlarmsAddAWarning() {
        SessionNotifier(context, FakeAlarmScheduler(exact = false)).showOngoing(riding, now = 0)
        assertEquals("알림이 늦을 수 있어요", ongoing()!!.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test fun idleClearsTheOngoingNotification() {
        notifier.showOngoing(waiting, now = 0)
        notifier.showOngoing(SessionState.Idle, now = 0)
        assertNull(ongoing())
    }

    @Test fun clearOngoingRemovesIt() {
        notifier.showOngoing(riding, now = 0)
        notifier.clearOngoing()
        assertNull(ongoing())
    }

    @Test fun tappingOpensTheSessionScreen() {
        notifier.showOngoing(waiting, now = 0)
        notifier.alert(AlertContent.Replanned)
        val opened = listOf(ongoing()!!, shadowOf(manager).getNotification(ALERT_ID_BASE))
            .map { shadowOf(it.contentIntent).savedIntent }
        assertTrue(opened.all { it.action == ACTION_OPEN_SESSION && it.component?.className == MainActivity::class.java.name })
    }

    @Test fun alertsUseTheAlertChannelAndCycleFiftyIds() {
        repeat(ALERT_SLOTS + 1) { notifier.alert(AlertContent.LeaveNow(it, "성수")) }
        assertEquals((ALERT_ID_BASE until ALERT_ID_BASE + ALERT_SLOTS).toSet(), manager.activeNotifications.map { it.id }.toSet())
        val newest = shadowOf(manager).getNotification(ALERT_ID_BASE)
        assertEquals(CHANNEL_ALERTS, newest.channelId)
        assertEquals("지금 출발하세요", newest.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("${ALERT_SLOTS}분 뒤 성수행 열차", newest.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(newest.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun foregroundNotificationIsTheOngoingOne() {
        val n = notifier.foregroundNotification(waiting, now = 0)
        assertEquals(CHANNEL_ONGOING, n.channelId)
        assertEquals("홍대입구 2호선 합정 방면", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // 서비스가 막 끝난 세션으로 시작돼도 startForeground에 쓸 알림은 있어야 한다.
        assertEquals(CHANNEL_ONGOING, notifier.foregroundNotification(SessionState.Idle, now = 0).channelId)
    }
}
