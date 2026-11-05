package io.github.klanary.jigeumchulbal.ui.mappick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.camera.CameraUpdateFactory
import io.github.klanary.jigeumchulbal.App
import io.github.klanary.jigeumchulbal.map.KakaoMapView
import io.github.klanary.jigeumchulbal.map.toGeoPoint
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.Copy
import io.github.klanary.jigeumchulbal.ui.common.UiState
import io.github.klanary.jigeumchulbal.ui.common.appViewModel
import io.github.klanary.jigeumchulbal.ui.returnPick
import kotlinx.coroutines.launch

/**
 * 지도 핀 선택. 가운데 고정 핀을 움직여 맞추면 주소와 가장 가까운 역을 보여준다.
 * [slot]이 있으면 [출발] [경유] [도착], 없으면(설정의 내 장소) [이 위치로 지정]을 보여준다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickScreen(
    slot: Slot?,
    navController: NavController,
    viewModel: MapPickViewModel = appViewModel { MapPickViewModel(it.placeRepository, it.recentSearchRepository) },
) {
    val card by viewModel.card.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val location = (LocalContext.current.applicationContext as App).container.locationProvider
    var map by remember { mutableStateOf<KakaoMap?>(null) }

    // 처음 카메라는 현재 위치. 모르면 서울시청에 머문다.
    LaunchedEffect(map) {
        val ready = map ?: return@LaunchedEffect
        ready.cameraPosition?.position?.let { viewModel.onCameraIdle(it.toGeoPoint()) }
        val here = location.current() ?: return@LaunchedEffect
        ready.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(here.lat, here.lng)))
        viewModel.onCameraIdle(here)
    }

    fun pick(target: Slot?) {
        scope.launch { navController.returnPick(viewModel.pick(target)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Copy.PICK_ON_MAP) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Copy.BACK)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                KakaoMapView(onReady = { map = it }, onCameraIdle = viewModel::onCameraIdle)
                // 가운데 고정 핀: 지도를 움직여 위치를 맞춘다.
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 24.dp),
                )
            }
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val state = card) {
                        UiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is UiState.Content -> {
                            Text(state.data.addressText, style = MaterialTheme.typography.titleMedium)
                            Text(state.data.stationText, style = MaterialTheme.typography.bodyMedium)
                        }
                        is UiState.Error -> Text(state.message)
                        UiState.Offline -> Text(Copy.OFFLINE)
                        UiState.Empty -> Text(Copy.NO_ADDRESS)
                    }
                    // 주소 조회가 실패해도 핀 좌표로는 지정할 수 있다(스펙 3.4 최소 버전과 같은 동작).
                    if (slot == null) {
                        Button(onClick = { pick(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(Copy.USE_THIS_PLACE)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pick(Slot.ORIGIN) }, modifier = Modifier.weight(1f)) { Text(Copy.ORIGIN) }
                            Button(onClick = { pick(Slot.VIA) }, modifier = Modifier.weight(1f)) { Text(Copy.VIA) }
                            Button(onClick = { pick(Slot.DEST) }, modifier = Modifier.weight(1f)) { Text(Copy.DEST) }
                        }
                    }
                }
            }
        }
    }
}
