package io.github.klanary.jigeumchulbal.ui.linemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.ui.common.Copy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [focus]: 화면이 가운데로 옮기고 역 시트를 열 역(현재 위치 버튼, 검색 결과). 화면이 처리한 뒤 [LineMapViewModel.consumeFocus].
 * [message]: 잠깐 보일 안내("가까운 역이 없어요" 등).
 * [selected]: 역 시트를 연 역. [draft]: 고르는 중인 출발·경유·도착. [readyTrip]: 경로 결과로 넘길 차례인 여정.
 */
/** 안내에 붙는 버튼. */
enum class MessageAction { OPEN_SETTINGS }

data class LineMapUiState(
    val map: LineMapStations? = null,
    val focus: MapStation? = null,
    val message: String? = null,
    val messageAction: MessageAction? = null,
    val mapFailed: Boolean = false,
    val selected: String? = null,
    val draft: TripDraft = TripDraft(),
    val readyTrip: TripDraft? = null,
)

/** 노선도 홈(재설계 스펙 4.1). 역 위치표를 읽고, 현재 위치에서 가장 가까운 역을 찾는다. */
class LineMapViewModel(
    private val loadStations: () -> LineMapStations,
    private val stations: StationRepository,
    private val location: LocationProvider,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(LineMapUiState())
    val state: StateFlow<LineMapUiState> = _state.asStateFlow()

    init {
        loadMap()
    }

    /** 역 위치표를 읽는다. 실패하면 앱을 끄지 않고 [LineMapUiState.mapFailed]로 알린다. */
    private fun loadMap() {
        viewModelScope.launch(loadDispatcher) {
            val map = runCatching { loadStations() }.getOrNull()
            _state.update { it.copy(map = map, mapFailed = map == null) }
        }
    }

    /** 노선도 실패 화면의 [다시 시도]. */
    fun retryMap() {
        _state.update { it.copy(mapFailed = false) }
        loadMap()
    }

    /** [현재 위치]에서 위치 권한을 거부했다: 설정을 열 수 있는 안내를 띄운다. */
    fun onLocationDenied() {
        _state.update { it.copy(message = Copy.LOCATION_DENIED, messageAction = MessageAction.OPEN_SETTINGS) }
    }

    /** [현재 위치]: 권한을 받은 뒤 부른다. 가까운 역이 노선도에 있으면 그 역으로 간다. */
    fun locate() {
        viewModelScope.launch {
            val point = if (location.hasPermission()) location.current() else null
            if (point == null) {
                _state.update { it.copy(message = Copy.LOCATION_UNAVAILABLE) }
                return@launch
            }
            val nearest = when (val result = stations.nearest(point.lat, point.lng)) {
                is ApiResult.Ok -> result.value
                is ApiResult.Err -> {
                    _state.update { it.copy(message = Copy.NETWORK_ERROR) }
                    return@launch
                }
            }
            val onMap = nearest?.let { found -> _state.value.map?.find(found.id) }
            _state.update {
                when {
                    nearest == null -> it.copy(message = Copy.NO_STATION_NEARBY)
                    onMap == null -> it.copy(message = Copy.STATION_NOT_ON_MAP)
                    else -> it.copy(focus = onMap, message = null)
                }
            }
        }
    }

    /** 검색에서 고른 역으로 간다. */
    fun focusOn(stationId: String) {
        val found = _state.value.map?.find(stationId)
        _state.update { if (found != null) it.copy(focus = found) else it.copy(message = Copy.STATION_NOT_ON_MAP) }
    }

    /** 홈을 떠났다 돌아와도 보던 위치·배율 그대로 노선도를 보이게 한다. */
    var savedTransform: MapTransform? = null
        private set

    /** 저장해 둔 마지막 화면에서 위치·배율을 이어받는다(이미 보던 위치가 있으면 그대로 둔다). */
    fun restoreTransform(transform: MapTransform) {
        if (savedTransform == null) savedTransform = transform
    }

    fun saveTransform(transform: MapTransform) {
        savedTransform = transform
    }

    /** 노선도 화면 크기(px). 마지막 화면을 저장할 때 쓴다. */
    var viewportWidth = 0
        private set
    var viewportHeight = 0
        private set

    fun saveViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    fun consumeFocus() {
        _state.update { it.copy(focus = null) }
    }

    /** 역을 눌렀다: 그 역의 시트를 연다. */
    fun selectStation(stationId: String) {
        _state.update { it.copy(selected = stationId) }
    }

    fun closeSheet() {
        _state.update { it.copy(selected = null) }
    }

    /** 시트의 [출발로] [도착으로] [경유로]. 출발·도착이 정해지면 경로 결과로 넘긴다. */
    fun setRole(role: TripRole, station: StationRef) {
        _state.update {
            if (!it.draft.canSet(role, station)) return@update it
            val draft = it.draft.set(role, station)
            it.copy(draft = draft, selected = null, readyTrip = draft.takeIf { d -> d.ready })
        }
    }

    fun consumeReadyTrip() {
        _state.update { it.copy(readyTrip = null) }
    }

    fun cancelTrip() {
        _state.update { it.copy(draft = TripDraft(), readyTrip = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null, messageAction = null) }
    }
}
