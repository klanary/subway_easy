package io.github.klanary.jigeumchulbal.fakes

import io.github.klanary.jigeumchulbal.data.local.FavoriteRouteEntity
import io.github.klanary.jigeumchulbal.data.local.RouteUsageEntity
import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.NearestStationDto
import io.github.klanary.jigeumchulbal.data.remote.TimetableDto
import io.github.klanary.jigeumchulbal.data.repository.sameStations
import java.time.Instant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.klanary.jigeumchulbal.data.local.SettingsStore
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.LineTrainsDto
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto
import io.github.klanary.jigeumchulbal.data.remote.TrainEtaDto
import io.github.klanary.jigeumchulbal.data.repository.FavoriteRepository
import io.github.klanary.jigeumchulbal.data.repository.RecentSearchRepository
import io.github.klanary.jigeumchulbal.data.repository.RouteRepository
import io.github.klanary.jigeumchulbal.data.repository.ServerRepository
import io.github.klanary.jigeumchulbal.data.repository.StationRepository
import io.github.klanary.jigeumchulbal.data.repository.TrainRepository
import io.github.klanary.jigeumchulbal.fixtures.specRoute
import io.github.klanary.jigeumchulbal.location.LocationFix
import io.github.klanary.jigeumchulbal.location.LocationProvider
import io.github.klanary.jigeumchulbal.session.AlarmId
import io.github.klanary.jigeumchulbal.session.AlarmScheduler
import io.github.klanary.jigeumchulbal.session.AlertContent
import io.github.klanary.jigeumchulbal.session.SessionNotifierPort
import io.github.klanary.jigeumchulbal.session.SessionState
import io.github.klanary.jigeumchulbal.session.SessionStore
import io.github.klanary.jigeumchulbal.session.SessionServiceLauncher
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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

    /** 검색어별 결과. 있으면 [result]보다 먼저 쓴다. */
    var searchByQuery: Map<String, List<StationDto>> = emptyMap()

    override suspend fun search(q: String, limit: Int): ApiResult<List<StationDto>> {
        searchCalls += q
        searchByQuery[q]?.let { return ApiResult.Ok(it) }
        return result ?: ApiResult.Ok(listOf(StationDto(q, q, listOf("2호선"), 37.5571, 126.9245)))
    }

    /** 역별로 다른 응답을 줄 때. 없는 역은 [arrivalsResult]. */
    var arrivalsByStation: Map<String, ApiResult<ArrivalsDto>> = emptyMap()

    override suspend fun arrivals(stationId: String, line: String?, towards: String?): ApiResult<ArrivalsDto> {
        arrivalCalls += Triple(stationId, line, towards)
        return arrivalsByStation[stationId] ?: arrivalsResult
    }

    /** 시간표 호출 기록: (역, 호선, 방향, 기준 시각, 대수). */
    data class TimetableCall(val stationId: String, val line: String, val direction: String, val from: Instant?, val count: Int)

    val timetableCalls = mutableListOf<TimetableCall>()
    var timetableResult: (TimetableCall) -> ApiResult<TimetableDto> =
        { ApiResult.Ok(TimetableDto(it.stationId, it.line, it.direction, emptyList())) }

    override suspend fun timetable(
        stationId: String,
        line: String,
        direction: String,
        from: Instant?,
        count: Int,
    ): ApiResult<TimetableDto> {
        val call = TimetableCall(stationId, line, direction, from, count)
        timetableCalls += call
        return timetableResult(call)
    }

    /** 기본은 가까운 역 없음. */
    var nearestResult: ApiResult<NearestStationDto?> = ApiResult.Ok(null)
    val nearestCalls = mutableListOf<Pair<Double, Double>>()

    override suspend fun nearest(lat: Double, lng: Double): ApiResult<NearestStationDto?> {
        nearestCalls += lat to lng
        return nearestResult
    }
}

class FakeFavoriteRepository : FavoriteRepository {
    override val routes = MutableStateFlow<List<FavoriteRouteEntity>>(emptyList())
    val usages = MutableStateFlow<List<RouteUsageEntity>>(emptyList())

    override fun usagesSince(sinceMillis: Long): Flow<List<RouteUsageEntity>> =
        usages.map { list -> list.filter { it.usedAt >= sinceMillis } }

    override suspend fun add(from: StationRef, via: StationRef?, to: StationRef) {
        if (find(from, via, to) != null) return
        val id = (routes.value.maxOfOrNull { it.id } ?: 0) + 1
        routes.value = routes.value + FavoriteRouteEntity(
            id = id, fromId = from.id, fromName = from.name, viaId = via?.id, viaName = via?.name,
            toId = to.id, toName = to.name, createdAt = 0,
        )
    }

    override suspend fun remove(id: Long) {
        routes.value = routes.value.filterNot { it.id == id }
        usages.value = usages.value.filterNot { it.routeId == id }
    }

    override suspend fun find(from: StationRef, via: StationRef?, to: StationRef): FavoriteRouteEntity? =
        routes.value.find { it.sameStations(from, via, to) }

    override suspend fun recordUsageIfFavorite(from: StationRef, via: StationRef?, to: StationRef, at: Long) {
        find(from, via, to)?.let { usages.value = usages.value + RouteUsageEntity(routeId = it.id, usedAt = at) }
    }
}

class FakeServerRepository : ServerRepository {
    var wakeCalls = 0

    override suspend fun wake(): Boolean {
        wakeCalls++
        return true
    }
}

class FakeRecentSearchRepository(initial: List<RecentSearchEntity> = emptyList()) : RecentSearchRepository {
    override val recent = MutableStateFlow(initial)
    val recorded = mutableListOf<StationRef>()

    override suspend fun record(station: StationRef) {
        recorded += station
    }
}

/** 기본으로 스펙 6.2 예시 경로 하나를 돌려준다. */
class FakeRouteRepository : RouteRepository {
    var result: ApiResult<RoutesDto> =
        ApiResult.Ok(RoutesDto(updatedAt = "2026-11-03T08:00:00+09:00", routes = listOf(specRoute())))
    data class Call(val from: StationRef, val to: StationRef, val via: StationRef?, val departAt: Instant?)

    val calls = mutableListOf<Call>()
    val lastVia: StationRef? get() = calls.lastOrNull()?.via

    override suspend fun routes(from: StationRef, to: StationRef, via: StationRef?, departAt: Instant?): ApiResult<RoutesDto> {
        calls += Call(from, to, via, departAt)
        return result
    }
}

/**
 * 메모리에만 저장하는 DataStore 위의 실제 SettingsStore.
 * 파일 DataStore는 IO 스레드에서 늦게 값을 내보내, 테스트가 끝나 Main 디스패처를 되돌린 뒤
 * ViewModel로 디스패치하다 다음 테스트를 실패시킨다. 파일 저장 자체는 SettingsStoreTest가 확인한다.
 */
fun fakeSettingsStore(): SettingsStore = SettingsStore(InMemoryPreferencesDataStore())

class InMemoryPreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value).toPreferences()
        state.value = next
        return next
    }
}

/** 열차 위치 API 가짜. 기본은 근처 열차가 없고 열차를 못 찾은 정상 응답이다. */
class FakeTrainRepository : TrainRepository {
    var nearResult: ApiResult<LineTrainsDto> =
        ApiResult.Ok(LineTrainsDto(line = "5호선", updatedAt = "2026-11-05T08:00:00+09:00", trains = emptyList()))
    var etaResult: ApiResult<TrainEtaDto> =
        ApiResult.Ok(TrainEtaDto(trainNo = "", line = "5호선", found = false, updatedAt = "2026-11-05T08:00:00+09:00"))
    val nearCalls = mutableListOf<Triple<String, String, String?>>()
    val etaCalls = mutableListOf<Triple<String, String, String>>()

    override suspend fun near(line: String, station: String, toward: String?): ApiResult<LineTrainsDto> {
        nearCalls += Triple(line, station, toward)
        return nearResult
    }

    override suspend fun eta(trainNo: String, line: String, to: String): ApiResult<TrainEtaDto> {
        etaCalls += Triple(trainNo, line, to)
        return etaResult
    }
}

class FakeLocationProvider(var permission: Boolean, var point: GeoPoint?) : LocationProvider {
    /** 위치 갱신으로 내보낼 위치들. 권한이 없으면 빈 흐름이다. */
    var fixes: Flow<LocationFix> = emptyFlow()

    override fun hasPermission(): Boolean = permission

    override fun updates(intervalMs: Long): Flow<LocationFix> = if (permission) fixes else emptyFlow()

    override suspend fun current(): GeoPoint? = if (permission) point else null
}

/** 메모리 DataStore 위의 실제 SessionStore. [rawJson]을 주면 그 문자열이 저장돼 있는 상태로 시작한다. */
fun testSessionStore(rawJson: String? = null): SessionStore {
    val initial = if (rawJson == null) emptyPreferences() else preferencesOf(stringPreferencesKey("session_json") to rawJson)
    return SessionStore(InMemoryPreferencesDataStore(initial), Json { ignoreUnknownKeys = true })
}

class FakeAlarmScheduler(var exact: Boolean = true) : AlarmScheduler {
    val scheduled = mutableMapOf<AlarmId, Long>()
    var cancelAllCalls = 0

    override fun schedule(id: AlarmId, atMillis: Long) {
        scheduled[id] = atMillis
    }

    override fun cancel(id: AlarmId) {
        scheduled.remove(id)
    }

    override fun cancelAll() {
        cancelAllCalls++
        scheduled.clear()
    }

    override fun canScheduleExact(): Boolean = exact
}

class FakeNotifier : SessionNotifierPort {
    val alerts = mutableListOf<AlertContent>()
    var ongoingShown = 0
    var ongoingCleared = 0

    override fun showOngoing(state: SessionState, now: Long) {
        ongoingShown++
    }

    override fun clearOngoing() {
        ongoingCleared++
    }

    override fun alert(content: AlertContent) {
        alerts += content
    }
}

class FakeLauncher : SessionServiceLauncher {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}

