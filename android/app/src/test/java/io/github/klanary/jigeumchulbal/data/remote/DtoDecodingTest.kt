package io.github.klanary.jigeumchulbal.data.remote

import io.github.klanary.jigeumchulbal.fixtures.SPEC_ARRIVALS_JSON
import io.github.klanary.jigeumchulbal.fixtures.SPEC_ROUTES_JSON
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun routesExampleFromSpecDecodes() {
        val route = json.decodeFromString<RoutesDto>(SPEC_ROUTES_JSON).routes.single()
        assertEquals(1980, route.totalSec)
        assertEquals(StepMode.SUBWAY, route.legs[0].steps[1].mode)
        assertEquals(listOf(37.5550, 126.9200), route.legs[0].steps[0].path[0])
        assertEquals(180, route.firstBoarding!!.nextTrains!![0].remainingSec)
    }

    @Test fun arrivalsExampleFromSpecDecodes() {
        val group = json.decodeFromString<ArrivalsDto>(SPEC_ARRIVALS_JSON).groups.single()
        assertEquals("내선", group.direction)
        assertEquals(2, group.trains.size)
    }

    @Test fun nullsAndUnknownFieldsDecode() {
        val dto = json.decodeFromString<NearestDto>("""{"station":null,"extra":1}""")
        assertNull(dto.station)
    }

    // 아래는 2026-11-12 실제 서버 응답(src/test/resources/server/)이다(재설계 스펙 6).

    @Test fun timetableFallbackArrivalsDecode() {
        val group = json.decodeFromString<ArrivalsDto>(server("arrivals_jinjeop.json")).groups.single()
        assertEquals("timetable", group.source)
        assertEquals("하행", group.direction)
        assertTrue(group.trains.isNotEmpty())
    }

    @Test fun nonSeoulLineGroupHasNoDirection() {
        val groups = json.decodeFromString<ArrivalsDto>(server("arrivals_gyeyang.json")).groups
        val none = groups.single { it.source == "none" }
        assertEquals("인천1호선", none.line)
        assertNull(none.direction)
        assertTrue(none.trains.isEmpty())
        assertTrue(groups.filter { it.source == "realtime" }.all { it.direction != null })
    }

    @Test fun timetableDecodes() {
        val dto = json.decodeFromString<TimetableDto>(server("timetable_yeongdeungpo.json"))
        assertEquals(4, dto.trains.size)
        assertTrue(dto.trains.first().departsAt.startsWith("2026-11-13T08:"))
        assertEquals("방화", dto.trains.first().destination)
    }

    @Test fun routesWithDepartAtDecode() {
        val first = json.decodeFromString<RoutesDto>(server("routes_depart_at.json")).routes.first()
        assertTrue(first.departAt!! < first.arriveAt!!)
        assertTrue(first.firstBoarding!!.nextTrains!!.isNotEmpty())
    }

    @Test fun viaRoutesHaveTwoLegs() {
        val routes = json.decodeFromString<RoutesDto>(server("routes_via.json")).routes
        assertTrue(routes.all { it.legs.size == 2 && it.departAt == null })
    }

    @Test fun nearestDecodes() {
        assertEquals("시청", json.decodeFromString<NearestDto>(server("nearest_cityhall.json")).station!!.id)
    }

    @Test fun sameStationErrorDecodes() {
        val body = json.decodeFromString<ErrorBodyDto>(server("error_same_station.json"))
        assertEquals("SAME_STATION", body.error.reason)
    }

    private fun server(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("server/$name")) { name }.readText()
}
