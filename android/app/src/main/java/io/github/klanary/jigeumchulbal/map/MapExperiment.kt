package io.github.klanary.jigeumchulbal.map

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles

/**
 * 1단계 지도 실험 경로 (위도, 경도).
 *
 * TODO(P1 Task 4 Step 7 이후): kakao_route_subway_only.json의 첫 지하철 단계 좌표 10개 이하로 바꾼다.
 * 지금은 샘플이 없어 2호선 홍대입구 → 강남 사이 공공 역들의 대략적인 좌표를 쓴다.
 */
val EXPERIMENT_PATH: List<Pair<Double, Double>> = listOf(
    37.5571 to 126.9245, // 홍대입구
    37.5349 to 126.9025, // 당산
    37.5088 to 126.8913, // 신도림
    37.4852 to 126.9015, // 구로디지털단지
    37.4842 to 126.9297, // 신림
    37.4812 to 126.9527, // 서울대입구
    37.4766 to 126.9816, // 사당
    37.4814 to 126.9976, // 방배
    37.4934 to 127.0146, // 교대
    37.4979 to 127.0276, // 강남
)

private const val LINE_2_GREEN = 0xFF00A84D.toInt()

/** 지도를 띄우고 [EXPERIMENT_PATH]로 경로선 1개를 그린다. 지도 초기화에 실패하면 원인을 보여준다. */
@Composable
fun MapExperiment(modifier: Modifier = Modifier) {
    var error by remember { mutableStateOf<String?>(null) }
    val message = error
    if (message == null) {
        ExperimentMapView(onError = { error = it }, modifier = modifier)
    } else {
        Box(
            modifier = modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("지도를 불러오지 못했어요: $message")
        }
    }
}

@Composable
private fun ExperimentMapView(onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).apply {
            start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() = Unit

                    override fun onMapError(error: Exception) {
                        onError(error.message ?: error.javaClass.simpleName)
                    }
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(map: KakaoMap) = drawExperimentRoute(map)
                },
            )
        }
    }
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

private fun drawExperimentRoute(map: KakaoMap) {
    val points = EXPERIMENT_PATH.map { (lat, lng) -> LatLng.from(lat, lng) }
    val styles = RouteLineStyles.from(RouteLineStyle.from(12f, LINE_2_GREEN))
    map.routeLineManager?.layer?.addRouteLine(
        RouteLineOptions.from(RouteLineSegment.from(points, styles)),
    )
    map.moveCamera(CameraUpdateFactory.fitMapPoints(points.toTypedArray(), 64))
}
