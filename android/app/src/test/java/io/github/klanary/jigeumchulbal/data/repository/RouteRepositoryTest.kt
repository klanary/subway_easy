package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.model.StationRef
import io.github.klanary.jigeumchulbal.data.remote.ApiCaller
import io.github.klanary.jigeumchulbal.data.remote.ApiService
import io.github.klanary.jigeumchulbal.data.remote.ArrivalsDto
import io.github.klanary.jigeumchulbal.data.remote.NearestDto
import io.github.klanary.jigeumchulbal.data.remote.NetworkMonitor
import io.github.klanary.jigeumchulbal.data.remote.RoutesDto
import io.github.klanary.jigeumchulbal.data.remote.StationSearchDto
import io.github.klanary.jigeumchulbal.data.remote.TimetableDto
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

data class RoutesArgs(val from: String, val to: String, val via: String?, val departAt: String?)

data class TimetableArgs(val id: String, val line: String, val direction: String, val from: String?, val count: Int)

/** 마지막 호출의 인자를 기록하는 테스트용 ApiService. 다른 호출은 쓰지 않는다. */
class RecordingApiService : ApiService {
    var lastRoutes: RoutesArgs? = null
    var lastTimetable: TimetableArgs? = null
    var lastNearest: Pair<Double, Double>? = null

    override suspend fun routes(from: String, to: String, via: String?, departAt: String?): RoutesDto {
        lastRoutes = RoutesArgs(from, to, via, departAt)
        return RoutesDto(updatedAt = "2026-11-03T08:00:00+09:00", routes = emptyList())
    }

    override suspend fun timetable(id: String, line: String, direction: String, from: String?, count: Int): TimetableDto {
        lastTimetable = TimetableArgs(id, line, direction, from, count)
        return TimetableDto(id, line, direction, emptyList())
    }

    override suspend fun nearestStation(lat: Double, lng: Double): NearestDto {
        lastNearest = lat to lng
        return NearestDto(null)
    }

    override suspend fun health(): Map<String, String> = error("not used")
    override suspend fun searchStations(q: String, limit: Int): StationSearchDto = error("not used")
    override suspend fun arrivals(id: String, line: String?, towards: String?): ArrivalsDto = error("not used")
}

class RouteRepositoryTest {
    private val api = RecordingApiService()
    private val caller = ApiCaller(Json, NetworkMonitor { true })
    private val mapo = StationRef("마포", "마포")
    private val gongdeok = StationRef("공덕", "공덕")
    private val hongdae = StationRef("홍대입구", "홍대입구")

    @Test fun sendsStationIds() = runTest {
        DefaultRouteRepository(api, caller).routes(mapo, hongdae, via = gongdeok)
        assertEquals(RoutesArgs("마포", "홍대입구", "공덕", null), api.lastRoutes)
    }

    @Test fun departAtIsKstIso() = runTest {
        DefaultRouteRepository(api, caller).routes(mapo, gongdeok, departAt = Instant.parse("2026-11-12T23:00:00.123Z"))
        assertEquals("2026-11-13T08:00:00+09:00", api.lastRoutes!!.departAt)
    }

    @Test fun timetableSendsLineDirectionAndTime() = runTest {
        DefaultStationRepository(api, caller).timetable("영등포구청", "5호선", "상행", Instant.parse("2026-11-12T23:00:00Z"), 4)
        assertEquals(TimetableArgs("영등포구청", "5호선", "상행", "2026-11-13T08:00:00+09:00", 4), api.lastTimetable)
    }

    @Test fun nearestSendsLatLng() = runTest {
        DefaultStationRepository(api, caller).nearest(37.5665, 126.978)
        assertEquals(37.5665 to 126.978, api.lastNearest)
    }
}
