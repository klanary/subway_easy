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
    /** 열차 번호. 예전 서버에는 없다. */
    val trainNo: String? = null,
)

@Serializable
data class ArrivalGroupDto(
    val line: String,
    /** 상행·하행·내선·외선. 비서울 노선([source] "none")은 null이다. */
    val direction: String? = null,
    val towards: String? = null,
    val trains: List<TrainDto>,
    /** 열차 정보 출처(재설계 스펙 5.1): "realtime", "timetable"(실시간이 없는 방향), "none"(비서울 노선). */
    val source: String = "realtime",
)

@Serializable
data class ArrivalsDto(
    val stationId: String,
    val updatedAt: String,
    val stale: Boolean = false,
    val groups: List<ArrivalGroupDto>,
)

@Serializable
data class NearestStationDto(val id: String, val name: String, val distanceM: Int)

/** 반경 2km 안에 역이 없으면 [station]이 null이다. */
@Serializable
data class NearestDto(val station: NearestStationDto? = null)

/** 시간표 열차(재설계 스펙 6). [departsAt]은 ISO 시각. */
@Serializable
data class TimetableTrainDto(
    val departsAt: String,
    val destination: String? = null,
    val isExpress: Boolean = false,
    val trainNo: String? = null,
)

@Serializable
data class TimetableDto(
    val stationId: String,
    val line: String,
    val direction: String,
    val trains: List<TimetableTrainDto>,
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
    /** 환승해서 타는 지하철 단계만: 이 역에 걸어 도착하는 예상 시각(ISO)과 그 뒤 열차 2대. */
    val boardAfter: String? = null,
    val transferTrains: List<TransferTrainDto>? = null,
)

/** 환승역에서 타고 온 열차가 도착한 뒤 출발하는 열차. 첫째와 그다음 열차. */
@Serializable
data class TransferTrainDto(
    val departsAt: String,
    val destination: String? = null,
    val source: String = "timetable",
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
    /** 출발 시각을 지정했을 때만: 첫 열차 출발 시각과 도착 예상(ISO). */
    val departAt: String? = null,
    val arriveAt: String? = null,
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
