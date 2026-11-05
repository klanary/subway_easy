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

/** 하차·환승 지점: [alightAt]에 내리고, [alertAt]에 미리 알린다. */
@Serializable
data class AlightPoint(
    val name: String,
    val isBus: Boolean,
    val kind: PointKind,
    val alightAt: Long,
    val alertAt: Long,
    val stopsToGo: Int,
    val nextLine: String? = null,
)

/** 하차 지점 계산을 어느 단계부터 할지: 대기에서 넘어왔으면 첫 지하철, [출발(하차 알림)]이면 첫 대중교통. */
@Serializable
enum class StartFrom { FIRST_SUBWAY, FIRST_TRANSIT }
