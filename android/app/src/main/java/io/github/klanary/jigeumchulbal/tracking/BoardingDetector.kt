package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.TrainNearDto
import io.github.klanary.jigeumchulbal.data.remote.TrainState
import io.github.klanary.jigeumchulbal.location.LocationFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// 위치로 탑승을 알아보는 규칙(⑭ 자동 감지, 위치 결합 스펙 7.1). 안드로이드에 의존하지 않아 좌표와 시각으로 테스트한다.

/** 감지할 곳: 이 호선의 탈 역과 그 다음 정거장, 두 역의 좌표. */
data class DetectionTarget(
    val line: String,
    val boardStationId: String,
    val nextStop: String,
    val board: GeoPoint,
    val next: GeoPoint,
)

private const val EARTH_RADIUS_M = 6_371_000.0

/** 정확도가 이보다 나쁜(반경이 큰) 위치는 버린다. */
private const val MAX_ACCURACY_M = 1_000f

/** 탈 역에 있었다고 보는 거리. */
private const val NEAR_M = 300.0

/** 탈 역을 떠났다고 보는 거리. */
private const val AWAY_M = 500.0

/** 걷기로는 넘지 못하는 속도(시속 15km). */
private const val MIN_SPEED_MPS = 15_000.0 / 3_600.0

/** 두 좌표 사이 거리(m). */
fun haversineM(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

/**
 * 탈 역 300m 안의 위치가 있었고, 그 뒤 탈 역에서 500m 넘게 떨어지면서 탈 역보다 다음 역에 더 가까운 위치가 나오고,
 * 마지막으로 탈 역 가까이 있던 위치에서 그 위치까지 시속 15km 이상으로 움직였으면 "탑승"이다.
 * 승강장에서 오래 기다려도 속도는 마지막 가까운 위치부터 재므로 기다린 시간이 속도를 낮추지 않는다.
 */
class BoardingDetector(private val target: DetectionTarget) {
    private var lastNear: LocationFix? = null

    /** 위치를 하나 넣는다. 탑승으로 보이면 true. */
    fun onFix(fix: LocationFix): Boolean {
        if (fix.accuracyM > MAX_ACCURACY_M) return false
        val fromBoard = haversineM(fix.point, target.board)
        if (fromBoard <= NEAR_M) {
            lastNear = fix
            return false
        }
        val near = lastNear ?: return false
        if (fromBoard <= AWAY_M || haversineM(fix.point, target.next) >= fromBoard) return false
        val seconds = (fix.at - near.at) / 1_000.0
        if (seconds <= 0) return false
        return haversineM(near.point, fix.point) / seconds >= MIN_SPEED_MPS
    }
}

/** 한 역 안에서 열차가 나아가는 순서: 전역출발(그 역으로 오는 중) → 진입 → 도착 → 출발. */
private fun TrainState.progress(): Int = when (this) {
    TrainState.LEFT_PREVIOUS -> 0
    TrainState.APPROACHING -> 1
    TrainState.ARRIVED -> 2
    TrainState.DEPARTED -> 3
}

/**
 * 근처 열차 가운데 탈 역을 막 지난 열차(탈 역에서 출발했거나, 탈 역 다음 2정거장 안에 있고 탈 역까지 남은 시간이 없는 열차)
 * 중 탈 역에 가장 가까운 열차. 없으면 null(직접 고르기로 넘긴다).
 */
fun pickBoardedTrain(trains: List<TrainNearDto>): TrainNearDto? =
    trains.filter { train ->
        (train.stopsFromStation == 0 && train.state == TrainState.DEPARTED) ||
            (train.stopsFromStation in 1..2 && train.etaSec == null)
    }.minWithOrNull(compareBy<TrainNearDto>({ it.stopsFromStation }, { it.state.progress() }))
