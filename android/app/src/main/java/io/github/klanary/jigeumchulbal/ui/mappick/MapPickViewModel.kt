package io.github.klanary.jigeumchulbal.ui.mappick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ReverseDto
import io.github.klanary.jigeumchulbal.data.repository.PlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.RecentSearchRepository
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.formatDistance
import io.github.klanary.jigeumchulbal.ui.common.mapContent
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** 핀 아래 카드: 주소와 가장 가까운 역. */
data class PinCard(
    val point: GeoPoint,
    val address: String?,
    val addressText: String,
    val stationText: String,
)

/**
 * 지도 핀 선택(스펙 3.1 ③). 카메라가 멈추고 500ms 안에 다시 움직이면 마지막 위치만 주소를 조회한다(스펙 7.3).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MapPickViewModel(
    private val places: PlaceRepository,
    private val recents: RecentSearchRepository,
) : ViewModel() {
    private val center = MutableStateFlow<GeoPoint?>(null)

    val card: StateFlow<UiState<PinCard>> = center
        .filterNotNull()
        .debounce(REVERSE_DELAY_MS)
        .flatMapLatest { point ->
            flow {
                emit(UiState.Loading)
                emit(places.reverse(point).toUiState().mapContent { it.toCard(point) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    fun onCameraIdle(point: GeoPoint) {
        center.value = point
    }

    /** 지금 핀 위치를 고른다. 주소를 아직 못 받았거나 주소가 없으면 "지도에서 선택한 위치"로 표시한다. */
    suspend fun pick(slot: Slot?): PickResult {
        val point = checkNotNull(center.value) { "지도가 준비되기 전에는 고를 수 없어요" }
        val address = (card.value as? UiState.Content)?.data?.takeIf { it.point == point }?.address
        val endpoint = Endpoint(address ?: Copy.PIN_LABEL, point, EndpointKind.PIN)
        recents.record(endpoint)
        return PickResult(slot, endpoint)
    }

    private fun ReverseDto.toCard(point: GeoPoint) = PinCard(
        point = point,
        address = address,
        addressText = address ?: Copy.NO_ADDRESS,
        stationText = nearestStation
            ?.let { "가장 가까운 역: ${it.name} · ${formatDistance(it.distanceM)}" }
            ?: Copy.NO_STATION_NEARBY,
    )

    private companion object {
        const val REVERSE_DELAY_MS = 500L
    }
}
