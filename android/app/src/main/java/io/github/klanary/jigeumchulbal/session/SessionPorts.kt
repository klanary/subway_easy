package io.github.klanary.jigeumchulbal.session

// SessionController가 효과를 실행하는 창구. 안드로이드 구현(알람·알림·포그라운드 서비스)과
// 테스트용 가짜를 갈아 끼울 수 있게 인터페이스로 둔다. 3차(서버 푸시)에서는 AlarmScheduler를 바꾼다(스펙 8.4).

interface AlarmScheduler {
    fun schedule(id: AlarmId, atMillis: Long)

    fun cancel(id: AlarmId)

    fun cancelAll()

    /** 정확한 알람(setAlarmClock)을 쓸 수 있는지. 없으면 부정확 알람이라 "알림이 늦을 수 있어요"를 보여준다. */
    fun canScheduleExact(): Boolean
}

interface SessionNotifierPort {
    fun showOngoing(state: SessionState, now: Long)

    fun clearOngoing()

    fun alert(content: AlertContent)
}

/** 세션 서비스(대기 조회, 탑승 중 열차 추적·탑승 감지)를 켜고 끈다(위치 결합 스펙 7.3). */
interface SessionServiceLauncher {
    fun start()

    fun stop()
}

/** 앱 안 모드(알림 권한 없음)와 테스트에서 쓰는, 아무 일도 하지 않는 구현. */
object NoopAlarmScheduler : AlarmScheduler {
    override fun schedule(id: AlarmId, atMillis: Long) = Unit

    override fun cancel(id: AlarmId) = Unit

    override fun cancelAll() = Unit

    override fun canScheduleExact(): Boolean = true
}

object NoopNotifier : SessionNotifierPort {
    override fun showOngoing(state: SessionState, now: Long) = Unit

    override fun clearOngoing() = Unit

    override fun alert(content: AlertContent) = Unit
}

object NoopLauncher : SessionServiceLauncher {
    override fun start() = Unit

    override fun stop() = Unit
}
