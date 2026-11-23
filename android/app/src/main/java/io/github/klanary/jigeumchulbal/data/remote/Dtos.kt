package io.github.klanary.jigeumchulbal.data.remote

import kotlinx.serialization.Serializable

// 서버 응답 형식(스펙 6.2). 필드 이름은 JSON 키와 같다. 앱은 서울시·카카오 형식을 모른다.

@Serializable
data class StationDto(
    val id: String,
    val name: String,
    val lines: List<String>,
    val lat: Double,
    val lng: Double,
)

@Serializable
data class StationSearchDto(val items: List<StationDto>)

@Serializable
data class TrainDto(
    val destination: String? = null,
    val remainingSec: Int? = null,
    val message: String = "",
    val isLast: Boolean = false,
    val isExpress: Boolean = false,
)

@Serializable
data class ArrivalGroupDto(
    val line: String,
    val direction: String,
    val towards: String? = null,
    val trains: List<TrainDto>,
)

@Serializable
data class ArrivalsDto(
    val stationId: String,
    val updatedAt: String,
    val stale: Boolean = false,
    val groups: List<ArrivalGroupDto>,
)

@Serializable
data class PlaceDto(
    val name: String,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val category: String? = null,
)

@Serializable
data class PlaceSearchDto(val items: List<PlaceDto>)

@Serializable
data class NearestStationDto(val id: String, val name: String, val distanceM: Int)

@Serializable
data class ReverseDto(
    val address: String? = null,
    val nearestStation: NearestStationDto? = null,
)

@Serializable
enum class StepMode { WALK, SUBWAY, BUS }

@Serializable
data class StepDto(
    val mode: StepMode,
    val durationSec: Int,
    val distanceM: Int,
    val guidance: String = "",
    /** [위도, 경도] 목록 */
    val path: List<List<Double>> = emptyList(),
    val lineName: String? = null,
    val routeName: String? = null,
    val stops: List<String>? = null,
    val boardStationId: String? = null,
    val alightStationId: String? = null,
)

@Serializable
data class LegDto(val steps: List<StepDto>)

@Serializable
data class FirstBoardingDto(
    val stationId: String? = null,
    val lineName: String,
    val towards: String? = null,
    val secToBoarding: Int,
    val allWalkBeforeBoarding: Boolean,
    val nextTrains: List<TrainDto>? = null,
)

@Serializable
data class RouteDto(
    val id: String,
    /** SUBWAY, BUS, MIXED, WALK */
    val type: String,
    val totalSec: Int,
    val distanceM: Int,
    val transfers: Int,
    val fare: Int? = null,
    val legs: List<LegDto>,
    val firstBoarding: FirstBoardingDto? = null,
)

@Serializable
data class RoutesDto(
    val updatedAt: String,
    val stale: Boolean = false,
    val routes: List<RouteDto>,
)

@Serializable
data class ErrorBodyDto(val error: ErrorDto)

@Serializable
data class ErrorDto(val code: String, val reason: String? = null, val message: String)
