package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.NetworkMonitor
import io.github.klanary.jigeumchulbal.data.remote.PlaceSearchDto
import io.github.klanary.jigeumchulbal.data.remote.ReverseDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StationSearchDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

data class RoutesArgs(
    val from: String,
    val to: String,
    val via: String?,
    val fromName: String?,
    val toName: String?,
    val viaName: String?,
)

/** 마지막 routes 호출의 인자를 기록하는 테스트용 ApiService. 다른 호출은 쓰지 않는다. */
class RecordingApiService : ApiService {
    var lastRoutes: RoutesArgs? = null

    override suspend fun routes(
        from: String, to: String, via: String?, fromName: String?, toName: String?, viaName: String?,
    ): RoutesDto {
        lastRoutes = RoutesArgs(from, to, via, fromName, toName, viaName)
        return RoutesDto(updatedAt = "2026-11-03T08:00:00+09:00", routes = emptyList())
    }

    override suspend fun health(): Map<String, String> = error("not used")
    override suspend fun searchStations(q: String, limit: Int): StationSearchDto = error("not used")
    override suspend fun arrivals(id: String, line: String?, towards: String?): ArrivalsDto = error("not used")
    override suspend fun searchPlaces(q: String, lat: Double?, lng: Double?): PlaceSearchDto = error("not used")
    override suspend fun reverse(lat: Double, lng: Double): ReverseDto = error("not used")
}

class RouteRepositoryTest {
    @Test fun currentLocationSendsNoNameButOthersDo() = runTest {
        val api = RecordingApiService()
        DefaultRouteRepository(api, ApiCaller(Json, NetworkMonitor { true })).routes(
            Endpoint("현재 위치", GeoPoint(37.5571, 126.9245), EndpointKind.CURRENT_LOCATION),
            Endpoint("강남", GeoPoint(37.4979, 127.0276), EndpointKind.STATION, "강남"),
        )
        assertEquals(RoutesArgs("37.5571,126.9245", "37.4979,127.0276", null, null, "강남", null), api.lastRoutes)
    }
}
