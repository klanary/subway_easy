package io.github.klanary.jigeumchulbal.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceType
import io.github.klanary.jigeumchulbal.data.local.SettingsStore
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.repository.MyPlaceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// 알림 시점 선택지(스펙 8.6). 기본값은 각각 60초, 120초, 180초, 30분이다.
val DEPART_MARGIN_OPTIONS = listOf(0, 30, 60, 90, 120)
val ALIGHT_LEAD_OPTIONS = listOf(60, 120, 180, 240)
val STATION_LEAD_OPTIONS = listOf(60, 120, 180, 300)
val MAX_WAIT_OPTIONS = listOf(15, 30, 45, 60)

data class SettingsUiState(
    val places: List<MyPlaceEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class SettingsViewModel(
    private val myPlaces: MyPlaceRepository,
    private val store: SettingsStore,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(myPlaces.places, store.settings) { places, settings ->
        SettingsUiState(places, settings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /** 지도에서 고른 위치(PIN)는 이름이 곧 주소다. 그 밖에는 주소를 따로 저장하지 않는다. */
    fun savePlace(type: MyPlaceType, label: String, endpoint: Endpoint) {
        val address = if (endpoint.kind == EndpointKind.PIN) endpoint.label else null
        viewModelScope.launch { myPlaces.save(type, label, endpoint, address) }
    }

    fun deletePlace(id: Long) {
        viewModelScope.launch { myPlaces.delete(id) }
    }

    fun setDepartMargin(sec: Int) = update(sec, DEPART_MARGIN_OPTIONS) { it.copy(departMarginSec = sec) }

    fun setAlightLead(sec: Int) = update(sec, ALIGHT_LEAD_OPTIONS) { it.copy(alightLeadSec = sec) }

    fun setStationLead(sec: Int) = update(sec, STATION_LEAD_OPTIONS) { it.copy(stationLeadSec = sec) }

    fun setMaxWait(min: Int) = update(min, MAX_WAIT_OPTIONS) { it.copy(maxWaitMin = min) }

    private fun update(value: Int, options: List<Int>, transform: (AppSettings) -> AppSettings) {
        require(value in options) { "$value 은(는) 선택지 $options 에 없어요" }
        viewModelScope.launch { store.update(transform) }
    }
}
