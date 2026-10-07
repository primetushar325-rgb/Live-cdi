package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpEndpointTest {

    @Test
    fun `builds RTMPS endpoint only from validated server and key`() {
        assertEquals(
            "rtmps://a.rtmp.youtube.com/live2/test-key-123",
            RtmpEndpoint.build(" rtmps://a.rtmp.youtube.com/live2/ ", " test-key-123 ")
        )
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
