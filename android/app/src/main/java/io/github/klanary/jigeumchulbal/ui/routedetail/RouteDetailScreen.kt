package io.github.klanary.jigeumchulbal.ui.routedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.map.KakaoMapView
import io.github.klanary.jigeumchulbal.map.SEOUL_CITY_HALL
import io.github.klanary.jigeumchulbal.map.buildOverlay
import io.github.klanary.jigeumchulbal.map.render
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.hasTransit
import io.github.klanary.jigeumchulbal.session.withoutPaths
import io.github.klanary.jigeumchulbal.ui.SessionDest
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.color
import io.github.klanary.jigeumchulbal.ui.common.formatDuration
import io.github.klanary.jigeumchulbal.ui.common.formatFare
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowViewModel
import io.github.klanary.jigeumchulbal.ui.session.rememberStartSession

/**
 * 경로 상세(스펙 3.1 ④): 위는 구간별 색 경로선과 출발·환승·도착 마커를 그린 지도, 아래는 끌어 올리는 단계 안내.
 * [출발(하차 알림)]은 지금 탄다고 보고 하차·환승 알림을 건다(⑤). [승차 전 알림]·[★]는 P4 Task 7·12가 더한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(viewModel: RouteFlowViewModel, routeId: String, navController: NavController) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(routeId) { viewModel.select(routeId) }
    val route = state.selectedRoute
    val startSession = rememberStartSession(onStarted = { navController.navigate(SessionDest) })

    val back: @Composable () -> Unit = {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
        }
    }

    if (route == null) {
        // 앱이 백그라운드에서 정리돼 검색 결과가 사라진 경우 등
        Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = back) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(Copy.ROUTE_GONE)
            }
        }
        return
    }

    BottomSheetScaffold(
        topBar = {
            TopAppBar(
                title = { Text("${formatDuration(route.totalSec)} · ${formatFare(route.fare)}") },
                navigationIcon = back,
            )
        },
        sheetPeekHeight = 220.dp,
        sheetContent = {
            // 도보만인 경로는 내릴 곳이 없어 하차 알림을 걸지 않는다.
            if (route.hasTransit()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = { startSession { settings -> SessionEvent.StartRiding(route.withoutPaths(), settings) } }) {
                        Text(Copy.START_ALIGHT_ALERT)
                    }
                }
            }
            StepList(route)
        },
    ) { padding ->
        RouteMap(route, Modifier.padding(padding))
    }
}

@Composable
private fun RouteMap(route: RouteDto, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val overlay = remember(route) { buildOverlay(route) }
    KakaoMapView(
        modifier = modifier,
        initialCenter = overlay.bounds.firstOrNull() ?: SEOUL_CITY_HALL,
        onReady = { map -> map.render(overlay, context) },
    )
}

@Composable
private fun StepList(route: RouteDto) {
    val steps = remember(route) { route.legs.flatMap { it.steps } }
    LazyColumn(Modifier.padding(bottom = 16.dp)) {
        itemsIndexed(steps) { _, step ->
            ListItem(
                leadingContent = {
                    Box(Modifier.size(12.dp).background(Color(step.color()), CircleShape))
                },
                headlineContent = { Text(stepTitle(step)) },
                supportingContent = step.guidance.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            )
        }
    }
}
