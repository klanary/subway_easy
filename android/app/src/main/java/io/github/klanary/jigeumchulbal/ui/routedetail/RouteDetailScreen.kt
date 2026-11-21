package io.github.klanary.jigeumchulbal.ui.routedetail

import io.github.klanary.jigeumchulbal.ui.theme.ButtonShape
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
import androidx.compose.material3.FilterChip
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
import io.github.klanary.jigeumchulbal.data.remote.StepMode
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.klanary.jigeumchulbal.ui.common.LineBadge
import io.github.klanary.jigeumchulbal.ui.common.color
import io.github.klanary.jigeumchulbal.ui.common.formatDuration
import io.github.klanary.jigeumchulbal.ui.routeedit.RouteFlowState
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
    // 구간마다 고른 열차로 바꾼 경로(열차 고르기 설계 2026-11-18). 제목·알림·루틴이 이것을 쓴다.
    val route = state.pickedRoute
    val startSession = rememberStartSession(onStarted = { navController.navigate(SessionDest) })
    val container = (LocalContext.current.applicationContext as App).container

    RouteDetailContent(
        state = state,
        onBack = { navController.popBackStack() },
        onToggleFavorite = viewModel::toggleFavorite,
        onMakeRoutine = { picked ->
            val origin = state.origin
            val dest = state.dest
            if (origin != null && dest != null) {
                // 루틴 편집 화면에 이 경로를 넘긴다(재설계 스펙 4.5, 4.6).
                container.pendingRoutine = RoutineDraft(origin, state.via, dest, routeJson(picked))
                navController.navigate(RoutineEditDest())
            }
        },
        picks = TrainPickActions(
            move = viewModel::moveTrain,
            loadLater = viewModel::loadLater,
            filter = viewModel::setExpressFilter,
            unpin = viewModel::unpin,
        ),
        sessionButtons = { picked, hasPicks -> SessionButtons(picked, hasPicks, startSession) },
    )
}

/** 열차 고르기 줄의 동작(구간 번호를 받는다). */
internal class TrainPickActions(
    val move: (Int, Int) -> Unit = { _, _ -> },
    val loadLater: (Int) -> Unit = {},
    val filter: (Int, ExpressFilter) -> Unit = { _, _ -> },
    val unpin: (Int) -> Unit = {},
)

/** 경로 상세를 그린다(상태만 받는다). 알림 시작 버튼은 권한·대화상자를 다뤄 [sessionButtons] 자리로 받는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteDetailContent(
    state: RouteFlowState,
    now: Long = System.currentTimeMillis(),
    onBack: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onMakeRoutine: (RouteDto) -> Unit = {},
    picks: TrainPickActions = TrainPickActions(),
    sessionButtons: @Composable (RouteDto, Boolean) -> Unit = { _, _ -> },
) {
    val route = state.pickedRoute
    Scaffold(
        topBar = {
            TopAppBar(
                // "마포 → 노량진 · 9분"(Task 7 합의)
                title = {
                    route?.let {
                        val name = listOfNotNull(state.origin?.name, state.dest?.name).joinToString(" → ")
                        Text(listOf(name, formatDuration(it.totalSec)).filter { p -> p.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
        // 알림 버튼은 아래에 고정해 스크롤해도 보인다(Task 7 합의). 도보만인 경로는 알림을 걸지 않는다.
        bottomBar = {
            if (route != null && route.hasTransit()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Box(Modifier.navigationBarsPadding()) { sessionButtons(route, state.hasPicks) }
                }
            }
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
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onToggleFavorite) {
                        Text(if (state.isFavorite) Copy.REMOVE_FAVORITE else Copy.ADD_FAVORITE)
                    }
                    val origin = state.origin
                    val dest = state.dest
                    if (route.hasTransit() && origin != null && dest != null) {
                        TextButton(onClick = { onMakeRoutine(route) }) { Text(Copy.MAKE_ROUTINE) }
                    }
                }
                HorizontalDivider()
            }
            stepItems(route, viaName = state.via?.name) { k ->
                state.picks.getOrNull(k)?.let {
                    val filter = state.choices.getOrNull(k)?.filter ?: ExpressFilter.ALL
                    TrainPickRow(it.copy(needsMore = it.needsMore && k !in state.exhausted), k, filter, state.loadingMore, k in state.exhausted, now, picks)
                }
            }
        }
    }
}


/**
 * [지금 출발 알림](첫 탑승역의 열차 정보가 있을 때만)과 [하차 알림 시작], "환승·하차 진동 알림" 스위치(기본 끔).
 * 스위치가 꺼져 있으면 [하차 알림 시작]은 누를 수 없고, [지금 출발 알림]은 [탑승했어요]에서 끝난다(재설계 스펙 D2, 4.5).
 */
@Composable
private fun SessionButtons(route: RouteDto, hasPicks: Boolean, startSession: ((AppSettings) -> SessionEvent) -> Unit) {
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
                shape = ButtonShape,
                enabled = waitStart != null,
                onClick = {
                    waitStart?.let { start ->
                        startSession { settings ->
                            SessionEvent.StartWaiting(
                                start.target, start.mode, route.withoutPaths(), start.travelSec, settings, alightAlerts = vibration,
                                // 직접 고른 열차가 있을 때만 그 열차에 맞춘다(설계 §5.4 "고르지 않고 시작하면 지금과 같다")
                                chosen = start.chosen.takeIf { hasPicks },
                            )
                        }
                    }
                },
            ) { Text(Copy.PRE_BOARDING_ALERT) }
            Button(
                shape = ButtonShape,
                enabled = vibration,
                onClick = { startSession { settings -> SessionEvent.StartRiding(route.withoutPaths(), settings) } },
            ) { Text(Copy.START_ALIGHT_ALERT) }
        }
        if (waitStart == null) Text(Copy.NO_REALTIME_FOR_ROUTE, style = MaterialTheme.typography.bodySmall)
    }
}

private fun LazyListScope.stepItems(route: RouteDto, viaName: String?, pickRow: @Composable (Int) -> Unit) {
    var subway = 0
    route.legs.forEachIndexed { legIndex, leg ->
        // 경유지가 있으면 두 구간 사이에 구분선을 둔다.
        if (legIndex > 0) {
            item {
                HorizontalDivider()
                ListItem(headlineContent = { Text(listOfNotNull(Copy.VIA_POINT, viaName).joinToString(": ")) })
                HorizontalDivider()
            }
        }
        // 지하철 단계의 구간 번호(열차 고르기 상태의 위치)
        val segments = leg.steps.map { if (it.mode == StepMode.SUBWAY) subway++ else -1 }
        itemsIndexed(leg.steps) { i, step ->
            ListItem(
                leadingContent = {
                    Box(Modifier.size(12.dp).background(Color(step.color()), CircleShape))
                },
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step.mode == StepMode.SUBWAY) step.lineName?.let { LineBadge(it) }
                        Text(stepHead(step), style = MaterialTheme.typography.titleMedium)
                    }
                },
                supportingContent = {
                    Column {
                        stepDetail(step)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        step.guidance.takeIf { it.isNotBlank() }?.let { Text(it) }
                        // 지하철 구간은 열차 고르기 줄(이전/다음·급행·고정·경고), 그 밖은 운행표 열차 줄
                        if (segments[i] >= 0) {
                            pickRow(segments[i])
                        } else {
                            trainLine(step)?.let {
                                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        // 환승역에서 타고 온 열차가 도착한 뒤 출발하는 열차 두 대
                        transferLine(step)?.let {
                            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
            )
        }
    }
}

/**
 * 구간 하나의 열차 고르기(열차 고르기 설계 §5.3):
 * [◀ 이전] 08:05 출발 → 08:09 도착 · 5301 [다음 ▶], 급행과 일반이 함께 다니면 전체/급행/일반,
 * 고정했으면 📌 고정됨 [고정 풀기], 어긋나면 ⚠ 경고, 맞는 열차가 없으면 더 늦은 열차 불러오기.
 */
@Composable
private fun TrainPickRow(
    pick: PickedSegment,
    k: Int,
    current: ExpressFilter,
    loadingMore: Boolean,
    exhausted: Boolean,
    now: Long,
    actions: TrainPickActions,
) {
    Column {
        // 열차 고르기 줄(Task 7 합의): 아이콘 버튼, 가운데 시각을 크게, 번호·급행·떠남은 작게.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = pick.canPrev, onClick = { actions.move(k, -1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = Copy.PREV_TRAIN)
            }
            // 이미 떠난 열차는 흐리게(고르면 알림이 바로 "놓쳤어요"가 된다)
            val train = pick.train
            val departed = train?.let { isDeparted(it, now) } == true
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    train?.let { pickTimes(it) } ?: Copy.NO_LATER_TRAIN,
                    color = if (departed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = if (train != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (train != null) FontWeight.Bold else null,
                )
                val meta = listOfNotNull(train?.let { pickMeta(it) }?.takeIf { it.isNotEmpty() }, Copy.DEPARTED.takeIf { departed })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                enabled = pick.canNext || (pick.needsMore && !loadingMore),
                onClick = { if (pick.canNext) actions.move(k, 1) else actions.loadLater(k) },
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = Copy.NEXT_TRAIN)
            }
        }
        if (pick.hasExpress && pick.hasLocal) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ExpressFilter.ALL to Copy.FILTER_ALL, ExpressFilter.EXPRESS to Copy.FILTER_EXPRESS, ExpressFilter.LOCAL to Copy.FILTER_LOCAL)
                    .forEach { (filter, label) ->
                        FilterChip(selected = current == filter, onClick = { actions.filter(k, filter) }, label = { Text(label) })
                    }
            }
        }
        if (pick.pinned) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Copy.PINNED, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { actions.unpin(k) }) { Text(Copy.UNPIN) }
            }
        }
        pick.warning?.let {
            Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // 더 불러와도 새 열차가 없었다: 마지막 열차(최종 리뷰 #4)
        if (exhausted && pick.train != null && !pick.canNext) {
            Text(Copy.NO_LATER_TRAIN, style = MaterialTheme.typography.bodySmall)
        }
        if (pick.train == null && pick.needsMore) {
            TextButton(enabled = !loadingMore, onClick = { actions.loadLater(k) }) { Text(Copy.LOAD_LATER) }
        }
    }
}

private val routeJsonFormat = Json { encodeDefaults = false }

/** 루틴에 저장할 경로: 지도 좌표(path)를 뺀 JSON. */
private fun routeJson(route: RouteDto): String = routeJsonFormat.encodeToString(route.withoutPaths())
