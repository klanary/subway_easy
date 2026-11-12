package io.github.klanary.jigeumchulbal.session

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * 상태 전환(상태 → 저장 → 효과 실행)은 부른 쪽이 도중에 취소돼도 끝까지 한다. 세션 서비스는 할 일이 바뀌면 지금 일을
 * 취소하는데, 그 일이 막 보낸 사건 때문에 할 일이 바뀐 경우 전환이 반쯤만 되면 알람·서비스·알림이 상태와 어긋난다.
 */
class SessionController(
    private val store: SessionStore,
    private val scheduler: AlarmScheduler,
    private val notifier: SessionNotifierPort,
    private val launcher: SessionServiceLauncher,
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
        atomically {
            ensureLoaded()
            if (session.state != SessionState.Idle) {
                if (!replaceExisting) return@atomically StartResult.ALREADY_RUNNING
                apply(SessionEvent.Cancel) // 기존 세션의 알람·서비스는 기존 모드로 정리한다
            }
            _inAppOnly.value = inAppOnly
            apply(event)
            StartResult.STARTED
        }

    suspend fun dispatch(event: SessionEvent) = atomically {
        ensureLoaded()
        apply(event)
    }

    /** AlarmReceiver가 부른다. */
    suspend fun onAlarm(id: AlarmId) = dispatch(SessionEvent.AlarmFired(id))

    /**
     * 저장된 세션을 이어 간다(스펙 8.7). 앱을 열 때(MainActivity)와 재부팅 뒤(BootReceiver) 부른다.
     * App.onCreate에서는 부르지 않는다: 부팅·알람으로 프로세스가 깨어날 때 대기 서비스를 시작하면 안 된다.
     */
    suspend fun restore(onBoot: Boolean) = atomically {
        ensureLoaded()
        val (restored, effects) = planRestore(session.state, clock(), onBoot)
        session = CommuteSession(clock, restored)
        commit(effects)
    }

    /** 정확한 알람이 새로 허용되면 진행 중 세션의 알람을 정확한 방식으로 다시 예약한다(스펙 8.7). */
    suspend fun rescheduleAll() = atomically {
        ensureLoaded()
        if (_inAppOnly.value || session.state == SessionState.Idle) return@atomically
        scheduler.cancelAll()
        planRestore(session.state, clock(), onBoot = false).second
            .filterIsInstance<SessionEffect.Schedule>()
            .forEach { scheduler.schedule(it.id, it.at) }
        notifier.showOngoing(session.state, clock()) // "알림이 늦을 수 있어요"를 지운다
    }

    /** 한 번에 하나씩, 취소되지 않게 실행한다(클래스 설명 참고). */
    private suspend fun <T> atomically(block: suspend () -> T): T = withContext(NonCancellable) { mutex.withLock { block() } }

    /** 처음 한 번 저장된 세션을 불러온다(앱 재시작 뒤 알람 수신기가 먼저 불러도 이어진다). */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = store.load()
        session = CommuteSession(clock, saved)
        _state.value = saved
    }

    private suspend fun apply(event: SessionEvent) = commit(session.handle(event))

    /** 상태 머신이 바꾼 상태를 알리고 저장한 뒤 효과를 실행한다. 앱 안 모드에서는 저장·알람·알림·서비스를 쓰지 않는다. */
    private suspend fun commit(effects: List<SessionEffect>) {
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
                // 백그라운드에서 서비스를 못 켜면(정확하지 않은 알람으로 깬 루틴 등) 눌러서 앱을 열라고 알린다.
                // 앱을 열면 MainActivity의 복원이 서비스를 다시 켠다(재설계 스펙 13).
                SessionEffect.StartSessionService -> if (!service.start()) notices.alert(AlertContent.TapToStart)
                SessionEffect.StopSessionService -> service.stop()
                is SessionEffect.Finished -> _uiEvents.emit(SessionUiEvent.Finished(effect.reason))
            }
        }
    }
}
