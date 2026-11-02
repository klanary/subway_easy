package io.github.klanary.jigeumchulbal.ui.routeedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.toEndpoint
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.repository.MyPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 경로 편집 → 경로 결과 → 경로 상세가 함께 쓰는 상태. [results]가 null이면 아직 검색하지 않았다. */
data class RouteFlowState(
    val origin: Endpoint? = null,
    val dest: Endpoint? = null,
    val originHint: String? = null,
    val results: UiState<RoutesDto>? = null,
    val receivedAt: Long? = null,
    val selectedRouteId: String? = null,
) {
    val canSearch: Boolean get() = origin != null && dest != null

    val selectedRoute: RouteDto?
        get() = (results as? UiState.Content)?.data?.routes?.find { it.id == selectedRouteId }
}

/** 중첩 그래프 RouteFlowGraph에 하나만 두고 세 화면이 공유한다. 경유지는 P4가 더한다. */
class RouteFlowViewModel(
    private val routes: RouteRepository,
    private val myPlaces: MyPlaceRepository,
    private val location: LocationProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(RouteFlowState())
    val state: StateFlow<RouteFlowState> = _state.asStateFlow()

    private var initialized = false
    private var autoOpenConsumed = false
    private var searchJob: Job? = null

    /** 홈 검색창이면 [destMyPlaceId]가 null, 내 장소 칩이면 그 장소를 도착에 넣는다. 한 번만 실행한다. */
    fun init(destMyPlaceId: Long?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val dest = destMyPlaceId?.let { myPlaces.get(it)?.toEndpoint() }
            if (dest != null) _state.update { it.copy(dest = dest) }
            fillOriginFromLocation(overwrite = false)
        }
    }

    /** [현재 위치] 칩: 권한을 허용받은 뒤 부른다. */
    fun useCurrentLocation() {
        viewModelScope.launch { fillOriginFromLocation(overwrite = true) }
    }

    fun apply(pick: PickResult) {
        _state.update {
            when (pick.slot) {
                Slot.ORIGIN -> it.copy(origin = pick.endpoint, originHint = null).withoutResults()
                Slot.DEST -> it.copy(dest = pick.endpoint).withoutResults()
                Slot.VIA, null -> it // 경유지는 P4
            }
        }
    }

    fun swap() {
        _state.update { it.copy(origin = it.dest, dest = it.origin).withoutResults() }
    }

    fun search() {
        val current = _state.value
        val origin = current.origin ?: return
        val dest = current.dest ?: return
        searchJob?.cancel()
        _state.update { it.copy(results = UiState.Loading, selectedRouteId = null) }
        searchJob = viewModelScope.launch {
            val result = routes.routes(origin, dest)
            _state.update {
                it.copy(
                    results = result.toUiState(isEmpty = { dto -> dto.routes.isEmpty() }, isStale = { dto -> dto.stale }),
                    receivedAt = clock(),
                )
            }
        }
    }

    fun select(routeId: String) {
        _state.update { it.copy(selectedRouteId = routeId) }
    }

    /** 도착 칸이 비어 있으면 처음 한 번만 true. 검색 화면에서 그냥 돌아와도 다시 열지 않는다. */
    fun consumeAutoOpenSearch(): Boolean {
        if (autoOpenConsumed) return false
        autoOpenConsumed = true
        return _state.value.dest == null
    }

    private suspend fun fillOriginFromLocation(overwrite: Boolean) {
        val point = if (location.hasPermission()) location.current() else null
        _state.update {
            when {
                // 위치를 기다리는 사이 사용자가 출발지를 골랐으면 덮어쓰지 않는다.
                it.origin != null && !overwrite -> it
                point != null -> it.copy(
                    origin = Endpoint(Copy.CURRENT_LOCATION, point, EndpointKind.CURRENT_LOCATION),
                    originHint = null,
                ).withoutResults()
                it.origin == null -> it.copy(originHint = Copy.ORIGIN_HINT)
                else -> it
            }
        }
    }

    /** 출발·도착이 바뀌면 이전 검색 결과는 맞지 않으므로 지운다. */
    private fun RouteFlowState.withoutResults() = copy(results = null, receivedAt = null, selectedRouteId = null)
}
