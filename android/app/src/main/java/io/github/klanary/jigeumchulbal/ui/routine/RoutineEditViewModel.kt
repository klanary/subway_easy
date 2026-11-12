package io.github.klanary.jigeumchulbal.ui.routine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.routine.RoutineDraft
import io.github.klanary.jigeumchulbal.routine.RoutineEntity
import io.github.klanary.jigeumchulbal.routine.RoutineRepository
import io.github.klanary.jigeumchulbal.routine.ALL_DAYS
import io.github.klanary.jigeumchulbal.routine.dayBit
import io.github.klanary.jigeumchulbal.routine.nextOccurrence
import io.github.klanary.jigeumchulbal.ui.common.Copy
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val WEEKDAYS = DayOfWeek.entries.take(5).sumOf { dayBit(it) }
private const val DEFAULT_MINUTE = 8 * 60
private const val PREVIEW_DEBOUNCE_MS = 300L

data class RoutineEditUi(
    val id: Long = 0,
    val name: String = "",
    val from: StationRef? = null,
    val via: StationRef? = null,
    val to: StationRef? = null,
    val routeJson: String = "",
    val days: Int = WEEKDAYS,
    val minuteOfDay: Int = DEFAULT_MINUTE,
    val walkMin: Int = RoutineEntity.DEFAULT_WALK_MIN,
    val alightAlerts: Boolean = false,
    /** "이 시각에 타게 될 열차: 08:14, 08:22"의 시각들. */
    val preview: List<String> = emptyList(),
    val message: String? = null,
    val saved: Boolean = false,
) {
    /** 출발·도착이 있고 요일을 하나 이상 골라야 저장한다(요일이 없으면 울리지 않는다). */
    val canSave: Boolean get() = from != null && to != null && days and ALL_DAYS != 0
}

/**
 * 루틴 등록·편집(재설계 스펙 4.6). 새 루틴은 경로 상세의 [루틴으로 만들기]가 넘긴 경로로 시작한다.
 * 시각·걷는 시간이 바뀌면 그 시각에 타게 될 열차를 서버 시간표로 미리 보인다.
 */
class RoutineEditViewModel(
    private val routineId: Long?,
    draft: RoutineDraft?,
    private val routines: RoutineRepository,
    private val routes: RouteRepository,
    private val json: Json,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(
        draft?.let { RoutineEditUi(name = "${it.from.name} → ${it.to.name}", from = it.from, via = it.via, to = it.to, routeJson = it.routeJson) }
            ?: RoutineEditUi(),
    )
    val state: StateFlow<RoutineEditUi> = _state.asStateFlow()
    private var previewJob: Job? = null
    private var previewKey: List<Any?>? = null

    init {
        if (routineId != null) {
            viewModelScope.launch {
                val r = routines.get(routineId) ?: return@launch
                _state.value = RoutineEditUi(
                    id = r.id, name = r.name, from = r.from, via = r.via, to = r.to, routeJson = r.routeJson,
                    days = r.days, minuteOfDay = r.minuteOfDay, walkMin = r.walkMin, alightAlerts = r.alightAlerts,
                )
                refreshPreview()
            }
        } else {
            refreshPreview()
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun toggleDay(day: DayOfWeek) {
        _state.update { it.copy(days = it.days xor dayBit(day)) }
        refreshPreview()
    }

    fun setTime(minuteOfDay: Int) {
        _state.update { it.copy(minuteOfDay = minuteOfDay.coerceIn(0, 24 * 60 - 1)) }
        refreshPreview()
    }

    fun setWalk(minutes: Int) {
        _state.update { it.copy(walkMin = minutes.coerceIn(0, RoutineEntity.MAX_WALK_MIN)) }
        refreshPreview()
    }

    fun setAlightAlerts(on: Boolean) = _state.update { it.copy(alightAlerts = on) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** 루틴을 켜려면 알림 권한이 있어야 한다(재설계 스펙 5.4). 없으면 꺼진 채로 저장하고 알린다. */
    fun save(hasNotificationPermission: Boolean) {
        val s = _state.value
        val from = s.from ?: return
        val to = s.to ?: return
        if (!s.canSave) {
            _state.update { it.copy(message = Copy.PICK_A_DAY) }
            return
        }
        viewModelScope.launch {
            routines.save(
                RoutineEntity(
                    id = s.id, name = s.name.ifBlank { "${from.name} → ${to.name}" },
                    fromId = from.id, fromName = from.name, viaId = s.via?.id, viaName = s.via?.name,
                    toId = to.id, toName = to.name, routeJson = s.routeJson, days = s.days, minuteOfDay = s.minuteOfDay,
                    walkMin = s.walkMin, alightAlerts = s.alightAlerts, enabled = hasNotificationPermission,
                ),
            )
            _state.update {
                it.copy(saved = true, message = if (hasNotificationPermission) null else Copy.NEED_NOTIFICATION_PERMISSION)
            }
        }
    }

    /**
     * 다음 실행 시각(없으면 오늘 그 시각)에 걷기 시작해 역에 닿는 시각 이후의 열차를 서버에 묻는다.
     * 서버는 `departAt + 탑승 전 시간` 이후 열차를 주므로 루틴의 걷는 시간과 맞게 departAt을 당긴다.
     */
    private fun refreshPreview() {
        val s = _state.value
        val from = s.from ?: return
        val to = s.to ?: return
        val route = runCatching { json.decodeFromString<RouteDto>(s.routeJson) }.getOrNull()
        val toBoarding = route?.firstBoarding?.secToBoarding ?: 0
        val base = now()
        val leave = nextOccurrence(s.days, s.minuteOfDay, base)
            ?: base.toLocalDate().atTime(s.minuteOfDay / 60, s.minuteOfDay % 60).atZone(base.zone)
        val departAt = leave.plusMinutes(s.walkMin.toLong()).minusSeconds(toBoarding.toLong()).toInstant()
        // 같은 조건은 다시 묻지 않고, 슬라이더를 끄는 동안은 멈춘 뒤 한 번만 묻는다(서버 요청 제한).
        val key = listOf(from.id, to.id, s.via?.id, departAt)
        if (key == previewKey) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            previewKey = key
            val found = (routes.routes(from, to, s.via, departAt) as? ApiResult.Ok)?.value?.routes.orEmpty()
            val same = found.firstOrNull { it.id == route?.id } ?: found.firstOrNull()
            val times = same?.firstBoarding?.nextTrains.orEmpty().map { it.message.removeSuffix(" 출발") }
            _state.update { it.copy(preview = times) }
        }
    }
}
