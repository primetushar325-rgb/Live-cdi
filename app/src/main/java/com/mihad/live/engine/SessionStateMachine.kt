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
            StreamState.ENCODER_READY to setOf(
                StreamState.PREPARING, StreamState.CONNECTING,
                StreamState.STOPPING, StreamState.ERROR, StreamState.STOPPED
            ),
            StreamState.CONNECTING to setOf(
                StreamState.RTMP_HANDSHAKE, StreamState.RTMP_CONNECTED, StreamState.RECONNECTING,
                StreamState.STOPPING, StreamState.ERROR
            ),
            StreamState.RTMP_HANDSHAKE to setOf(
                StreamState.CONNECTING, StreamState.RTMP_CONNECTED, StreamState.RECONNECTING,
                StreamState.STOPPING, StreamState.ERROR
            ),
            StreamState.RTMP_CONNECTED to setOf(
                StreamState.PUBLISHING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR
            ),
            StreamState.PUBLISHING to setOf(
                StreamState.MEDIA_FLOWING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR
            ),
            // Local packet writes cannot enter YOUTUBE_INGEST_DETECTED; no official YouTube
            // receipt/broadcast API is configured in this build.
            StreamState.MEDIA_FLOWING to setOf(
                StreamState.PUBLISHING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR
            ),
            StreamState.YOUTUBE_INGEST_DETECTED to setOf(
                StreamState.LIVE, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR
            ),
            StreamState.LIVE to setOf(StreamState.RECONNECTING, StreamState.STOPPING, StreamState.ERROR),
            StreamState.RECONNECTING to setOf(
                StreamState.CONNECTING, StreamState.RTMP_HANDSHAKE, StreamState.RTMP_CONNECTED,
                StreamState.PUBLISHING, StreamState.MEDIA_FLOWING,
                StreamState.STOPPING, StreamState.ERROR
            ),
            StreamState.STOPPING to setOf(StreamState.STOPPED, StreamState.ERROR),
            StreamState.STOPPED to setOf(StreamState.IDLE, StreamState.PREPARING),
            StreamState.ERROR to setOf(
                StreamState.PREPARING, StreamState.RECONNECTING, StreamState.STOPPING, StreamState.STOPPED
            )
        )
    }
}
