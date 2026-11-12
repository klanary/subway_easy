package io.github.klanary.jigeumchulbal.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.ServerRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(val favoriteRoutes: List<FavoriteRouteEntity> = emptyList())

/** 홈. 만들어질 때 잠든 서버를 깨우되 응답을 기다리지 않고, 기기에 저장된 자주 가는 경로부터 보여준다(스펙 10.1). */
class HomeViewModel(
    favorites: FavoriteRepository,
    server: ServerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { server.wake() }
        viewModelScope.launch {
            // 자주 가는 경로: 지금 시간대에 많이 쓴 경로부터(스펙 7.2). 정렬 기준 시각은 홈을 연 때다.
            val now = Instant.ofEpochMilli(clock()).atZone(zone)
            val since = now.minusDays(30).toInstant().toEpochMilli()
            combine(favorites.routes, favorites.usagesSince(since)) { routes, usages ->
                sortFavoriteRoutes(routes, usages, now)
            }.collect { sorted -> _state.update { it.copy(favoriteRoutes = sorted) } }
        }
    }
}
