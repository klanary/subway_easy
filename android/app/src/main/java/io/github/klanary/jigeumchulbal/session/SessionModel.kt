package io.github.klanary.jigeumchulbal.session

import kotlinx.serialization.Serializable

// 통학 세션의 값들(스펙 8장). 세션 상태와 함께 DataStore에 JSON으로 저장하므로 모두 직렬화한다. 시각은 epoch ms.

/**
 * 알릴 열차: [alertAt]에 알리고, 열차는 [trainArrivalAt]에 탑승역에 도착할 예정이다.
 * [trainNo]는 그 열차 번호로, 열차 번호 없이 탑승으로 넘어가면 탄 열차로 쓴다(위치 결합 스펙 7.1 "계획한 열차").
 */
@Serializable
data class TrainPlan(val alertAt: Long, val trainArrivalAt: Long, val destination: String?, val trainNo: String? = null)

/** 승차 전 알림의 두 방식: 경로에서 시작하면 출발 타이밍, 역 상세(또는 첫 탑승 전 버스가 있는 경로)면 도착 N분 전. */
@Serializable
enum class WaitMode { DEPARTURE_TIMING, MINUTES_BEFORE }

/** 기다리는 열차: 이 역의 이 호선·방향(·방면). */
@Serializable
data class WaitTarget(
    val stationId: String,
    val stationName: String,
    val line: String,
    val towards: String?,
    val direction: String?,
)

@Serializable
enum class PointKind { TRANSFER, VIA, FINAL }

/**
 * 하차·환승 지점: [alightAt]에 내리고, [alertAt]에 미리 알린다.
 * 지하철 단계의 지점은 탄 열차를 따라가는 데 필요한 값([line], 탄 역·내릴 역 id, 탄 역 다음 정거장 이름)을 함께 갖는다
 * (위치 결합 스펙 7.2). 예전에 저장된 세션에는 없으므로 기본값이 있다.
 */
@Serializable
data class AlightPoint(
    val name: String,
    val isBus: Boolean,
    val kind: PointKind,
    val alightAt: Long,
    val alertAt: Long,
    val stopsToGo: Int,
    val nextLine: String? = null,
    val line: String? = null,
    val boardStationId: String? = null,
    val alightStationId: String? = null,
    val nextStop: String? = null,
)

/** 탄 열차를 어떻게 정했는지(위치 결합 스펙 7.1): 계획한 열차, 위치 자동 감지, 직접 고르기. */
@Serializable
enum class TrainSource { PLANNED, AUTO, MANUAL }

/**
 * 탑승 중 추적(위치 결합 스펙 7.2). [pointIndex]는 따라가는 지하철 단계의 하차 지점이 `Riding.points`에서 몇 번째인지다.
 * [trainNo]가 null이면 아직 탄 열차를 정하지 못했다. 3회 연속 열차를 못 찾으면 [lost]("실시간 추적 끊김")다.
 */
@Serializable
data class Tracking(
    val pointIndex: Int,
    val line: String,
    val alightStationId: String,
    val trainNo: String? = null,
    val destination: String? = null,
    val source: TrainSource? = null,
    val failures: Int = 0,
    val lost: Boolean = false,
)

/** 위치로 알아낸 탄 열차(사건에만 쓴다). */
data class DetectedTrain(val trainNo: String, val destination: String?)

/** 하차 지점 계산을 어느 단계부터 할지: 대기에서 넘어왔으면 첫 지하철, [출발(하차 알림)]이면 첫 대중교통. */
@Serializable
enum class StartFrom { FIRST_SUBWAY, FIRST_TRANSIT }
