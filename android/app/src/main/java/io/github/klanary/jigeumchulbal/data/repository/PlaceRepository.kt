package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.PlaceDto
import io.github.klanary.jigeumchulbal.data.remote.ReverseDto

interface PlaceRepository {
    /** [near]를 주면 서버가 가까운 순으로 정렬한다. */
    suspend fun search(q: String, near: GeoPoint?): ApiResult<List<PlaceDto>>

    suspend fun reverse(point: GeoPoint): ApiResult<ReverseDto>
}

class DefaultPlaceRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : PlaceRepository {
    override suspend fun search(q: String, near: GeoPoint?): ApiResult<List<PlaceDto>> =
        call { api.searchPlaces(q, near?.lat, near?.lng).items }

    override suspend fun reverse(point: GeoPoint): ApiResult<ReverseDto> =
        call { api.reverse(point.lat, point.lng) }
}
