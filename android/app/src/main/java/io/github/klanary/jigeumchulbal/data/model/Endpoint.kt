package io.github.klanary.jigeumchulbal.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class EndpointKind { CURRENT_LOCATION, STATION, PLACE, PIN, MY_PLACE }

/** 경로의 출발·경유·도착 지점. */
@Serializable
data class Endpoint(
    val label: String,
    val point: GeoPoint,
    val kind: EndpointKind,
    val stationId: String? = null,
)
