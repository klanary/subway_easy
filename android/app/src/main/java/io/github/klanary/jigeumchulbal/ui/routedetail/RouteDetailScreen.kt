package io.github.klanary.jigeumchulbal.ui.routedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.data.local.AppSettings
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.session.SessionEvent
import io.github.klanary.jigeumchulbal.session.hasTransit
import io.github.klanary.jigeumchulbal.session.waitStartFromRoute
import io.github.klanary.jigeumchulbal.session.withoutPaths
import io.github.klanary.jigeumchulbal.App
import io.github.klanary.jigeumchulbal.routine.RoutineDraft
import io.github.klanary.jigeumchulbal.ui.RoutineEditDest
import io.github.klanary.jigeumchulbal.ui.SessionDest
import kotlinx.serialization.json.Json
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.color
import io.github.klanary.jigeumchulbal.ui.common.formatDuration
import io.github.klanary.jigeumchulbal.ui.common.formatFare
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowViewModel
import io.github.klanary.jigeumchulbal.ui.session.rememberStartSession

/**
 * 경로 상세(재설계 스펙 4.5, 지도 없음): 위에 알림 버튼과 [★ 자주 가는 경로], 아래에 단계 목록.
 * [승차 전 알림]은 첫 탑승역의 다음 열차로 출발할 때를 알리고, [출발(하차 알림)]은 지금 탄다고 보고
 * 하차·환승 알림을 건다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(viewModel: RouteFlowViewModel, routeId: String, navController: NavController) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(routeId) { viewModel.select(routeId) }
    val route = state.selectedRoute
    val startSession = rememberStartSession(onStarted = { navController.navigate(SessionDest) })
    val container = (LocalContext.current.applicationContext as App).container

    Scaffold(
        topBar = {
            TopAppBar(
                title = { route?.let { Text("${formatDuration(it.totalSec)} · ${formatFare(it.fare)}") } },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        if (route == null) {
            // 앱이 백그라운드에서 정리돼 검색 결과가 사라진 경우 등
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(Copy.ROUTE_GONE)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                // 도보만인 경로는 탈 열차도 내릴 곳도 없어 알림을 걸지 않는다.
                if (route.hasTransit()) SessionButtons(route, startSession)
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = viewModel::toggleFavorite) {
                        Text(if (state.isFavorite) Copy.REMOVE_FAVORITE else Copy.ADD_FAVORITE)
                    }
                    val origin = state.origin
                    val dest = state.dest
                    if (route.hasTransit() && origin != null && dest != null) {
                        TextButton(onClick = {
                            // 루틴 편집 화면에 이 경로를 넘긴다(재설계 스펙 4.5, 4.6).
                            container.pendingRoutine = RoutineDraft(origin, state.via, dest, routeJson(route))
                            navController.navigate(RoutineEditDest())
                        }) { Text(Copy.MAKE_ROUTINE) }
                    }
                }
                HorizontalDivider()
            }
            stepItems(route, viaName = state.via?.name)
        }
    }
}

/**
 * [지금 출발 알림](첫 탑승역의 열차 정보가 있을 때만)과 [하차 알림 시작], "환승·하차 진동 알림" 스위치(기본 끔).
 * 스위치가 꺼져 있으면 [하차 알림 시작]은 누를 수 없고, [지금 출발 알림]은 [탑승했어요]에서 끝난다(재설계 스펙 D2, 4.5).
 */
@Composable
private fun SessionButtons(route: RouteDto, startSession: ((AppSettings) -> SessionEvent) -> Unit) {
    val waitStart = remember(route) { waitStartFromRoute(route) }
    var vibration by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Copy.VIBRATION_ALERTS, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = vibration, onCheckedChange = { vibration = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = waitStart != null,
                onClick = {
                    waitStart?.let { start ->
                        startSession { settings ->
                            SessionEvent.StartWaiting(
                                start.target, start.mode, route.withoutPaths(), start.travelSec, settings, alightAlerts = vibration,
                            )
                        }
                    }
                },
            ) { Text(Copy.PRE_BOARDING_ALERT) }
            Button(
                enabled = vibration,
                onClick = { startSession { settings -> SessionEvent.StartRiding(route.withoutPaths(), settings) } },
            ) { Text(Copy.START_ALIGHT_ALERT) }
        }
        if (waitStart == null) Text(Copy.NO_REALTIME_FOR_ROUTE, style = MaterialTheme.typography.bodySmall)
    }
}

private fun LazyListScope.stepItems(route: RouteDto, viaName: String?) {
    route.legs.forEachIndexed { legIndex, leg ->
        // 경유지가 있으면 두 구간 사이에 구분선을 둔다.
        if (legIndex > 0) {
            item {
                HorizontalDivider()
                ListItem(headlineContent = { Text(listOfNotNull(Copy.VIA_POINT, viaName).joinToString(": ")) })
                HorizontalDivider()
            }
        }
        itemsIndexed(leg.steps) { _, step ->
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

private val routeJsonFormat = Json { encodeDefaults = false }

/** 루틴에 저장할 경로: 지도 좌표(path)를 뺀 JSON. */
private fun routeJson(route: RouteDto): String = routeJsonFormat.encodeToString(route.withoutPaths())
