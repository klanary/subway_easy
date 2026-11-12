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
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.ui.RouteFlowGraph
import io.github.klanary.jigeumchulbal.ui.SessionDest
import io.github.klanary.jigeumchulbal.ui.SettingsDest
import io.github.klanary.jigeumchulbal.ui.SearchDest
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.common.formatRemaining
import io.github.klanary.jigeumchulbal.ui.session.SessionViewModel
import io.github.klanary.jigeumchulbal.ui.session.sessionHeadline
import io.github.klanary.jigeumchulbal.ui.session.sessionViewModel
import io.github.klanary.jigeumchulbal.ui.station.groupTitle

/** 홈: 진행 중인 세션 배너, 경로 찾기, 역 검색, 자주 가는 경로. 재설계의 노선도 홈(A5)이 이 화면을 대신한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = appViewModel {
        HomeViewModel(it.favoriteRepository, it.serverRepository)
    },
    sessionViewModel: SessionViewModel = sessionViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by sessionViewModel.state.collectAsStateWithLifecycle()

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
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { navController.navigate(SearchDest()) },
                ) {
                    ListItem(headlineContent = { Text(Copy.SEARCH_HINT) })
                }
            }
            if (state.favoriteRoutes.isNotEmpty()) {
                item { Text(Copy.FAVORITE_ROUTES, style = MaterialTheme.typography.titleMedium) }
                items(state.favoriteRoutes, key = { "route-${it.id}" }) { route ->
                    // 한 번 탭으로 경로 결과(⑨)
                    OutlinedCard(onClick = { navController.navigate(RouteFlowGraph(favoriteRouteId = route.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(favoriteRouteTitle(route), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** "출발 → 도착", 경유지가 있으면 "출발 → 경유 → 도착". */
private fun favoriteRouteTitle(route: FavoriteRouteEntity): String =
    listOfNotNull(route.fromName, route.viaName, route.toName).joinToString(" → ")

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
