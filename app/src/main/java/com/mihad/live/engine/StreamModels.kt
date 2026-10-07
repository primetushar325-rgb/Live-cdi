package com.mihad.live.engine

import android.net.Uri

/** Canvas aspect is fixed before a source is played or an encoder is prepared. */
enum class LiveFormat(val label: String, val widthRatio: Int, val heightRatio: Int) {
    LANDSCAPE("16:9 LANDSCAPE", 16, 9),
    VERTICAL("9:16 VERTICAL", 9, 16);

    val isPortrait: Boolean get() = this == VERTICAL

    fun outputSize(quality: VideoQuality): CanvasSize = when (this) {
        LANDSCAPE -> when (quality) {
            VideoQuality.P720 -> CanvasSize(1280, 720)
            VideoQuality.P1080 -> CanvasSize(1920, 1080)
        }
        VERTICAL -> when (quality) {
            VideoQuality.P720 -> CanvasSize(720, 1280)
            VideoQuality.P1080 -> CanvasSize(1080, 1920)
        }
    }
}

enum class VideoQuality(val label: String) {
    P720("720p"),
    P1080("1080p")
}

enum class BitrateMode(val label: String) {
    AUTO("Auto"),
    RECOMMENDED("Recommended"),
    CUSTOM("Custom")
}

enum class CompositionMode(val label: String) {
    FIT("Fit"),
    FILL("Fill")
}

data class CanvasSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
        require(width % 2 == 0 && height % 2 == 0) { "H.264 output width and height must be even." }
    }
}

data class VideoAsset(
    val uri: Uri,
    val displayName: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceRotation: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val audioSampleRate: Int?,
    val audioChannels: Int?
) {
    val effectiveWidth: Int
        get() = if (sourceRotation == 90 || sourceRotation == 270) sourceHeight else sourceWidth
    val effectiveHeight: Int
        get() = if (sourceRotation == 90 || sourceRotation == 270) sourceWidth else sourceHeight
}

data class CompositionSettings(
    val mode: CompositionMode = CompositionMode.FIT,
    val zoom: Float = 1f,
    /** Normalized -1..1. Negative is left/up; positive is right/down. */
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    fun normalized(): CompositionSettings = copy(
        zoom = zoom.coerceIn(1f, 3f),
        panX = panX.coerceIn(-1f, 1f),
        panY = panY.coerceIn(-1f, 1f)
    )
}

data class StreamRequest(
    val streamName: String,
    val serverUrl: String,
    /** Secret. Never serialize this value into logs or project/history models. */
    val streamKey: String,
    val videoAsset: VideoAsset,
    val format: LiveFormat,
    val quality: VideoQuality,
    val fps: Int,
    val bitrateMode: BitrateMode,
    val customBitrateBps: Int?,
    val loopVideo: Boolean,
    val composition: CompositionSettings
) {
    fun canvas(): CanvasSize = format.outputSize(quality)

    fun videoBitrateBps(): Int {
        val size = canvas()
        val recommended = if (quality == VideoQuality.P1080) 5_000_000 else 2_800_000
        return when (bitrateMode) {
            BitrateMode.AUTO -> if (quality == VideoQuality.P1080) 4_000_000 else 2_200_000
            BitrateMode.RECOMMENDED -> recommended
            BitrateMode.CUSTOM -> (customBitrateBps ?: recommended).coerceIn(
                1_000_000,
                if (quality == VideoQuality.P1080) 8_000_000 else 5_000_000
            )
        }.coerceAtMost(if (size.width * size.height >= 1920L * 1080L) 8_000_000 else 5_000_000)
    }

    fun audioBitrateBps(): Int = 128_000

    /** Never include the URL/key in generated diagnostics or incidental stringification. */
    override fun toString(): String = "StreamRequest(name=${streamName.take(40)}, format=$format, quality=$quality, fps=$fps)"
}

enum class StreamState {
    IDLE,
    PREPARING,
    ENCODER_READY,
    RTMP_CONNECTING,
    RTMP_CONNECTED,
    RTMP_PUBLISHING,
    INGEST_VERIFYING,
    LIVE_VERIFIED,
    RECONNECTING,
    STOPPING,
    STOPPED,
    ERROR
}

data class SessionSnapshot(
    val state: StreamState = StreamState.IDLE,
    val statusText: String = "READY",
    val errorMessage: String? = null,
    val engineStatus: String = "READY",
    val rtmpStatus: String = "DISCONNECTED",
    val ingestStatus: String = "NOT VERIFIED",
    val startedAtElapsedMs: Long? = null,
    val elapsedMs: Long = 0L,
    val width: Int? = null,
    val height: Int? = null,
    val targetFps: Int? = null,
    val actualFps: Int? = null,
    val encodedVideoBitrateBps: Long? = null,
    val encodedAudioBitrateBps: Long? = null,
    val uploadBitrateBps: Long? = null,
    val droppedVideoFrames: Long? = null,
    val encodedVideoFrames: Long? = null,
    val sentBytes: Long? = null,
    val reconnectCount: Int = 0,
    val networkConnected: Boolean? = null,
    val sendQueueBytes: Long? = null,
    val loopCount: Long = 0L
) {
    val isActive: Boolean get() = state !in setOf(StreamState.IDLE, StreamState.STOPPED, StreamState.ERROR)
    val isStreaming: Boolean get() = state in setOf(
        StreamState.RTMP_CONNECTING,
        StreamState.RTMP_CONNECTED,
        StreamState.RTMP_PUBLISHING,
        StreamState.INGEST_VERIFYING,
        StreamState.LIVE_VERIFIED,
        StreamState.RECONNECTING,
        StreamState.STOPPING
    )
}

data class SavedProject(
    val name: String,
    val sourceName: String,
    val sourceUri: String,
    val format: LiveFormat,
    val quality: VideoQuality,
    val savedAtMs: Long
)

data class LiveHistoryEntry(
    val name: String,
    val format: LiveFormat,
    val resolution: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val result: String
)
