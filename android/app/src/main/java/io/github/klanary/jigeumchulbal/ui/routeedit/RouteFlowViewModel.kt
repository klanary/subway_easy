package io.github.klanary.jigeumchulbal.ui.routeedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.model.toEndpoint
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 경로 편집 → 경로 결과 → 경로 상세가 함께 쓰는 상태. [results]가 null이면 아직 검색하지 않았다. */
data class RouteFlowState(
    val origin: Endpoint? = null,
    val via: Endpoint? = null,
    val dest: Endpoint? = null,
    val viaVisible: Boolean = false,
    val originHint: String? = null,
    val results: UiState<RoutesDto>? = null,
    val receivedAt: Long? = null,
    val selectedRouteId: String? = null,
    val isFavorite: Boolean = false,
) {
    val canSearch: Boolean get() = origin != null && dest != null

    val selectedRoute: RouteDto?
        get() = (results as? UiState.Content)?.data?.routes?.find { it.id == selectedRouteId }
}

/** 중첩 그래프 RouteFlowGraph에 하나만 두고 세 화면이 공유한다. */
class RouteFlowViewModel(
    private val routes: RouteRepository,
    private val myPlaces: MyPlaceRepository,
    private val location: LocationProvider,
    private val favorites: FavoriteRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(RouteFlowState())
    val state: StateFlow<RouteFlowState> = _state.asStateFlow()

    private var initialized = false
    private var autoOpenConsumed = false
    private var openResultsPending = false
    private var searchJob: Job? = null

    /**
     * 홈 검색창이면 [destMyPlaceId]가 null, 내 장소 칩이면 그 장소를 도착에 넣는다.
     * [favoriteRouteId]가 있으면 그 즐겨찾기의 출발·경유·도착을 넣고 바로 검색한다(⑨, 홈 카드 1탭). 한 번만 실행한다.
     */
    fun init(destMyPlaceId: Long?, favoriteRouteId: Long? = null) {
        if (initialized) return
        initialized = true
        if (favoriteRouteId != null) {
            autoOpenConsumed = true
            openResultsPending = true
            viewModelScope.launch {
                val fav = favorites.routes.first().find { it.id == favoriteRouteId } ?: return@launch
                _state.update { fav.toFlowState() }
                search()
            }
            return
        }
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
                Slot.VIA -> it.copy(via = pick.endpoint, viaVisible = true).withoutResults()
                null -> it
            }
        }
    }

    /** "+ 경유지"를 누를 때만 칸이 펼쳐진다(4.2). 숨기면 경유지도 지운다. */
    fun toggleVia() {
        _state.update {
            if (it.viaVisible) it.copy(via = null, viaVisible = false).withoutResults() else it.copy(viaVisible = true)
        }
    }

    /** 경유지는 그대로 두고 출발과 도착만 바꾼다. */
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
            val result = routes.routes(origin, dest, current.via)
            val at = clock()
            if (result is ApiResult.Ok) favorites.recordUsageIfFavorite(origin, current.via, dest, at)
            val favorite = favorites.findRoute(origin, current.via, dest) != null
            _state.update {
                it.copy(
                    results = result.toUiState(isEmpty = { dto -> dto.routes.isEmpty() }, isStale = { dto -> dto.stale }),
                    receivedAt = at,
                    isFavorite = favorite,
                )
            }
        }
    }

    /** 경로 상세의 [★]. 지금 출발·경유·도착을 자주 가는 경로에 넣거나 뺀다. */
    fun toggleFavorite() {
        val current = _state.value
        val origin = current.origin ?: return
        val dest = current.dest ?: return
        viewModelScope.launch {
            val existing = favorites.findRoute(origin, current.via, dest)
            if (existing != null) favorites.removeRoute(existing.id) else favorites.addRoute(origin, current.via, dest)
            _state.update { it.copy(isFavorite = existing == null) }
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

    /** 홈 자주 가는 경로 카드로 들어왔으면 처음 한 번만 true. 결과에서 뒤로 와 편집 화면이 다시 그려져도 다시 보내지 않는다. */
    fun consumeOpenResults(): Boolean {
        val open = openResultsPending
        openResultsPending = false
        return open
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
    private fun RouteFlowState.withoutResults() =
        copy(results = null, receivedAt = null, selectedRouteId = null, isFavorite = false)

    private fun FavoriteRouteEntity.toFlowState(): RouteFlowState {
        fun endpoint(label: String, lat: Double, lng: Double) = Endpoint(label, GeoPoint(lat, lng), EndpointKind.PIN)
        val via = if (viaLat != null && viaLng != null) endpoint(viaLabel ?: Copy.VIA_POINT, viaLat, viaLng) else null
        return RouteFlowState(
            origin = endpoint(originLabel, originLat, originLng),
            via = via,
            dest = endpoint(destLabel, destLat, destLng),
            viaVisible = via != null,
        )
    }
}
