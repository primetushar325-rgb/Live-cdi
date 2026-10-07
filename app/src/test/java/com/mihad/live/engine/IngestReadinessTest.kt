package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestReadinessTest {

    @Test
    fun `handshake failure is distinct from RTMP publish failure`() {
        assertEquals(
            "RTMP HANDSHAKE FAILED",
            IngestReadiness.assess(IngestEvidence(handshakeFailed = true, publishFailed = true)).status
        )
        assertEquals(
            "RTMP PUBLISH FAILED",
            IngestReadiness.assess(IngestEvidence(publishFailed = true)).status
        )
    }

    @Test
    fun `connect and publish request do not mean publish was accepted`() {
        val result = IngestReadiness.assess(
            IngestEvidence(connectAccepted = true, publishSent = true)
        )
        assertEquals("RTMP CONNECTED — PUBLISH RESPONSE PENDING", result.status)
        assertFalse(result.mediaFlowing)
        assertFalse(result.localMediaReady)
    }

    @Test
    fun `accepted publish with no flushed video is not media flowing`() {
        val result = IngestReadiness.assess(IngestEvidence(publishAccepted = true))
        assertEquals("RTMP PUBLISHING — NO VIDEO PACKETS", result.status)
        assertFalse(result.mediaFlowing)
        assertFalse(result.localMediaReady)
    }

    @Test
    fun `codec configuration alone does not establish raw media flow`() {
        val result = IngestReadiness.assess(
            IngestEvidence(publishAccepted = true, videoCodecConfigs = 1)
        )
        assertEquals("RTMP PUBLISHING — NO VIDEO PACKETS", result.status)
        assertFalse(result.mediaFlowing)
    }

    @Test
    fun `media writes without accepted publish cannot establish media flow`() {
        val result = IngestReadiness.assess(
            IngestEvidence(
                videoPackets = 1,
                videoKeyframes = 1,
                videoCodecConfigs = 1
            )
        )
        assertEquals("RTMP NOT CONNECTED", result.status)
        assertFalse(result.mediaFlowing)
        assertFalse(result.localMediaReady)
    }

    @Test
    fun `audio-only writes do not mask missing video packets`() {
        val result = IngestReadiness.assess(
            IngestEvidence(publishAccepted = true, audioExpected = true, audioPackets = 12)
        )
        assertEquals("RTMP PUBLISHING — AUDIO WRITES, NO VIDEO PACKETS", result.status)
        assertFalse(result.mediaFlowing)
        assertFalse(result.localMediaReady)
    }

    @Test
    fun `audio source requires successful AAC config and raw packets`() {
        val result = IngestReadiness.assess(
            IngestEvidence(
                publishAccepted = true,
                videoPackets = 1,
                videoKeyframes = 1,
                videoCodecConfigs = 1,
                audioExpected = true,
                audioCodecConfigs = 1
            )
        )
        assertEquals("MEDIA FLOWING — AUDIO NOT FLOWING", result.status)
        assertTrue(result.mediaFlowing)
        assertFalse(result.localMediaReady)
    }

    @Test
    fun `complete local RTMP media evidence still does not claim YouTube receipt`() {
        val result = IngestReadiness.assess(
            IngestEvidence(
                publishAccepted = true,
                videoPackets = 1,
                videoKeyframes = 1,
                videoCodecConfigs = 1,
                audioExpected = true,
                audioPackets = 1,
                audioCodecConfigs = 1
            )
        )
        assertEquals("RTMP MEDIA FLOWING — YOUTUBE RECEIPT NOT VERIFIED", result.status)
        assertTrue(result.mediaFlowing)
        assertTrue(result.localMediaReady)
        assertFalse(result.status.contains("YOUTUBE INGEST DETECTED"))
        assertFalse(result.status.contains("LIVE"))
    }
}
