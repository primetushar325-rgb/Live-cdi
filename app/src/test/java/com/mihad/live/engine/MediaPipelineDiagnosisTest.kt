package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPipelineDiagnosisTest {

    @Test
    fun `identifies source decoder before encoder when nothing is decoded`() {
        val failure = MediaPipelineDiagnosis.firstFailure(evidence(decodedVideoFrames = 0))
        assertEquals("VIDEO SOURCE / DECODER", failure?.stage)
    }

    @Test
    fun `identifies H264 encoder when source frames exist`() {
        val failure = MediaPipelineDiagnosis.firstFailure(
            evidence(decodedVideoFrames = 20, encodedVideoFrames = 0)
        )
        assertEquals("H.264 ENCODER", failure?.stage)
    }

    @Test
    fun `identifies AVC config and video writer failures separately`() {
        val missingConfig = MediaPipelineDiagnosis.firstFailure(
            evidence(decodedVideoFrames = 20, encodedVideoFrames = 20)
        )
        assertEquals("AVC CONFIG / SPS-PPS", missingConfig?.stage)

        val missingPacket = MediaPipelineDiagnosis.firstFailure(
            evidence(decodedVideoFrames = 20, encodedVideoFrames = 20, h264ConfigWrites = 1)
        )
        assertEquals("FLV / RTMP VIDEO WRITER", missingPacket?.stage)
    }

    @Test
    fun `identifies keyframe and AAC failures after video flow`() {
        val noKeyframe = MediaPipelineDiagnosis.firstFailure(
            evidence(decodedVideoFrames = 20, encodedVideoFrames = 20, h264ConfigWrites = 1, videoPacketWrites = 20)
        )
        assertEquals("H.264 KEYFRAME", noKeyframe?.stage)

        val noAudio = MediaPipelineDiagnosis.firstFailure(
            evidence(
                decodedVideoFrames = 20,
                encodedVideoFrames = 20,
                h264ConfigWrites = 1,
                videoPacketWrites = 20,
                keyframeWrites = 1,
                decodedAudioFrames = 20,
                encodedAudioFrames = 20,
                aacConfigWrites = 1,
                audioPacketWrites = 0,
                audioExpected = true
            )
        )
        assertEquals("FLV / RTMP AUDIO WRITER", noAudio?.stage)
    }

    @Test
    fun `video only source does not fail due to unused audio pipeline`() {
        val result = MediaPipelineDiagnosis.firstFailure(
            evidence(
                decodedVideoFrames = 20,
                encodedVideoFrames = 20,
                h264ConfigWrites = 1,
                videoPacketWrites = 20,
                keyframeWrites = 1,
                audioExpected = false
            )
        )
        assertNull(result)
    }

    private fun evidence(
        decodedVideoFrames: Long = 0,
        encodedVideoFrames: Long = 0,
        h264ConfigWrites: Long = 0,
        videoPacketWrites: Long = 0,
        keyframeWrites: Long = 0,
        decodedAudioFrames: Long = 0,
        encodedAudioFrames: Long = 0,
        aacConfigWrites: Long = 0,
        audioPacketWrites: Long = 0,
        audioExpected: Boolean = false
    ) = MediaPipelineEvidence(
        decodedVideoFrames = decodedVideoFrames,
        encodedVideoFrames = encodedVideoFrames,
        h264ConfigWrites = h264ConfigWrites,
        videoPacketWrites = videoPacketWrites,
        keyframeWrites = keyframeWrites,
        decodedAudioFrames = decodedAudioFrames,
        encodedAudioFrames = encodedAudioFrames,
        aacConfigWrites = aacConfigWrites,
        audioPacketWrites = audioPacketWrites,
        audioExpected = audioExpected
    )
}
