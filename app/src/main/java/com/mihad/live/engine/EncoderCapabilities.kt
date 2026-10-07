package com.mihad.live.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Conservative H.264 device capability check performed before encoder creation. */
object EncoderCapabilities {

    data class Result(
        val supported: Boolean,
        val hardwareAvailable: Boolean,
        val reason: String? = null
    )

    fun check(canvas: CanvasSize, fps: Int, bitrateBps: Int): Result {
        if (fps !in 15..60) return Result(false, false, "Choose a frame rate between 15 and 60 FPS")
        if (canvas.width % 2 != 0 || canvas.height % 2 != 0) {
            return Result(false, false, "H.264 requires even output dimensions")
        }
        if (bitrateBps !in 500_000..12_000_000) {
            return Result(false, false, "Video bitrate is outside the supported safety range")
        }

        var anySupported = false
        var hardwareSupported = false
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        for (codec in codecs) {
            if (!codec.isEncoder) continue
            val mime = codec.supportedTypes.firstOrNull { it.equals("video/avc", ignoreCase = true) } ?: continue
            val caps = runCatching { codec.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
            val sizeSupported = runCatching { caps.areSizeAndRateSupported(canvas.width, canvas.height, fps.toDouble()) }
                .getOrDefault(false)
            val bitrateSupported = runCatching { caps.bitrateRange.contains(bitrateBps) }.getOrDefault(false)
            if (sizeSupported && bitrateSupported) {
                anySupported = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!codec.isSoftwareOnly) hardwareSupported = true
                } else {
                    val name = codec.name.lowercase()
                    if (!name.startsWith("omx.google.") && !name.contains("sw.") && !name.contains("software")) {
                        hardwareSupported = true
                    }
                }
            }
        }
        return when {
            hardwareSupported -> Result(true, true)
            anySupported -> Result(true, false, "Hardware H.264 encoder unavailable; the system encoder may be used")
            else -> Result(false, false, "This device does not report an H.264 encoder for the selected resolution, FPS and bitrate")
        }
    }
}
