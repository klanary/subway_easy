package io.github.klanary.jigeumchulbal.session

import kotlinx.serialization.Serializable

// 통학 세션의 값들(스펙 8장). 세션 상태와 함께 DataStore에 JSON으로 저장하므로 모두 직렬화한다. 시각은 epoch ms.

/** 알릴 열차: [alertAt]에 알리고, 열차는 [trainArrivalAt]에 탑승역에 도착할 예정이다. [trainNo]는 그 열차 번호다. */
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
 * 지하철 단계의 지점은 호선과 탄 역·내릴 역 id, 탄 역 다음 정거장 이름을 함께 갖는다(화면 표시용). 예전 저장분에는 없어 기본값이 있다.
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

/** 하차 지점 계산을 어느 단계부터 할지: 대기에서 넘어왔으면 첫 지하철, [출발(하차 알림)]이면 첫 대중교통. */
@Serializable
enum class StartFrom { FIRST_SUBWAY, FIRST_TRANSIT }
