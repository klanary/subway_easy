package io.github.klanary.jigeumchulbal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmCodesTest {
    private val all = listOf(AlarmId.PreBoarding, AlarmId.SessionEnd) + (0 until MAX_ALIGHT_POINTS).map { AlarmId.Alight(it) }

    @Test fun requestCodesAreUnique() = assertEquals(all.size, all.map { it.requestCode() }.toSet().size)

    @Test fun encodeDecodeRoundTrip() = all.forEach { assertEquals(it, decodeAlarmId(it.encode())) }

    @Test fun badValueDecodesToNull() {
        assertNull(decodeAlarmId("ALIGHT:x"))
        assertNull(decodeAlarmId(null))
        assertNull(decodeAlarmId("ALIGHT:$MAX_ALIGHT_POINTS"))
    }
}
