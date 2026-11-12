package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import java.time.Instant

interface RouteRepository {
    /** 역에서 역까지 지하철 경로(재설계 스펙 5.3). [departAt]이 null이면 지금 출발. */
    suspend fun routes(
        from: StationRef,
        to: StationRef,
        via: StationRef? = null,
        departAt: Instant? = null,
    ): ApiResult<RoutesDto>
}

class DefaultRouteRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : RouteRepository {
    override suspend fun routes(
        from: StationRef,
        to: StationRef,
        via: StationRef?,
        departAt: Instant?,
    ): ApiResult<RoutesDto> = call { api.routes(from.id, to.id, via?.id, departAt?.let(::isoKst)) }
}
