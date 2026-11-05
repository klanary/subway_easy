package io.github.klanary.jigeumchulbal.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceType
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.ui.RouteFlowGraph
import io.github.klanary.jigeumchulbal.ui.SessionDest
import io.github.klanary.jigeumchulbal.ui.SettingsDest
import io.github.klanary.jigeumchulbal.ui.StationDest
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining
import io.github.klanary.jigeumchulbal.ui.session.SessionViewModel
import io.github.klanary.jigeumchulbal.ui.session.sessionHeadline
import io.github.klanary.jigeumchulbal.ui.session.sessionViewModel
import io.github.klanary.jigeumchulbal.ui.station.groupTitle

/** 홈(스펙 4.2): 진행 중인 세션 배너, 검색창, 내 장소 칩, 즐겨찾는 역의 실시간 도착. 자주 가는 경로는 P4 Task 12가 더한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = appViewModel {
        HomeViewModel(it.myPlaceRepository, it.favoriteRepository, it.stationRepository, it.serverRepository)
    },
    sessionViewModel: SessionViewModel = sessionViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.runAutoRefresh() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Copy.APP_NAME) },
                actions = {
                    IconButton(onClick = { navController.navigate(SettingsDest) }) {
                        Icon(Icons.Filled.Settings, contentDescription = Copy.SETTINGS)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session != SessionState.Idle) {
                item { SessionBanner(sessionViewModel) { navController.navigate(SessionDest()) } }
            }
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { navController.navigate(RouteFlowGraph()) },
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                        headlineContent = { Text(Copy.SEARCH_BAR) },
                    )
                }
            }
            item { MyPlaceChips(state.chips, navController) }
            if (state.favoriteRoutes.isNotEmpty()) {
                item { Text(Copy.FAVORITE_ROUTES, style = MaterialTheme.typography.titleMedium) }
                items(state.favoriteRoutes, key = { "route-${it.id}" }) { route ->
                    // 한 번 탭으로 경로 결과(⑨)
                    OutlinedCard(onClick = { navController.navigate(RouteFlowGraph(favoriteRouteId = route.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(favoriteRouteTitle(route), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (state.favoriteStations.isNotEmpty()) {
                item { Text(Copy.FAVORITE_STATIONS, style = MaterialTheme.typography.titleMedium) }
                items(state.favoriteStations, key = { it.stationId }) { station ->
                    FavoriteStationCard(station) { navController.navigate(StationDest(station.stationId)) }
                }
            }
        }
    }
}

/** "출발 → 도착", 경유지가 있으면 "출발 → 경유 → 도착". */
private fun favoriteRouteTitle(route: FavoriteRouteEntity): String =
    listOfNotNull(route.originLabel, route.viaLabel.takeIf { route.viaLat != null }, route.destLabel).joinToString(" → ")

/** "통학 알림 진행 중 · …". 1초 시계는 이 배너만 다시 그린다. */
@Composable
private fun SessionBanner(viewModel: SessionViewModel, onClick: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val headline = sessionHeadline(state, now) ?: return
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Text("${Copy.SESSION_IN_PROGRESS} · $headline", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** 집·학교·회사는 저장돼 있으면 그 이름, 아니면 "+ 집"처럼 추가 칩(설정으로)을 보여준다. 직접 추가한 장소는 뒤에 붙는다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyPlaceChips(places: List<MyPlaceEntity>, navController: NavController) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(MyPlaceType.HOME, MyPlaceType.SCHOOL, MyPlaceType.WORK).forEach { type ->
            val saved = places.firstOrNull { it.type == type }
            if (saved != null) {
                AssistChip(
                    onClick = { navController.navigate(RouteFlowGraph(destMyPlaceId = saved.id)) },
                    label = { Text(type.label()) },
                )
            } else {
                AssistChip(onClick = { navController.navigate(SettingsDest) }, label = { Text("+ ${type.label()}") })
            }
        }
        places.filter { it.type == MyPlaceType.CUSTOM }.forEach { custom ->
            AssistChip(
                onClick = { navController.navigate(RouteFlowGraph(destMyPlaceId = custom.id)) },
                label = { Text(custom.label) },
            )
        }
    }
}

@Composable
private fun FavoriteStationCard(station: FavoriteStationUi, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(station.stationId, style = MaterialTheme.typography.titleMedium)
            when (val arrivals = station.arrivals) {
                UiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UiState.Empty -> Text(Copy.NO_ARRIVALS)
                is UiState.Error -> Text(arrivals.message)
                UiState.Offline -> Text(Copy.OFFLINE)
                is UiState.Content -> Column(Modifier.alpha(if (arrivals.stale) 0.5f else 1f)) {
                    arrivals.data.groups.forEach { group ->
                        val first = group.trains.firstOrNull()
                        val train = first?.let { (it.destination?.let { d -> "${d}행 · " }.orEmpty()) + formatRemaining(it.remainingSec) }
                        Text("${groupTitle(group)}  ${train ?: Copy.NO_INFO}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

fun MyPlaceType.label(): String = when (this) {
    MyPlaceType.HOME -> Copy.HOME
    MyPlaceType.SCHOOL -> Copy.SCHOOL
    MyPlaceType.WORK -> Copy.WORK
    MyPlaceType.CUSTOM -> Copy.CUSTOM_PLACE
}
