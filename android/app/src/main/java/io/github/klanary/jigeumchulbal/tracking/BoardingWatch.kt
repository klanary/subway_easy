package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.LineTrainsDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.location.LocationFix
import io.github.klanary.jigeumchulbal.session.DetectedTrain
import io.github.klanary.jigeumchulbal.session.DetectionSpot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/** 위치를 받으며 탑승을 기다린다. 탑승으로 보이면 true, 위치 흐름이 먼저 끝나면(권한을 거둠 등) false. */
suspend fun awaitBoarding(target: DetectionTarget, fixes: Flow<LocationFix>): Boolean {
    val detector = BoardingDetector(target)
    return fixes.firstOrNull { detector.onFix(it) } != null
}

/** 탑승을 알아낸 뒤 근처 열차에서 방금 탄 열차를 고른다. 고를 열차가 없거나 조회가 실패하면 null(직접 고르기로 넘긴다). */
suspend fun boardedTrain(near: suspend () -> ApiResult<LineTrainsDto>): DetectedTrain? =
    when (val result = near()) {
        is ApiResult.Ok -> pickBoardedTrain(result.value.trains)?.let { DetectedTrain(it.trainNo, it.destination) }
        is ApiResult.Err -> null
    }

/**
 * 세션 서비스의 탑승 감지(⑭ 자동 감지, 위치 결합 스펙 7.1): [spot]의 좌표를 역 검색으로 찾고, 위치를 지켜보다가 탑승을
 * 알아내면 근처 열차에서 탄 열차를 골라 [onBoarded]로 한 번 알린다(못 고르면 null).
 * 좌표를 못 찾았거나 위치 흐름이 끝나 감지할 수 없으면 취소될 때까지 기다린다. 곧바로 끝나면 서비스가 같은 일을
 * 되풀이해 검색을 계속 부르게 되기 때문이다.
 */
suspend fun detectBoarding(
    spot: DetectionSpot,
    search: suspend (String) -> ApiResult<List<StationDto>>,
    fixes: () -> Flow<LocationFix>,
    near: suspend (DetectionSpot) -> ApiResult<LineTrainsDto>,
    onBoarded: suspend (DetectedTrain?) -> Unit,
) {
    val target = detectionTarget(spot.line, spot.boardStationId, spot.nextStop, search) ?: awaitCancellation()
    if (!awaitBoarding(target, fixes())) awaitCancellation()
    onBoarded(boardedTrain { near(spot) })
}
