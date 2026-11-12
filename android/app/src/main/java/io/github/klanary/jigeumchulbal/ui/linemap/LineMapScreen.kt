package io.github.klanary.jigeumchulbal.ui.linemap

import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.caverock.androidsvg.SVG
import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.location.rememberLocationPermissionRequest
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.ui.RouteFlowGraph
import io.github.klanary.jigeumchulbal.ui.SearchDest
import io.github.klanary.jigeumchulbal.ui.SessionDest
import io.github.klanary.jigeumchulbal.ui.SettingsDest
import io.github.klanary.jigeumchulbal.ui.OnPickResult
import io.github.klanary.jigeumchulbal.ui.RoutineEditDest
import io.github.klanary.jigeumchulbal.ui.routine.RoutineList
import io.github.klanary.jigeumchulbal.ui.routine.RoutineListViewModel
import io.github.klanary.jigeumchulbal.ui.routine.nextRoutineLine
import java.time.LocalDate
import java.time.LocalTime
import io.github.klanary.jigeumchulbal.ui.toGraph
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.home.HomeViewModel
import io.github.klanary.jigeumchulbal.ui.session.SessionViewModel
import io.github.klanary.jigeumchulbal.ui.session.sessionHeadline
import io.github.klanary.jigeumchulbal.ui.session.sessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 누르기 판정 반경(재설계 스펙 8): 화면에서 24dp. */
private val HIT_RADIUS = 24.dp

/** 처음 보일 배율과 최대 배율(화면 px / 노선도 px를 dp 기준으로). 역 이름(노선도 20px)이 약 9dp, 24dp가 된다. */
private const val INITIAL_SCALE_PER_DENSITY = 0.45f
private const val MAX_SCALE_PER_DENSITY = 1.2f

/**
 * 홈 = 노선도(재설계 스펙 4.1). 끌기, 두 손가락 확대·축소, 두 번 누르면 확대. 역을 누르면 역 시트가 열린다.
 * 위에 역 검색과 [현재 위치]·설정, 출발·도착을 고르는 중이면 그 띠, 아래에 접히는 패널([루틴] [자주 가는 경로]).
 * 출발·도착이 정해지면 경로 결과로 간다.
 */
@Composable
fun LineMapScreen(
    navController: NavController,
    entry: NavBackStackEntry,
    viewModel: LineMapViewModel = lineMapViewModel(),
    home: HomeViewModel = appViewModel { HomeViewModel(it.favoriteRepository, it.serverRepository) },
    sessionViewModel: SessionViewModel = sessionViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 역 검색에서 고른 역: 그 역으로 가서 시트를 연다(재설계 스펙 4.3).
    entry.OnPickResult { viewModel.focusOn(it.station.id) }
    LaunchedEffect(state.readyTrip) {
        val trip = state.readyTrip ?: return@LaunchedEffect
        viewModel.consumeReadyTrip()
        navController.navigate(trip.toGraph())
    }
    val homeState by home.state.collectAsStateWithLifecycle()
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val requestLocation = rememberLocationPermissionRequest { granted -> if (granted) viewModel.locate() }
    val context = LocalContext.current
    val picture by produceState<Picture?>(null) {
        value = withContext(Dispatchers.Default) {
            SVG.getFromAsset(context.assets, LINEMAP_SVG_ASSET).renderToPicture()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val map = state.map
        val drawn = picture
        if (map == null || drawn == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            LineMapCanvas(
                map = map,
                picture = drawn,
                focus = state.focus,
                onFocusHandled = { station ->
                    viewModel.consumeFocus()
                    viewModel.selectStation(station.id)
                },
                onStationTap = { viewModel.selectStation(it.id) },
            )
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBar(
                onSearch = { navController.navigate(SearchDest()) },
                onLocate = requestLocation,
                onRoute = { navController.navigate(RouteFlowGraph()) },
                onSettings = { navController.navigate(SettingsDest) },
            )
            state.draft.banner()?.let { banner -> TripBanner(banner, onCancel = viewModel::cancelTrip) }
            if (session != SessionState.Idle) SessionBanner(sessionViewModel) { navController.navigate(SessionDest) }
            state.message?.let { message ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.clickable { viewModel.consumeMessage() }.padding(12.dp),
                    )
                }
                LaunchedEffect(message) {
                    delay(3_000)
                    viewModel.consumeMessage()
                }
            }
        }
        BottomPanel(
            favoriteRoutes = homeState.favoriteRoutes,
            onFavorite = { navController.navigate(RouteFlowGraph(favoriteRouteId = it.id)) },
            onEditRoutine = { navController.navigate(RoutineEditDest(it)) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    state.selected?.let { stationId ->
        StationSheet(stationId, state.draft, onRole = viewModel::setRole, onDismiss = viewModel::closeSheet)
    }
}

/** "출발: 마포 · 도착: (역을 고르세요)"와 [취소]. */
@Composable
private fun TripBanner(text: String, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text(Copy.CANCEL) }
        }
    }
}

@Composable
private fun lineMapViewModel(): LineMapViewModel {
    val context = LocalContext.current
    return appViewModel {
        LineMapViewModel(
            loadStations = {
                parseLineMapStations(context.assets.open(LINEMAP_STATIONS_ASSET).bufferedReader().use { r -> r.readText() })
            },
            stations = it.stationRepository,
            location = it.locationProvider,
        )
    }
}

@Composable
private fun LineMapCanvas(
    map: LineMapStations,
    picture: Picture,
    focus: MapStation?,
    onFocusHandled: (MapStation) -> Unit,
    onStationTap: (MapStation) -> Unit,
) {
    val density = LocalDensity.current.density
    val hitRadiusPx = with(LocalDensity.current) { HIT_RADIUS.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf<MapTransform?>(null) }
    val minScale = if (size == IntSize.Zero) 0f else fitScale(map.width, map.height, size.width.toFloat(), size.height.toFloat())
    val maxScale = MAX_SCALE_PER_DENSITY * density

    LaunchedEffect(size) {
        if (size != IntSize.Zero && transform == null) {
            // 처음에는 서울 도심(시청)을 가운데에 둔다.
            val center = map.find("시청") ?: MapStation("", map.width / 2, map.height / 2)
            transform = MapTransform(INITIAL_SCALE_PER_DENSITY * density, 0f, 0f)
                .centerOn(center.x, center.y, size.width.toFloat(), size.height.toFloat())
        }
    }
    LaunchedEffect(focus, size) {
        val target = focus ?: return@LaunchedEffect
        val current = transform ?: return@LaunchedEffect
        transform = current.copy(scale = maxOf(current.scale, INITIAL_SCALE_PER_DENSITY * density))
            .centerOn(target.x, target.y, size.width.toFloat(), size.height.toFloat())
        onFocusHandled(target)
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(map, minScale, maxScale) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform = transform?.zoomAround(centroid.x, centroid.y, zoom, minScale, maxScale)?.pan(pan.x, pan.y)
                }
            }
            .pointerInput(map, minScale, maxScale) {
                detectTapGestures(
                    onDoubleTap = { at -> transform = transform?.zoomAround(at.x, at.y, 2f, minScale, maxScale) },
                    onTap = { at ->
                        val t = transform ?: return@detectTapGestures
                        val (x, y) = t.toMap(at.x, at.y)
                        hitStation(map.stations, x, y, hitRadiusPx / t.scale)?.let(onStationTap)
                    },
                )
            },
    ) {
        val t = transform ?: return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(t.offsetX, t.offsetY)
            native.scale(t.scale, t.scale)
            native.drawPicture(picture)
            native.restore()
        }
    }
}

@Composable
private fun TopBar(onSearch: () -> Unit, onLocate: () -> Unit, onRoute: () -> Unit, onSettings: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(onClick = onSearch).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text(Copy.SEARCH_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRoute) { Text(Copy.FIND_ROUTE) }
            IconButton(onClick = onLocate) { Icon(Icons.Filled.LocationOn, contentDescription = Copy.LOCATE) }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = Copy.SETTINGS) }
        }
    }
}

/** 접히는 아래 패널: [루틴] [자주 가는 경로]. 접혀 있으면 오늘의 다음 루틴 한 줄을 보인다. */
@Composable
private fun BottomPanel(
    favoriteRoutes: List<FavoriteRouteEntity>,
    onFavorite: (FavoriteRouteEntity) -> Unit,
    onEditRoutine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val routines by appViewModel { RoutineListViewModel(it.routineRepository) }.list.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                    Tab(selected = tab == 0, onClick = { tab = 0; expanded = true }, text = { Text(Copy.ROUTINES) })
                    Tab(selected = tab == 1, onClick = { tab = 1; expanded = true }, text = { Text(Copy.FAVORITE_ROUTES) })
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = if (expanded) Copy.COLLAPSE else Copy.EXPAND,
                    )
                }
            }
            if (!expanded) {
                val now = LocalTime.now()
                nextRoutineLine(routines, now.hour * 60 + now.minute, LocalDate.now().dayOfWeek)?.let { line ->
                    Text(line, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (expanded) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    when (tab) {
                        0 -> item { RoutineList(onEdit = onEditRoutine) }
                        else -> if (favoriteRoutes.isEmpty()) {
                            item { ListItem(headlineContent = { Text(Copy.NO_FAVORITE_ROUTES) }) }
                        } else {
                            items(favoriteRoutes, key = { it.id }) { route ->
                                ListItem(
                                    headlineContent = { Text(favoriteRouteTitle(route)) },
                                    modifier = Modifier.clickable { onFavorite(route) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "출발 → 도착", 경유지가 있으면 "출발 → 경유 → 도착". */
fun favoriteRouteTitle(route: FavoriteRouteEntity): String =
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
