package io.github.klanary.jigeumchulbal.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionUiEvent {
    data class Finished(val reason: EndReason) : SessionUiEvent

    /** 알림 권한이 없을 때 화면 안에서 보여줄 알림(스펙 8.6). */
    data class InAppAlert(val content: AlertContent) : SessionUiEvent
}

enum class StartResult { STARTED, ALREADY_RUNNING }

/**
 * 세션 하나를 들고, 이벤트를 상태 머신에 넣고, 돌아온 효과를 포트로 실행한다.
 * 알람 수신기·대기 서비스·화면이 동시에 이벤트를 보낼 수 있어 Mutex로 차례대로 처리한다.
 * 앱 안 모드(알림 권한 없음)에서는 저장·알람·알림·서비스를 쓰지 않고 화면에만 알린다.
 */
class SessionController(
    private val store: SessionStore,
    private val scheduler: AlarmScheduler,
    private val notifier: SessionNotifierPort,
    private val launcher: WaitingServiceLauncher,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private var session = CommuteSession(clock)
    private var loaded = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _inAppOnly = MutableStateFlow(false)
    val inAppOnly: StateFlow<Boolean> = _inAppOnly.asStateFlow()

    private val _uiEvents = MutableSharedFlow<SessionUiEvent>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<SessionUiEvent> = _uiEvents.asSharedFlow()

    /** 세션은 한 번에 하나다(스펙 8.1). 진행 중이면 [replaceExisting]일 때만 기존 세션을 끝내고 시작한다. */
    suspend fun start(event: SessionEvent, inAppOnly: Boolean, replaceExisting: Boolean = false): StartResult =
        mutex.withLock {
            ensureLoaded()
            if (session.state != SessionState.Idle) {
                if (!replaceExisting) return@withLock StartResult.ALREADY_RUNNING
                apply(SessionEvent.Cancel) // 기존 세션의 알람·서비스는 기존 모드로 정리한다
            }
            _inAppOnly.value = inAppOnly
            apply(event)
            StartResult.STARTED
        }

    suspend fun dispatch(event: SessionEvent) = mutex.withLock {
        ensureLoaded()
        apply(event)
    }

    /** 처음 한 번 저장된 세션을 불러온다(앱 재시작 뒤 알람 수신기가 먼저 불러도 이어진다). */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = store.load()
        session = CommuteSession(clock, saved)
        _state.value = saved
    }

    private suspend fun apply(event: SessionEvent) {
        val effects = session.handle(event)
        val newState = session.state
        _state.value = newState
        val inApp = _inAppOnly.value
        if (!inApp) store.save(newState)

        val alarms = if (inApp) NoopAlarmScheduler else scheduler
        val notices = if (inApp) NoopNotifier else notifier
        val service = if (inApp) NoopLauncher else launcher
        effects.forEach { effect ->
            when (effect) {
                is SessionEffect.Schedule -> alarms.schedule(effect.id, effect.at)
                is SessionEffect.CancelAlarm -> alarms.cancel(effect.id)
                SessionEffect.CancelAllAlarms -> alarms.cancelAll()
                is SessionEffect.Alert ->
                    if (inApp) _uiEvents.emit(SessionUiEvent.InAppAlert(effect.content)) else notices.alert(effect.content)
                SessionEffect.UpdateOngoing -> notices.showOngoing(newState, clock())
                SessionEffect.ClearOngoing -> notices.clearOngoing()
                SessionEffect.StartWaitingService -> service.start()
                SessionEffect.StopWaitingService -> service.stop()
                is SessionEffect.Finished -> _uiEvents.emit(SessionUiEvent.Finished(effect.reason))
            }
        }
    }
}
