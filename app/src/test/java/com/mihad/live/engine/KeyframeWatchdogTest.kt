package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyframeWatchdogTest {

    @Test
    fun `waits when no video timeline reference exists`() {
        assertEquals(
            KeyframeWatchdogAction.WAIT,
            KeyframeWatchdog.decide(nowMs = 60_000, referenceAtMs = 0, lastRequestAtMs = 0)
        )
    }

    @Test
    fun `waits four seconds before first keyframe request`() {
        assertEquals(
            KeyframeWatchdogAction.WAIT,
            KeyframeWatchdog.decide(nowMs = 4_999, referenceAtMs = 1_000, lastRequestAtMs = 0)
        )
        assertEquals(
            KeyframeWatchdogAction.REQUEST_KEYFRAME,
            KeyframeWatchdog.decide(nowMs = 5_000, referenceAtMs = 1_000, lastRequestAtMs = 0)
        )
    }

    @Test
    fun `rate limits repeated keyframe requests to two seconds`() {
        assertEquals(
            KeyframeWatchdogAction.WAIT,
            KeyframeWatchdog.decide(nowMs = 6_999, referenceAtMs = 1_000, lastRequestAtMs = 5_000)
        )
        assertEquals(
            KeyframeWatchdogAction.REQUEST_KEYFRAME,
            KeyframeWatchdog.decide(nowMs = 7_000, referenceAtMs = 1_000, lastRequestAtMs = 5_000)
        )
    }

    @Test
    fun `requests rather than failing at timeout if no keyframe request was issued`() {
        assertEquals(
            KeyframeWatchdogAction.REQUEST_KEYFRAME,
            KeyframeWatchdog.decide(nowMs = 21_000, referenceAtMs = 1_000, lastRequestAtMs = 0)
        )
    }

    @Test
    fun `fails after twenty seconds without a keyframe despite requests`() {
        assertEquals(
            KeyframeWatchdogAction.WAIT,
            KeyframeWatchdog.decide(nowMs = 20_999, referenceAtMs = 1_000, lastRequestAtMs = 19_000)
        )
        assertEquals(
            KeyframeWatchdogAction.FAIL,
            KeyframeWatchdog.decide(nowMs = 21_000, referenceAtMs = 1_000, lastRequestAtMs = 19_000)
        )
    }

    @Test
    fun `new keyframe resets the age reference`() {
        assertEquals(
            KeyframeWatchdogAction.WAIT,
            KeyframeWatchdog.decide(nowMs = 8_000, referenceAtMs = 7_000, lastRequestAtMs = 5_000)
        )
        assertEquals(
            KeyframeWatchdogAction.REQUEST_KEYFRAME,
            KeyframeWatchdog.decide(nowMs = 11_000, referenceAtMs = 7_000, lastRequestAtMs = 5_000)
        )
    }
}
