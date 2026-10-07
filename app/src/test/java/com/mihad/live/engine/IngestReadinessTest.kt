package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestReadinessTest {

    @Test
    fun `handshake failure is distinct from publish failure`() {
        assertEquals(
            "INGEST: HANDSHAKE FAILED",
            IngestReadiness.assess(IngestEvidence(handshakeFailed = true, publishFailed = true)).status
        )
        assertEquals(
            "INGEST: PUBLISH FAILED",
            IngestReadiness.assess(IngestEvidence(publishFailed = true)).status
        )
    }

    @Test
    fun `accepted publish with no flushed media is not connected`() {
        val result = IngestReadiness.assess(IngestEvidence(publishAccepted = true))
        assertEquals("INGEST: CONNECTED — MEDIA NOT FLOWING", result.status)
        assertFalse(result.mediaFlowing)
        assertFalse(result.connected)
    }

    @Test
    fun `codec configuration alone does not establish media flow`() {
        val result = IngestReadiness.assess(
            IngestEvidence(publishAccepted = true, videoCodecConfigs = 1)
        )
        assertEquals("INGEST: CONNECTED — MEDIA NOT FLOWING", result.status)
        assertFalse(result.connected)
    }

    @Test
    fun `media writes without accepted publish cannot establish ingest`() {
        val result = IngestReadiness.assess(
            IngestEvidence(
                videoPackets = 1,
                videoKeyframes = 1,
                videoCodecConfigs = 1
            )
        )
        assertEquals("INGEST: NOT CONNECTED", result.status)
        assertFalse(result.connected)
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
        assertEquals("INGEST: MEDIA FLOWING — AUDIO NOT FLOWING", result.status)
        assertTrue(result.mediaFlowing)
        assertFalse(result.connected)
    }

    @Test
    fun `connected requires publish acceptance and flushed codec and media packets`() {
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
        assertEquals("INGEST: CONNECTED", result.status)
        assertTrue(result.connected)
    }
}
