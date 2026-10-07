package com.mihad.live.engine

/** Counts measured after the current RTMP publish attempt began; no endpoint or key data. */
data class MediaPipelineEvidence(
    val decodedVideoFrames: Long,
    val encodedVideoFrames: Long,
    val h264ConfigWrites: Long,
    val videoPacketWrites: Long,
    val keyframeWrites: Long,
    val decodedAudioFrames: Long,
    val encodedAudioFrames: Long,
    val aacConfigWrites: Long,
    val audioPacketWrites: Long,
    val audioExpected: Boolean
)

data class MediaPipelineFailure(
    val code: String,
    val stage: String,
    val detail: String
)

/** Identifies the first missing measured stage instead of reporting a generic CONNECTED state. */
object MediaPipelineDiagnosis {
    fun firstFailure(evidence: MediaPipelineEvidence): MediaPipelineFailure? = when {
        evidence.decodedVideoFrames == 0L -> MediaPipelineFailure(
            "MEDIA_SOURCE_VIDEO_STALLED",
            "VIDEO SOURCE / DECODER",
            "The selected video decoder produced no frames after RTMP publish acceptance."
        )
        evidence.encodedVideoFrames == 0L -> MediaPipelineFailure(
            "MEDIA_H264_ENCODER_STALLED",
            "H.264 ENCODER",
            "Video source frames were decoded, but the H.264 encoder produced no output."
        )
        evidence.h264ConfigWrites == 0L -> MediaPipelineFailure(
            "MEDIA_AVC_CONFIG_NOT_SENT",
            "AVC CONFIG / SPS-PPS",
            "H.264 frames were encoded, but no AVC configuration packet was successfully written."
        )
        evidence.videoPacketWrites == 0L -> MediaPipelineFailure(
            "MEDIA_VIDEO_PACKET_NOT_SENT",
            "FLV / RTMP VIDEO WRITER",
            "H.264 configuration exists, but no raw video FLV packet was successfully written."
        )
        evidence.keyframeWrites == 0L -> MediaPipelineFailure(
            "MEDIA_KEYFRAME_NOT_SENT",
            "H.264 KEYFRAME",
            "Video packets were written, but no keyframe was successfully written."
        )
        evidence.audioExpected && evidence.decodedAudioFrames == 0L -> MediaPipelineFailure(
            "MEDIA_SOURCE_AUDIO_STALLED",
            "AUDIO SOURCE / DECODER",
            "The selected video's audio track produced no decoded PCM frames."
        )
        evidence.audioExpected && evidence.encodedAudioFrames == 0L -> MediaPipelineFailure(
            "MEDIA_AAC_ENCODER_STALLED",
            "AAC ENCODER",
            "Audio PCM frames were decoded, but the AAC encoder produced no output."
        )
        evidence.audioExpected && evidence.aacConfigWrites == 0L -> MediaPipelineFailure(
            "MEDIA_AAC_CONFIG_NOT_SENT",
            "AAC CONFIG",
            "AAC frames were encoded, but no AAC configuration packet was successfully written."
        )
        evidence.audioExpected && evidence.audioPacketWrites == 0L -> MediaPipelineFailure(
            "MEDIA_AUDIO_PACKET_NOT_SENT",
            "FLV / RTMP AUDIO WRITER",
            "AAC configuration exists, but no raw audio FLV packet was successfully written."
        )
        else -> null
    }
}
