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
    /** 열차 번호. 위치정보와 같은 열차를 잇는 열쇠다(위치 결합 스펙 6.3). 예전 서버에는 없다. */
    val trainNo: String? = null,
    /** 남은 시간을 무엇으로 계산했는지: "position"(열차 위치) 또는 "arrival"(도착 정보). */
    val basis: String? = null,
)

/** 열차 위치 상태(위치 결합 스펙 6.3): 진입, 도착, 출발, 전역출발. */
@Serializable
enum class TrainState { APPROACHING, ARRIVED, DEPARTED, LEFT_PREVIOUS }

/** 역 근처 열차(직접 고르기 목록과 자동 감지 후보). [stopsFromStation]: 오는 중이면 음수, 그 역이면 0, 지나갔으면 양수. */
@Serializable
data class TrainNearDto(
    val trainNo: String,
    val stationId: String,
    val stationName: String,
    val state: TrainState,
    val direction: String? = null,
    val destination: String? = null,
    val isExpress: Boolean = false,
    val isLast: Boolean = false,
    /** 그 역까지 남은 초. 이미 지났으면 null. */
    val etaSec: Int? = null,
    val stopsFromStation: Int,
)

@Serializable
data class LineTrainsDto(
    val line: String,
    val updatedAt: String,
    val stale: Boolean = false,
    val trains: List<TrainNearDto>,
)

/** 탄 열차가 내릴 역까지 남은 시간. 지금 위치 목록에 그 열차가 없으면 [found]가 false다(오류 아님). */
@Serializable
data class TrainEtaDto(
    val trainNo: String,
    val line: String,
    val found: Boolean,
    val stationId: String? = null,
    val state: TrainState? = null,
    val etaSec: Int? = null,
    val updatedAt: String,
    val stale: Boolean = false,
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
