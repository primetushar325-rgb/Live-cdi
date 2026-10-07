package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun `publish starts only after server acceptance and reconnects through RTMP connect`() {
        val machine = SessionStateMachine()
        assertTrue(machine.moveTo(StreamState.PREPARING))
        assertTrue(machine.moveTo(StreamState.ENCODER_READY))
        assertTrue(machine.moveTo(StreamState.CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_HANDSHAKE))
        assertTrue(machine.moveTo(StreamState.CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_CONNECTED))
        assertTrue(machine.moveTo(StreamState.PUBLISHING))
        assertTrue(machine.moveTo(StreamState.MEDIA_FLOWING))
        assertTrue(machine.moveTo(StreamState.RECONNECTING))
        assertTrue(machine.moveTo(StreamState.CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_HANDSHAKE))
        assertTrue(machine.moveTo(StreamState.CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_CONNECTED))
        assertEquals(StreamState.RTMP_CONNECTED, machine.current)
    }

    @Test
    fun `cannot claim YouTube ingest or live from local media flow`() {
        val machine = SessionStateMachine()
        assertFalse(machine.moveTo(StreamState.LIVE))
        assertTrue(machine.moveTo(StreamState.PREPARING))
        assertTrue(machine.moveTo(StreamState.ENCODER_READY))
        assertTrue(machine.moveTo(StreamState.CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_HANDSHAKE))
        assertTrue(machine.moveTo(StreamState.CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_CONNECTED))
        assertTrue(machine.moveTo(StreamState.PUBLISHING))
        assertTrue(machine.moveTo(StreamState.MEDIA_FLOWING))
        assertFalse(machine.moveTo(StreamState.YOUTUBE_INGEST_DETECTED))
        assertFalse(machine.moveTo(StreamState.LIVE))
        assertEquals(StreamState.MEDIA_FLOWING, machine.current)
    }

    @Test
    fun `encoder errors can be stopped cleanly`() {
        val machine = SessionStateMachine()
        assertTrue(machine.moveTo(StreamState.PREPARING))
        assertTrue(machine.moveTo(StreamState.ERROR))
        assertTrue(machine.moveTo(StreamState.STOPPING))
        assertTrue(machine.moveTo(StreamState.STOPPED))
    }
}
