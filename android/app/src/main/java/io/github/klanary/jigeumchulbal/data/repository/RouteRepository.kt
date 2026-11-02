package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto

interface RouteRepository {
    suspend fun routes(origin: Endpoint, dest: Endpoint, via: Endpoint? = null): ApiResult<RoutesDto>
}

class DefaultRouteRepository(
    private val api: ApiService,
    private val call: ApiCaller,
) : RouteRepository {
    override suspend fun routes(origin: Endpoint, dest: Endpoint, via: Endpoint?): ApiResult<RoutesDto> =
        call {
            api.routes(
                from = origin.point.toQuery(),
                to = dest.point.toQuery(),
                via = via?.point?.toQuery(),
                fromName = origin.nameParam(),
                toName = dest.nameParam(),
                viaName = via?.nameParam(),
            )
        }

    /** "현재 위치"는 장소 이름이 아니므로 서버(카카오 경로의 이름 파라미터)에 보내지 않는다. */
    private fun Endpoint.nameParam(): String? = if (kind == EndpointKind.CURRENT_LOCATION) null else label
}
