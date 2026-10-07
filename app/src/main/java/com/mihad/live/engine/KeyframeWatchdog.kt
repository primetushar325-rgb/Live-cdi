package com.mihad.live.engine

/** Pure decision used by the RTMP media monitor to recover before failing a keyframe stall. */
enum class KeyframeWatchdogAction {
    WAIT,
    REQUEST_KEYFRAME,
    FAIL
}

object KeyframeWatchdog {
    fun decide(
        nowMs: Long,
        referenceAtMs: Long,
        lastRequestAtMs: Long,
        requestAfterMs: Long = 4_000L,
        requestIntervalMs: Long = 2_000L,
        failAfterMs: Long = 20_000L
    ): KeyframeWatchdogAction {
        if (referenceAtMs <= 0L) return KeyframeWatchdogAction.WAIT
        val ageMs = (nowMs - referenceAtMs).coerceAtLeast(0L)
        val hasRequestedSinceReference = lastRequestAtMs >= referenceAtMs && lastRequestAtMs > 0L
        val requestAgeMs = if (hasRequestedSinceReference) nowMs - lastRequestAtMs else Long.MAX_VALUE
        if (ageMs >= failAfterMs && hasRequestedSinceReference && requestAgeMs >= requestIntervalMs) {
            return KeyframeWatchdogAction.FAIL
        }
        if (ageMs >= requestAfterMs &&
            (!hasRequestedSinceReference || requestAgeMs >= requestIntervalMs)) {
            return KeyframeWatchdogAction.REQUEST_KEYFRAME
        }
        return KeyframeWatchdogAction.WAIT
    }
}
