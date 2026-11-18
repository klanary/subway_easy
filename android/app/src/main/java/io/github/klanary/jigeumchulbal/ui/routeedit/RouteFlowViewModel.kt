package io.github.klanary.jigeumchulbal.ui.routeedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
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
import io.github.klanary.jigeumchulbal.ui.routedetail.ExpressFilter
import io.github.klanary.jigeumchulbal.ui.routedetail.PickedSegment
import io.github.klanary.jigeumchulbal.ui.routedetail.SegmentChoice
import io.github.klanary.jigeumchulbal.ui.routedetail.move
import io.github.klanary.jigeumchulbal.ui.routedetail.pickTrains
import io.github.klanary.jigeumchulbal.ui.routedetail.setFilter
import io.github.klanary.jigeumchulbal.ui.routedetail.subwaySteps
import io.github.klanary.jigeumchulbal.ui.routedetail.unpin
import io.github.klanary.jigeumchulbal.ui.routedetail.withPicks
import java.time.OffsetDateTime
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
    /** 경로 상세에서 구간마다 고른 열차(열차 고르기 설계 2026-11-18). 경로를 고르거나 다시 찾으면 비운다. */
    val choices: List<SegmentChoice> = emptyList(),
    /** "더 늦은 열차 불러오기" 중. */
    val loadingMore: Boolean = false,
    /** 더 늦은 열차를 불러와도 새 열차가 없던 구간(최종 리뷰 #4). [다음]을 끈다. */
    val exhausted: Set<Int> = emptySet(),
) {
    /** 사용자가 열차를 직접 바꿨는지(고정 또는 급행 필터). 아니면 알림은 예전처럼 실시간 첫 열차로(설계 §5.4). */
    val hasPicks: Boolean
        get() = choices.any { it.pinned != null || it.filter != ExpressFilter.ALL }

    /** 출발·도착이 있고, 출발·경유·도착이 모두 다른 역이어야 한다(서버는 같으면 400). */
    val canSearch: Boolean
        get() {
            val ids = listOfNotNull(origin?.id, via?.id, dest?.id)
            return origin != null && dest != null && ids.toSet().size == ids.size
        }

    val selectedRoute: RouteDto?
        get() = (results as? UiState.Content)?.data?.routes?.find { it.id == selectedRouteId }

    /** 구간마다 계산한 열차(고정·자동 맞춤·경고). */
    val picks: List<PickedSegment> by lazy { selectedRoute?.let { pickTrains(it, choices) }.orEmpty() }

    /** 고른 열차로 바꾼 경로. 제목·알림·루틴이 쓴다. */
    val pickedRoute: RouteDto? by lazy { selectedRoute?.withPicks(picks) }
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
    private var loadJob: Job? = null

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
        loadJob?.cancel()
        _state.update { it.copy(results = UiState.Loading, selectedRouteId = null, choices = emptyList(), exhausted = emptySet()) }
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
        loadJob?.cancel() // 앞 경로의 "더 늦은 열차" 응답이 새 선택에 섞이지 않게
        _state.update { it.copy(selectedRouteId = routeId, choices = emptyList(), loadingMore = false, exhausted = emptySet()) }
    }

    /** 구간 [segment]의 열차를 [delta](−1 이전, +1 다음)만큼 옮기고 고정한다. */
    fun moveTrain(segment: Int, delta: Int) = updateChoices { route, choices -> move(route, choices, segment, delta) }

    fun setExpressFilter(segment: Int, filter: ExpressFilter) = updateChoices { _, choices -> setFilter(choices, segment, filter) }

    fun unpin(segment: Int) = updateChoices { _, choices -> unpin(choices, segment) }

    private fun updateChoices(change: (RouteDto, List<SegmentChoice>) -> List<SegmentChoice>) {
        _state.update { s -> s.selectedRoute?.let { s.copy(choices = change(it, s.choices)) } ?: s }
    }

    /**
     * 구간 [segment]의 후보 끝을 넘을 때: 그 구간 마지막 후보 출발 + 1분으로 경로를 다시 받아, 지하철 구간 열
     * (노선·탄 역·내릴 역)이 같은 경로의 후보를 구간마다 합친다. 검색 조건(출발 시각)과 결과 목록 순서는 그대로 둔다.
     */
    fun loadLater(segment: Int) {
        val current = _state.value
        val route = current.selectedRoute ?: return
        val origin = current.origin ?: return
        val dest = current.dest ?: return
        if (current.loadingMore) return
        val step = subwaySteps(route).getOrNull(segment) ?: return
        val last = step.candidates?.lastOrNull()?.departsAt ?: step.departsAt ?: return
        // 경로는 출발역에서 찾으므로, 이 구간을 타기까지 걸리는 시간만큼 앞당겨 묻는다(최종 리뷰 #3).
        val offset = if (step.departsAt != null && route.departAt != null) {
            java.time.Duration.between(OffsetDateTime.parse(route.departAt).toInstant(), OffsetDateTime.parse(step.departsAt).toInstant())
                .coerceAtLeast(java.time.Duration.ZERO)
        } else {
            java.time.Duration.ZERO
        }
        val at = OffsetDateTime.parse(last).toInstant().plusSeconds(60).minus(offset)
        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            val result = routes.routes(origin, dest, current.via, at)
            val later = (result as? ApiResult.Ok)?.value?.routes?.firstOrNull { it.segmentKey() == route.segmentKey() }
            _state.update { s ->
                val content = s.results as? UiState.Content
                if (content == null || result !is ApiResult.Ok) return@update s.copy(loadingMore = false) // 실패: 다시 누를 수 있다
                val before = subwaySteps(route).getOrNull(segment)?.candidates?.size ?: 0
                val mergedRoute = later?.let { route.mergeCandidates(it) }
                val after = mergedRoute?.let { subwaySteps(it).getOrNull(segment)?.candidates?.size } ?: before
                // 같은 경로가 없거나 새 열차가 없으면 이 구간은 더 불러올 수 없다(최종 리뷰 #4)
                val exhausted = if (after > before) s.exhausted else s.exhausted + segment
                val routesNow = content.data.routes.map { if (it.id == route.id && mergedRoute != null) mergedRoute else it }
                s.copy(results = content.copy(data = content.data.copy(routes = routesNow)), loadingMore = false, exhausted = exhausted)
            }
        }
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
        copy(results = null, receivedAt = null, selectedRouteId = null, isFavorite = false, choices = emptyList())
}

/** 같은 경로인지 비교하는 열: 지하철 구간마다 노선·탄 역·내릴 역. */
private fun RouteDto.segmentKey() = subwaySteps(this).map { Triple(it.lineName, it.boardStationId, it.alightStationId) }

/** 구간마다 [other]의 후보를 더한다(번호+출발로 중복 제거, 출발 순). */
private fun RouteDto.mergeCandidates(other: RouteDto): RouteDto {
    val extra = subwaySteps(other).map { it.candidates.orEmpty() }
    var k = 0
    return copy(
        legs = legs.map { leg ->
            leg.copy(
                steps = leg.steps.map { step ->
                    if (step.mode != StepMode.SUBWAY) return@map step
                    val more = extra.getOrNull(k++).orEmpty()
                    val all = (step.candidates.orEmpty() + more)
                        .distinctBy { it.trainNo to it.departsAt }
                        .sortedBy { OffsetDateTime.parse(it.departsAt).toInstant() }
                    step.copy(candidates = all)
                },
            )
        },
    )
}
