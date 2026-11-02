package io.github.klanary.jigeumchulbal.data.remote

import io.github.klanary.jigeumchulbal.fixtures.SPEC_ARRIVALS_JSON
import io.github.klanary.jigeumchulbal.fixtures.SPEC_ROUTES_JSON
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val dto = json.decodeFromString<ReverseDto>("""{"address":null,"nearestStation":null,"extra":1}""")
        assertNull(dto.address)
        assertNull(dto.nearestStation)
    }
}
