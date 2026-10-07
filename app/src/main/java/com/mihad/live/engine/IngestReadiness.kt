package com.mihad.live.engine

/** Evidence from RTMP protocol callbacks and successfully flushed FLV media packets. */
data class IngestEvidence(
    val handshakeFailed: Boolean = false,
    val connectFailed: Boolean = false,
    val connectAccepted: Boolean = false,
    val publishSent: Boolean = false,
    val publishAccepted: Boolean = false,
    val publishFailed: Boolean = false,
    val videoPackets: Long = 0,
    val videoKeyframes: Long = 0,
    val videoCodecConfigs: Long = 0,
    val audioExpected: Boolean = false,
    val audioPackets: Long = 0,
    val audioCodecConfigs: Long = 0
)

data class IngestAssessment(
    val status: String,
    val mediaFlowing: Boolean,
    val connected: Boolean
)

/**
 * Never infers ingest from preview, encoder output, elapsed time or an open socket. A connected
 * result requires RTMP publish acceptance plus successful H.264 config, keyframe and media writes;
 * AAC config and raw audio writes are also required when the selected source has audio.
 */
object IngestReadiness {
    fun assess(evidence: IngestEvidence): IngestAssessment {
        if (evidence.handshakeFailed) return IngestAssessment("INGEST: HANDSHAKE FAILED", false, false)
        if (evidence.connectFailed) return IngestAssessment("INGEST: CONNECT FAILED", false, false)
        if (evidence.publishFailed) return IngestAssessment("INGEST: PUBLISH FAILED", false, false)
        if (!evidence.publishAccepted) {
            val status = if (evidence.publishSent || evidence.connectAccepted) "INGEST: PUBLISHING" else "INGEST: NOT CONNECTED"
            return IngestAssessment(status, false, false)
        }

        val mediaFlowing = evidence.videoPackets > 0 || evidence.audioPackets > 0
        if (!mediaFlowing) {
            return IngestAssessment("INGEST: CONNECTED — MEDIA NOT FLOWING", false, false)
        }
        if (evidence.videoCodecConfigs == 0L) {
            return IngestAssessment("INGEST: MEDIA FLOWING — WAITING FOR H.264 CONFIG", true, false)
        }
        if (evidence.videoPackets == 0L) {
            return IngestAssessment("INGEST: MEDIA FLOWING — WAITING FOR VIDEO", true, false)
        }
        if (evidence.videoKeyframes == 0L) {
            return IngestAssessment("INGEST: MEDIA FLOWING — WAITING FOR KEYFRAME", true, false)
        }
        if (evidence.audioExpected && (evidence.audioCodecConfigs == 0L || evidence.audioPackets == 0L)) {
            return IngestAssessment("INGEST: MEDIA FLOWING — AUDIO NOT FLOWING", true, false)
        }
        return IngestAssessment("INGEST: CONNECTED", true, true)
    }
}
