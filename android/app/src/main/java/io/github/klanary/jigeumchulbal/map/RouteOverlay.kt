package io.github.klanary.jigeumchulbal.map

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLinePattern
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import io.github.klanary.jigeumchulbal.R
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.RouteDto
import io.github.klanary.jigeumchulbal.data.remote.StepMode
import io.github.klanary.jigeumchulbal.ui.common.color

data class PolylineSpec(val points: List<GeoPoint>, val color: Long, val dashed: Boolean)

enum class MarkerKind { START, TRANSFER, END }

data class MarkerSpec(val point: GeoPoint, val kind: MarkerKind)

/** 지도에 그릴 것: 구간별 선, 출발·환승·도착 마커, 카메라를 맞출 좌표들. */
data class OverlayModel(
    val polylines: List<PolylineSpec>,
    val markers: List<MarkerSpec>,
    val bounds: List<GeoPoint>,
)

/**
 * 경로를 지도 그림으로 바꾼다(스펙 7.3). 좌표가 2개 이상인 단계마다 선 하나:
 * 도보는 회색 점선, 지하철은 호선 색, 버스는 초록. 환승 마커는 두 번째 대중교통 단계부터 그 단계의 첫 좌표다.
 */
fun buildOverlay(route: RouteDto): OverlayModel {
    val steps = route.legs.flatMap { it.steps }
    val polylines = steps
        .map { step -> step to step.path.mapNotNull { it.toGeoPoint() } }
        .filter { (_, points) -> points.size >= 2 }
        .map { (step, points) -> PolylineSpec(points, step.color(), dashed = step.mode == StepMode.WALK) }
    val allPoints = polylines.flatMap { it.points }

    val markers = buildList {
        allPoints.firstOrNull()?.let { add(MarkerSpec(it, MarkerKind.START)) }
        steps.filter { it.mode != StepMode.WALK }
            .drop(1)
            .mapNotNull { step -> step.path.firstOrNull()?.toGeoPoint() }
            .forEach { add(MarkerSpec(it, MarkerKind.TRANSFER)) }
        allPoints.lastOrNull()?.let { add(MarkerSpec(it, MarkerKind.END)) }
    }
    return OverlayModel(polylines, markers, allPoints)
}

private fun List<Double>.toGeoPoint(): GeoPoint? = if (size >= 2) GeoPoint(this[0], this[1]) else null

/** 이전에 그린 경로를 지우고 [overlay]를 그린 뒤 카메라를 경로 전체에 맞춘다. */
fun KakaoMap.render(overlay: OverlayModel, context: Context) {
    val density = Resources.getSystem().displayMetrics.density
    val routeLayer = routeLineManager?.layer ?: return
    routeLayer.removeAll()
    val dot = dotBitmap(sizePx = (6 * density).toInt(), color = 0xFF757575.toInt())
    overlay.polylines.forEach { line ->
        val points = line.points.map { LatLng.from(it.lat, it.lng) }
        val style = if (line.dashed) {
            // 도보: 투명한 선 위에 점을 찍어 점선처럼 보이게 한다.
            RouteLineStyle.from(6 * density, android.graphics.Color.TRANSPARENT, RouteLinePattern.from(dot, 10 * density))
        } else {
            RouteLineStyle.from(8 * density, line.color.toInt())
        }
        routeLayer.addRouteLine(RouteLineOptions.from(RouteLineSegment.from(points, RouteLineStyles.from(style))))
    }

    labelManager?.layer?.let { labels ->
        labels.removeAll()
        overlay.markers.forEach { marker ->
            val bitmap = markerBitmap(context, marker.kind.drawable()) ?: return@forEach
            labels.addLabel(LabelOptions.from(LatLng.from(marker.point.lat, marker.point.lng)).setStyles(bitmap))
        }
    }

    if (overlay.bounds.isNotEmpty()) {
        val points = overlay.bounds.map { LatLng.from(it.lat, it.lng) }.toTypedArray()
        moveCamera(CameraUpdateFactory.fitMapPoints(points, (48 * density).toInt()))
    }
}

@DrawableRes
private fun MarkerKind.drawable(): Int = when (this) {
    MarkerKind.START -> R.drawable.marker_start
    MarkerKind.TRANSFER -> R.drawable.marker_transfer
    MarkerKind.END -> R.drawable.marker_end
}

private fun markerBitmap(context: Context, @DrawableRes id: Int): Bitmap? =
    ContextCompat.getDrawable(context, id)?.toBitmap()

private fun dotBitmap(sizePx: Int, color: Int): Bitmap =
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bitmap).drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
    }
