package io.github.klanary.jigeumchulbal.ui.routeedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.data.repository.from
import io.github.klanary.jigeumchulbal.data.repository.to
import io.github.klanary.jigeumchulbal.data.repository.via
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.ui.PickResult
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import io.github.klanary.jigeumchulbal.ui.linemap.TripDraft
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 경로 편집 → 경로 결과 → 경로 상세가 함께 쓰는 상태. [results]가 null이면 아직 검색하지 않았다. */
data class RouteFlowState(
    val origin: StationRef? = null,
    val via: StationRef? = null,
    val dest: StationRef? = null,
    val viaVisible: Boolean = false,
    val originHint: String? = null,
    /** null이면 지금 출발(재설계 스펙 4.4). */
    val departAt: Instant? = null,
    val results: UiState<RoutesDto>? = null,
    val receivedAt: Long? = null,
    val selectedRouteId: String? = null,
    val isFavorite: Boolean = false,
) {
    /** 출발·도착이 있고, 출발·경유·도착이 모두 다른 역이어야 한다(서버는 같으면 400). */
    val canSearch: Boolean
        get() {
            val ids = listOfNotNull(origin?.id, via?.id, dest?.id)
            return origin != null && dest != null && ids.toSet().size == ids.size
        }

    val selectedRoute: RouteDto?
        get() = (results as? UiState.Content)?.data?.routes?.find { it.id == selectedRouteId }
}

/** 중첩 그래프 RouteFlowGraph에 하나만 두고 세 화면이 공유한다. */
class RouteFlowViewModel(
    private val routes: RouteRepository,
    private val stations: StationRepository,
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
     * [favoriteRouteId]가 있으면 그 자주 가는 경로의 출발·경유·도착을 넣고 바로 검색한다(홈 카드 1탭).
     * 없으면 현재 위치에서 가장 가까운 역을 출발로 채운다. 한 번만 실행한다.
     */
    fun init(favoriteRouteId: Long? = null, trip: TripDraft? = null) {
        if (initialized) return
        initialized = true
        if (trip != null && trip.ready) {
            // 노선도에서 출발·도착을 골라 들어왔다: 바로 검색하고 결과를 연다.
            autoOpenConsumed = true
            openResultsPending = true
            _state.update { RouteFlowState(origin = trip.from, via = trip.via, dest = trip.to, viaVisible = trip.via != null) }
            search()
            return
        }
        if (favoriteRouteId != null) {
            autoOpenConsumed = true
            openResultsPending = true
            viewModelScope.launch {
                val fav = favorites.routes.first().find { it.id == favoriteRouteId } ?: return@launch
                _state.update { RouteFlowState(origin = fav.from, via = fav.via, dest = fav.to, viaVisible = fav.via != null) }
                search()
            }
            return
        }
        viewModelScope.launch { fillOriginFromLocation(overwrite = false) }
    }

    /** [현재 위치] 칩: 권한을 허용받은 뒤 부른다. 가장 가까운 역이 출발이 된다. */
    fun useCurrentLocation() {
        viewModelScope.launch { fillOriginFromLocation(overwrite = true) }
    }

    fun apply(pick: PickResult) {
        _state.update {
            when (pick.slot) {
                Slot.ORIGIN -> it.copy(origin = pick.station, originHint = null).withoutResults()
                Slot.DEST -> it.copy(dest = pick.station).withoutResults()
                Slot.VIA -> it.copy(via = pick.station, viaVisible = true).withoutResults()
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

    /** 출발 시각을 바꾸고 다시 검색한다. null이면 지금 출발. */
    fun setDepartAt(at: Instant?) {
        _state.update { it.copy(departAt = at) }
        if (_state.value.canSearch) search()
    }

    fun search() {
        val current = _state.value
        if (!current.canSearch) return
        val origin = current.origin ?: return
        val dest = current.dest ?: return
        searchJob?.cancel()
        _state.update { it.copy(results = UiState.Loading, selectedRouteId = null) }
        searchJob = viewModelScope.launch {
            val result = routes.routes(origin, dest, current.via, current.departAt)
            val at = clock()
            if (result is ApiResult.Ok) favorites.recordUsageIfFavorite(origin, current.via, dest, at)
            val favorite = favorites.find(origin, current.via, dest) != null
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
            val existing = favorites.find(origin, current.via, dest)
            if (existing != null) favorites.remove(existing.id) else favorites.add(origin, current.via, dest)
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

    /** 홈 자주 가는 경로 카드로 들어왔으면 처음 한 번만 true. */
    fun consumeOpenResults(): Boolean {
        val open = openResultsPending
        openResultsPending = false
        return open
    }

    private suspend fun fillOriginFromLocation(overwrite: Boolean) {
        val point = if (location.hasPermission()) location.current() else null
        val nearest = point?.let { (stations.nearest(it.lat, it.lng) as? ApiResult.Ok)?.value }
        _state.update {
            when {
                // 위치를 기다리는 사이 사용자가 출발역을 골랐으면 덮어쓰지 않는다.
                it.origin != null && !overwrite -> it
                nearest != null -> it.copy(origin = StationRef(nearest.id, nearest.name), originHint = null).withoutResults()
                point != null -> it.copy(originHint = Copy.NO_STATION_NEARBY)
                it.origin == null -> it.copy(originHint = Copy.ORIGIN_HINT)
                else -> it
            }
        }
    }

    /** 출발·도착이 바뀌면 이전 검색 결과는 맞지 않으므로 지운다. */
    private fun RouteFlowState.withoutResults() =
        copy(results = null, receivedAt = null, selectedRouteId = null, isFavorite = false)
}
