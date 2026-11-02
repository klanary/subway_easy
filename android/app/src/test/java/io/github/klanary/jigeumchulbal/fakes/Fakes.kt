package io.github.klanary.jigeumchulbal.fakes

import io.github.klanary.jigeumchulbal.data.local.FavoriteStationEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceType
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.PlaceDto
import io.github.klanary.jigeumchulbal.data.remote.ReverseDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.MyPlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.PlaceRepository
import io.github.klanary.jigeumchulbal.data.repository.RecentSearchRepository
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.data.repository.ServerRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.location.LocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// ViewModel 테스트용 가짜 저장소. 이후 작업이 필요한 가짜를 더한다.

/** 기본으로 검색어와 같은 이름의 역 하나를 돌려준다. [result]를 정하면 그것을 돌려준다. */
class FakeStationRepository : StationRepository {
    val searchCalls = mutableListOf<String>()
    var result: ApiResult<List<StationDto>>? = null
    val arrivalCalls = mutableListOf<Triple<String, String?, String?>>()

    /** 기본은 도착 정보가 없는 정상 응답. */
    var arrivalsResult: ApiResult<ArrivalsDto> =
        ApiResult.Ok(ArrivalsDto(stationId = "역", updatedAt = "2026-11-03T08:00:00+09:00", groups = emptyList()))

    override suspend fun search(q: String, limit: Int): ApiResult<List<StationDto>> {
        searchCalls += q
        return result ?: ApiResult.Ok(listOf(StationDto(q, q, listOf("2호선"), 37.5571, 126.9245)))
    }

    /** 역별로 다른 응답을 줄 때. 없는 역은 [arrivalsResult]. */
    var arrivalsByStation: Map<String, ApiResult<ArrivalsDto>> = emptyMap()

    override suspend fun arrivals(stationId: String, line: String?, towards: String?): ApiResult<ArrivalsDto> {
        arrivalCalls += Triple(stationId, line, towards)
        return arrivalsByStation[stationId] ?: arrivalsResult
    }
}

class FakeFavoriteRepository(stations: List<FavoriteStationEntity> = emptyList()) : FavoriteRepository {
    override val stations = MutableStateFlow(stations)

    override fun isFavorite(stationId: String): Flow<Boolean> = stations.map { list -> list.any { it.stationId == stationId } }

    override suspend fun toggleStation(stationId: String) {
        val list = stations.value
        stations.value = if (list.any { it.stationId == stationId }) {
            list.filterNot { it.stationId == stationId }
        } else {
            list + FavoriteStationEntity(stationId = stationId, createdAt = 0)
        }
    }
}

class FakeServerRepository : ServerRepository {
    var wakeCalls = 0

    override suspend fun wake(): Boolean {
        wakeCalls++
        return true
    }
}

/** 검색어 q에 대해 PlaceDto("$q 결과")를 돌려준다. [delays]로 검색어별 응답 지연을 줄 수 있다. */
class FakePlaceRepository : PlaceRepository {
    val searchCalls = mutableListOf<String>()
    var delays: Map<String, Long> = emptyMap()
    var lastNear: GeoPoint? = null
    var reverseResult: ApiResult<ReverseDto> = ApiResult.Ok(ReverseDto())
    val reverseCalls = mutableListOf<GeoPoint>()

    override suspend fun search(q: String, near: GeoPoint?): ApiResult<List<PlaceDto>> {
        searchCalls += q
        lastNear = near
        delays[q]?.let { delay(it) }
        return ApiResult.Ok(listOf(PlaceDto(name = "$q 결과", lat = 37.55, lng = 126.92)))
    }

    override suspend fun reverse(point: GeoPoint): ApiResult<ReverseDto> {
        reverseCalls += point
        return reverseResult
    }
}

class FakeMyPlaceRepository(initial: List<MyPlaceEntity> = emptyList()) : MyPlaceRepository {
    override val places = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override suspend fun save(type: MyPlaceType, label: String, endpoint: Endpoint, address: String?) {
        val kept = if (type == MyPlaceType.CUSTOM) places.value else places.value.filterNot { it.type == type }
        val entity = MyPlaceEntity(
            id = nextId++, type = type, label = label, lat = endpoint.point.lat, lng = endpoint.point.lng,
            address = address, stationId = endpoint.stationId,
        )
        places.value = kept + entity
    }

    override suspend fun delete(id: Long) {
        places.value = places.value.filterNot { it.id == id }
    }

    override suspend fun get(id: Long): MyPlaceEntity? = places.value.find { it.id == id }
}

class FakeRecentSearchRepository(initial: List<RecentSearchEntity> = emptyList()) : RecentSearchRepository {
    override val recent = MutableStateFlow(initial)
    val recorded = mutableListOf<Endpoint>()

    override suspend fun record(endpoint: Endpoint) {
        recorded += endpoint
    }
}

/** 기본으로 스펙 6.2 예시 경로 하나를 돌려준다. */
class FakeRouteRepository : RouteRepository {
    var result: ApiResult<RoutesDto> =
        ApiResult.Ok(RoutesDto(updatedAt = "2026-11-03T08:00:00+09:00", routes = listOf(specRoute())))
    val calls = mutableListOf<Triple<Endpoint, Endpoint, Endpoint?>>()

    override suspend fun routes(origin: Endpoint, dest: Endpoint, via: Endpoint?): ApiResult<RoutesDto> {
        calls += Triple(origin, dest, via)
        return result
    }
}

class FakeLocationProvider(var permission: Boolean, var point: GeoPoint?) : LocationProvider {
    override fun hasPermission(): Boolean = permission

    override suspend fun current(): GeoPoint? = if (permission) point else null
}
