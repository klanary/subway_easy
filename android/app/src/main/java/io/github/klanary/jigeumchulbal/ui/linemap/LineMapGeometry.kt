package io.github.klanary.jigeumchulbal.ui.linemap

import kotlin.math.hypot
import kotlin.math.min

// 노선도 화면의 좌표 계산(재설계 스펙 4.1, 8). 안드로이드 API 없이 테스트한다.
// 화면 좌표 = 지도 좌표 × scale + offset. 지도 좌표는 노선도 SVG의 좌표다.

data class MapTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toMap(screenX: Float, screenY: Float): Pair<Float, Float> =
        (screenX - offsetX) / scale to (screenY - offsetY) / scale

    fun toScreen(mapX: Float, mapY: Float): Pair<Float, Float> = mapX * scale + offsetX to mapY * scale + offsetY

    /** 화면의 한 점(손가락 사이 등)을 그대로 둔 채 [factor]배 확대·축소한다. 배율은 [min]~[max]로 제한한다. */
    fun zoomAround(focusX: Float, focusY: Float, factor: Float, min: Float = 0.5f, max: Float = 4f): MapTransform {
        val next = (scale * factor).coerceIn(min, max)
        val (mapX, mapY) = toMap(focusX, focusY)
        return MapTransform(next, focusX - mapX * next, focusY - mapY * next)
    }

    fun pan(dx: Float, dy: Float): MapTransform = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** 지도의 (x, y)를 화면 가운데에 둔다. */
    fun centerOn(x: Float, y: Float, viewW: Float, viewH: Float): MapTransform =
        copy(offsetX = viewW / 2 - x * scale, offsetY = viewH / 2 - y * scale)
}

/** 노선도 전체가 화면에 들어가는 배율. 축소의 하한이다. */
fun fitScale(mapW: Float, mapH: Float, viewW: Float, viewH: Float): Float = min(viewW / mapW, viewH / mapH)

/** 누른 점(지도 좌표)에서 [radiusMap] 안의 가장 가까운 역. 없으면 null. */
fun hitStation(stations: List<MapStation>, mapX: Float, mapY: Float, radiusMap: Float): MapStation? =
    stations
        .map { it to hypot(it.x - mapX, it.y - mapY) }
        .filter { it.second <= radiusMap }
        .minByOrNull { it.second }
        ?.first
