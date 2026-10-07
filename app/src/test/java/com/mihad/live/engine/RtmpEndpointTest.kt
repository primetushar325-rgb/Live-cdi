package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpEndpointTest {

    @Test
    fun `preserves the exact server prefix and raw key characters`() {
        assertEquals(
            "rtmps://a.rtmp.youtube.com:443/live2/test-key_123.a~b:live",
            RtmpEndpoint.build("rtmps://a.rtmp.youtube.com:443/live2", "test-key_123.a~b:live")
        )
    }

    @Test
    fun `preserves scheme host explicit port and encoded application path verbatim`() {
        val server = "RTMPS://YouTube.Example:443/live2/custom%20path/"
        assertEquals("${server}Key_123", RtmpEndpoint.build(server, "Key_123"))
    }

    @Test
    fun `does not duplicate a server path separator`() {
        assertEquals(
            "rtmps://a.rtmp.youtube.com/live2/key-1",
            RtmpEndpoint.build("rtmps://a.rtmp.youtube.com/live2/", "key-1")
        )
    }

    @Test
    fun `rejects whitespace rather than silently changing credentials`() {
        assertFalse(RtmpEndpoint.validateServer(" rtmps://a.rtmp.youtube.com/live2").valid)
        assertFalse(RtmpEndpoint.validateKey(" key-1").valid)
    }

    @Test
    fun `rejects HTTP endpoint`() {
        val result = RtmpEndpoint.validateServer("https://example.com/live")
        assertFalse(result.valid)
        assertEquals("Use an RTMP or RTMPS server URL", result.message)
    }

    @Test
    fun `requires application path and host`() {
        assertFalse(RtmpEndpoint.validateServer("rtmp://example.com").valid)
        assertFalse(RtmpEndpoint.validateServer("rtmp:///live").valid)
    }

    @Test
    fun `rejects stream key embedded in server URL`() {
        assertFalse(RtmpEndpoint.validateServer("rtmps://user:secret@example.com/live").valid)
        assertFalse(RtmpEndpoint.validateKey("rtmps://example.com/live/key").valid)
    }

    @Test
    fun `key is kept separate and cannot contain whitespace`() {
        assertTrue(RtmpEndpoint.validateKey("abc-123").valid)
        assertFalse(RtmpEndpoint.validateKey("abc 123").valid)
    }
}
