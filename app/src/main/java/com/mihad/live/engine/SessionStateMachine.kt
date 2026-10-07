package com.mihad.live.engine

/** Small, explicit state machine. Low-level encoder/socket lifecycle is never controlled by UI. */
class SessionStateMachine(initial: StreamState = StreamState.IDLE) {

    @Volatile
    var current: StreamState = initial
        private set

    @Synchronized
    fun moveTo(next: StreamState): Boolean {
        if (next == current) return true
        if (next !in allowed[current].orEmpty()) return false
        current = next
        return true
    }

    companion object {
        private val allowed = mapOf(
            StreamState.IDLE to setOf(StreamState.PREPARING, StreamState.STOPPED, StreamState.ERROR),
            StreamState.PREPARING to setOf(StreamState.ENCODER_READY, StreamState.ERROR, StreamState.STOPPING),
            StreamState.ENCODER_READY to setOf(StreamState.PREPARING, StreamState.RTMP_CONNECTING, StreamState.STOPPING, StreamState.ERROR, StreamState.STOPPED),
            StreamState.RTMP_CONNECTING to setOf(StreamState.RTMP_CONNECTED, StreamState.RTMP_PUBLISHING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.RTMP_CONNECTED to setOf(StreamState.RTMP_PUBLISHING, StreamState.INGEST_VERIFYING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.RTMP_PUBLISHING to setOf(StreamState.INGEST_VERIFYING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.INGEST_VERIFYING to setOf(StreamState.RTMP_PUBLISHING, StreamState.LIVE_VERIFIED, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.LIVE_VERIFIED to setOf(StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.RECONNECTING to setOf(StreamState.RTMP_CONNECTING, StreamState.RTMP_CONNECTED, StreamState.RTMP_PUBLISHING, StreamState.INGEST_VERIFYING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.STOPPING to setOf(StreamState.STOPPED, StreamState.ERROR),
            StreamState.STOPPED to setOf(StreamState.IDLE, StreamState.PREPARING),
            StreamState.ERROR to setOf(StreamState.PREPARING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.STOPPED)
        )
    }
}
