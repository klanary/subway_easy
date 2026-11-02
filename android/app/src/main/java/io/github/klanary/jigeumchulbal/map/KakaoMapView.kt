package io.github.klanary.jigeumchulbal.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.ui.common.Copy

/** 서울시청. 현재 위치를 모를 때의 처음 카메라 위치. */
val SEOUL_CITY_HALL = GeoPoint(37.5666, 126.9784)

/**
 * 카카오 지도 SDK v2(View 기반)를 Compose로 감싼다(스펙 7.3).
 * 지도 초기화에 실패하면 지도 자리에 문구만 보여주고 앱의 나머지는 그대로 동작한다(스펙 4.5).
 */
@Composable
fun KakaoMapView(
    modifier: Modifier = Modifier,
    initialCenter: GeoPoint = SEOUL_CITY_HALL,
    initialZoom: Int = 15,
    onReady: (KakaoMap) -> Unit,
    onCameraIdle: ((GeoPoint) -> Unit)? = null,
) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(Copy.MAP_FAILED)
        }
        return
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)

    val mapView = remember {
        runCatching {
            MapView(context).apply {
                start(
                    object : MapLifeCycleCallback() {
                        override fun onMapDestroy() = Unit

                        override fun onMapError(error: Exception) {
                            failed = true
                        }
                    },
                    object : KakaoMapReadyCallback() {
                        override fun getPosition(): LatLng = LatLng.from(initialCenter.lat, initialCenter.lng)

                        override fun getZoomLevel(): Int = initialZoom

                        override fun onMapReady(map: KakaoMap) {
                            map.setOnCameraMoveEndListener { _, position, _ ->
                                currentOnCameraIdle?.invoke(position.position.toGeoPoint())
                            }
                            currentOnReady(map)
                        }
                    },
                )
            }
        }.onFailure { failed = true }.getOrNull()
    }
    if (mapView == null) return

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.resume()
                Lifecycle.Event.ON_PAUSE -> mapView.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}

fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)
