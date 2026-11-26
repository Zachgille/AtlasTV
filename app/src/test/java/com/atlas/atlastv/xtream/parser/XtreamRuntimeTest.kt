package com.atlas.atlastv.xtream.parser

import org.junit.Assert.*
import org.junit.Test

class XtreamRuntimeTest {
    @Test fun validExplicitSecondsWinAndInvalidSecondsFallBackToClock() {
        assertEquals(2_520_000L, XtreamRuntime.millis("2520", "01:00:00"))
        assertEquals(2_520_000L, XtreamRuntime.millis(" 2520.000 ", null))
        assertEquals(2_520_500L, XtreamRuntime.millis("2520.5", null))
        for (bad in listOf(null, "0", "-1", "4294974496", "99999999", "NaN", "true", "9e300"))
            assertEquals(bad, 5_400_000L, XtreamRuntime.millis(bad, "01:30:00"))
    }
    @Test fun threePartClocksSupportSecondsAndMillisWithoutGuessingUnits() {
        assertEquals(7_200_000L, XtreamRuntime.millis(null, "02:00:00"))
        assertEquals(3_661_125L, XtreamRuntime.millis(null, "01:01:01.125"))
        assertEquals(86_400_000L, XtreamRuntime.millis(null, "24:00:00"))
        for (bad in listOf(null, "90", "01:30", "00:60:00", "00:00:60", "-1:00:00", "24:00:01", "999999999999999:00:00", "00:00:00"))
            assertNull(bad, XtreamRuntime.millis(null, bad))
    }
    @Test fun oversizedOrMalformedOptionalRuntimeCannotProduceAWrappedValue() {
        for (bad in listOf("4294974496", "9223372036854775807", "86400.001", "1".repeat(10000), "2520garbage", "+2520", "1.0001"))
            assertNull(bad.take(30), XtreamRuntime.millis(bad, null))
    }
}
