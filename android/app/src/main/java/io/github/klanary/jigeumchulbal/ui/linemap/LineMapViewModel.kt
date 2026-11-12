package io.github.klanary.jigeumchulbal.ui.linemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 */
data class LineMapUiState(
    val map: LineMapStations? = null,
    val focus: MapStation? = null,
    val message: String? = null,
)

/** 노선도 홈(재설계 스펙 4.1). 역 위치표를 읽고, 현재 위치에서 가장 가까운 역을 찾는다. */
class LineMapViewModel(
    loadStations: () -> LineMapStations,
    private val stations: StationRepository,
    private val location: LocationProvider,
    loadDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(LineMapUiState())
    val state: StateFlow<LineMapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(loadDispatcher) {
            val map = loadStations()
            _state.update { it.copy(map = map) }
        }
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

    fun consumeFocus() {
        _state.update { it.copy(focus = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
