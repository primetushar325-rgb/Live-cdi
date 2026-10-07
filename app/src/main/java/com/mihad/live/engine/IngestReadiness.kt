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
    /** A real raw video FLV packet was successfully flushed in this accepted publish attempt. */
    val mediaFlowing: Boolean,
    /** Local packet evidence is complete; this is not proof of YouTube receipt. */
    val localMediaReady: Boolean
)

/**
 * This reducer never claims YouTube ingest. It only reports RTMP protocol responses and local
 * successful packet writes. YouTube receipt and broadcast-LIVE state require external evidence.
 */
object IngestReadiness {
    fun assess(evidence: IngestEvidence): IngestAssessment {
        if (evidence.handshakeFailed) return IngestAssessment("RTMP HANDSHAKE FAILED", false, false)
        if (evidence.connectFailed) return IngestAssessment("RTMP CONNECT FAILED", false, false)
        if (evidence.publishFailed) return IngestAssessment("RTMP PUBLISH FAILED", false, false)
        if (!evidence.publishAccepted) {
            val status = when {
                evidence.publishSent -> "RTMP CONNECTED — PUBLISH RESPONSE PENDING"
                evidence.connectAccepted -> "RTMP CONNECTED — PUBLISH NOT ACCEPTED"
                else -> "RTMP NOT CONNECTED"
            }
            return IngestAssessment(status, false, false)
        }

        val videoFlowing = evidence.videoPackets > 0
        if (!videoFlowing) {
            val status = if (evidence.audioPackets > 0) {
                "RTMP PUBLISHING — AUDIO WRITES, NO VIDEO PACKETS"
            } else {
                "RTMP PUBLISHING — NO VIDEO PACKETS"
            }
            return IngestAssessment(status, false, false)
        }
        if (evidence.videoCodecConfigs == 0L) {
            return IngestAssessment("MEDIA FLOWING — WAITING FOR AVC CONFIG", true, false)
        }
        if (evidence.videoKeyframes == 0L) {
            return IngestAssessment("MEDIA FLOWING — WAITING FOR KEYFRAME", true, false)
        }
        if (evidence.audioExpected && (evidence.audioCodecConfigs == 0L || evidence.audioPackets == 0L)) {
            return IngestAssessment("MEDIA FLOWING — AUDIO NOT FLOWING", true, false)
        }
        return IngestAssessment("RTMP MEDIA FLOWING — YOUTUBE RECEIPT NOT VERIFIED", true, true)
    }
}
