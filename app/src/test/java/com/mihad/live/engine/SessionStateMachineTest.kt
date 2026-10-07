package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun `publishing lifecycle proves packet flow and can reconnect without resetting state`() {
        val machine = SessionStateMachine()
        assertTrue(machine.moveTo(StreamState.PREPARING))
        assertTrue(machine.moveTo(StreamState.ENCODER_READY))
        assertTrue(machine.moveTo(StreamState.CONNECTING_TO_YOUTUBE))
        assertTrue(machine.moveTo(StreamState.RTMP_HANDSHAKE))
        assertTrue(machine.moveTo(StreamState.CONNECTING_TO_YOUTUBE))
        assertTrue(machine.moveTo(StreamState.PUBLISHING))
        assertTrue(machine.moveTo(StreamState.MEDIA_FLOWING))
        assertTrue(machine.moveTo(StreamState.INGEST_CONNECTED))
        assertTrue(machine.moveTo(StreamState.RECONNECTING))
        assertTrue(machine.moveTo(StreamState.CONNECTING_TO_YOUTUBE))
        assertTrue(machine.moveTo(StreamState.RTMP_HANDSHAKE))
        assertTrue(machine.moveTo(StreamState.CONNECTING_TO_YOUTUBE))
        assertTrue(machine.moveTo(StreamState.PUBLISHING))
        assertEquals(StreamState.PUBLISHING, machine.current)
    }

    @Test
    fun `cannot claim live before ingest is connected`() {
        val machine = SessionStateMachine()
        assertFalse(machine.moveTo(StreamState.LIVE))
        assertTrue(machine.moveTo(StreamState.PREPARING))
        assertTrue(machine.moveTo(StreamState.ENCODER_READY))
        assertTrue(machine.moveTo(StreamState.CONNECTING_TO_YOUTUBE))
        assertTrue(machine.moveTo(StreamState.RTMP_HANDSHAKE))
        assertTrue(machine.moveTo(StreamState.CONNECTING_TO_YOUTUBE))
        assertTrue(machine.moveTo(StreamState.PUBLISHING))
        assertFalse(machine.moveTo(StreamState.LIVE))
        assertEquals(StreamState.PUBLISHING, machine.current)
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
