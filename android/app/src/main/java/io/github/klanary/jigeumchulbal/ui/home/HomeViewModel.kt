package io.github.klanary.jigeumchulbal.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.MyPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.ServerRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.toUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoriteStationUi(val stationId: String, val arrivals: UiState<ArrivalsDto>)

data class HomeUiState(
    val chips: List<MyPlaceEntity> = emptyList(),
    val favoriteStations: List<FavoriteStationUi> = emptyList(),
)

/**
 * 홈. 만들어질 때 잠든 서버를 깨우되 응답을 기다리지 않고, 기기에 저장된 내 장소·즐겨찾는 역부터 보여준다(스펙 10.1).
 * 즐겨찾는 역의 도착 정보는 역마다 따로 불러 한 역의 실패가 다른 역을 가리지 않는다.
 */
class HomeViewModel(
    myPlaces: MyPlaceRepository,
    favorites: FavoriteRepository,
    private val stations: StationRepository,
    server: ServerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var favoriteList: List<FavoriteStationEntity> = emptyList()
    private var lastLoadedAt = 0L

    init {
        viewModelScope.launch { server.wake() }
        viewModelScope.launch { myPlaces.places.collect { places -> _state.update { it.copy(chips = places) } } }
        viewModelScope.launch {
            favorites.stations.collect { list ->
                favoriteList = list
                _state.update { state ->
                    val previous = state.favoriteStations.associateBy { it.stationId }
                    state.copy(
                        favoriteStations = list.map { previous[it.stationId] ?: FavoriteStationUi(it.stationId, UiState.Loading) },
                    )
                }
                loadAll()
            }
        }
    }

    /** 30초마다 즐겨찾는 역의 도착 정보를 다시 부른다. 마지막으로 부른 지 30초가 지났으면 바로 부른다. */
    suspend fun runAutoRefresh() {
        while (true) {
            val wait = REFRESH_INTERVAL_MS - (clock() - lastLoadedAt)
            if (wait > 0) delay(wait)
            loadAll()
        }
    }

    private suspend fun loadAll() = coroutineScope {
        lastLoadedAt = clock()
        favoriteList.forEach { favorite ->
            launch {
                val result = stations.arrivals(favorite.stationId, favorite.line, favorite.towards)
                    .toUiState(isEmpty = { it.groups.isEmpty() }, isStale = { it.stale })
                _state.update { state ->
                    state.copy(
                        favoriteStations = state.favoriteStations.map {
                            if (it.stationId == favorite.stationId) it.copy(arrivals = result) else it
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
    }
}
