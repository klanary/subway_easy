package io.github.klanary.jigeumchulbal.tracking

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.StationDto

/**
 * 탑승 감지 대상(위치 결합 스펙 7.1). 탈 역과 다음 정거장의 좌표를 역 검색으로 얻는다. 역 이름으로만 찾고 폰 위치는
 * 서버로 보내지 않는다(3.2). 탈 역은 우리 역 id가 같은 결과, 다음 정거장은 같은 호선이 지나는 첫 결과를 쓴다.
 * 못 찾거나 검색이 실패하면 null이고, 이때는 자동 감지 없이 계획한 열차와 직접 고르기만 쓴다.
 */
suspend fun detectionTarget(
    line: String,
    boardStationId: String,
    nextStop: String,
    search: suspend (String) -> ApiResult<List<StationDto>>,
): DetectionTarget? {
    val board = (search(boardStationId) as? ApiResult.Ok)?.value?.firstOrNull { it.id == boardStationId } ?: return null
    val next = (search(nextStop) as? ApiResult.Ok)?.value?.firstOrNull { line in it.lines } ?: return null
    return DetectionTarget(line, boardStationId, nextStop, GeoPoint(board.lat, board.lng), GeoPoint(next.lat, next.lng))
}
