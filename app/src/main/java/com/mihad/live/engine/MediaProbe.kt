package com.mihad.live.engine

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/** Read metadata only; the real decode always runs in RootEncoder's MediaCodec pipeline. */
object MediaProbe {

    @Throws(IOException::class)
    fun inspect(context: Context, uri: Uri): VideoAsset {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: "Selected video"
        val retriever = MediaMetadataRetriever()
        var extractor: MediaExtractor? = null
        try {
            retriever.setDataSource(context, uri)
            val metadataWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val metadataHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

            extractor = MediaExtractor()
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
            } ?: throw IOException("Cannot open the selected video")

            var videoWidth = metadataWidth
            var videoHeight = metadataHeight
            var hasVideoTrack = false
            var audioSampleRate: Int? = null
            var audioChannels: Int? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> {
                        hasVideoTrack = true
                        if (format.containsKey(MediaFormat.KEY_WIDTH)) videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                        if (format.containsKey(MediaFormat.KEY_HEIGHT)) videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    mime.startsWith("audio/") -> {
                        if (audioSampleRate == null) {
                            audioSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            } else null
                            audioChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            } else null
                        }
                    }
                }
            }
            if (!hasVideoTrack || videoWidth <= 0 || videoHeight <= 0) {
                throw IOException("Unsupported video format: no readable video track was found")
            }
            return VideoAsset(
                uri = uri,
                displayName = displayName,
                sourceWidth = videoWidth,
                sourceHeight = videoHeight,
                sourceRotation = rotation,
                durationMs = durationMs,
                hasAudio = audioSampleRate != null && audioChannels != null,
                audioSampleRate = audioSampleRate,
                audioChannels = audioChannels
            )
        } catch (security: SecurityException) {
            throw IOException("The app cannot access this video. Please select it again.", security)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unsupported video format", e)
        } finally {
            runCatching { extractor?.release() }
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }
}
