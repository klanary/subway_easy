package io.github.klanary.jigeumchulbal.location

import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentOrLastTest {
    private val here = GeoPoint(37.5571, 126.9245)
    private val before = GeoPoint(37.0, 127.0)

    @Test fun currentWithinTimeoutWins() = runTest {
        assertEquals(here, currentOrLast({ delay(4_000); here }, { before }))
    }

    @Test fun slowCurrentFallsBackToLast() = runTest {
        assertEquals(before, currentOrLast({ delay(6_000); here }, { before }))
    }

    @Test fun nullCurrentFallsBackToLast() = runTest {
        assertEquals(before, currentOrLast({ null }, { before }))
    }

    @Test fun bothNullGivesNull() = runTest {
        assertNull(currentOrLast({ null }, { null }))
    }
}
