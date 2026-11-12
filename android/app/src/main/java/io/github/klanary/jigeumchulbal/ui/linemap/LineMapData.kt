package io.github.klanary.jigeumchulbal.ui.linemap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 노선도 위 역의 위치(노선도 SVG 좌표). [id]는 서버의 역 id다. */
@Serializable
data class MapStation(val id: String, val x: Float, val y: Float)

/** assets/linemap/stations.json: server/scripts/build_linemap.py가 만든다(재설계 스펙 8). */
@Serializable
data class LineMapStations(val width: Float, val height: Float, val stations: List<MapStation>) {
    fun find(id: String): MapStation? = stations.firstOrNull { it.id == id }
}

private val json = Json { ignoreUnknownKeys = true }

fun parseLineMapStations(text: String): LineMapStations = json.decodeFromString(text)

const val LINEMAP_SVG_ASSET = "linemap/linemap.svg"
const val LINEMAP_STATIONS_ASSET = "linemap/stations.json"
