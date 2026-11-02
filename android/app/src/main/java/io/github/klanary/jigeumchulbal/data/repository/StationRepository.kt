package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto

interface StationRepository {
    suspend fun search(q: String, limit: Int = 20): ApiResult<List<StationDto>>

    suspend fun arrivals(stationId: String, line: String? = null, towards: String? = null): ApiResult<ArrivalsDto>
}

class DefaultStationRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : StationRepository {
    override suspend fun search(q: String, limit: Int): ApiResult<List<StationDto>> =
        call { api.searchStations(q, limit).items }

    override suspend fun arrivals(stationId: String, line: String?, towards: String?): ApiResult<ArrivalsDto> =
        call { api.arrivals(stationId, line, towards) }
}
