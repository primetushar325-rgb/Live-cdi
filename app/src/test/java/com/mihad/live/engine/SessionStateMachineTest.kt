package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun `normal publishing lifecycle supports reconnect without resetting state`() {
        val machine = SessionStateMachine()
        assertTrue(machine.moveTo(StreamState.PREPARING))
        assertTrue(machine.moveTo(StreamState.ENCODER_READY))
        assertTrue(machine.moveTo(StreamState.RTMP_CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_PUBLISHING))
        assertTrue(machine.moveTo(StreamState.RECONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_CONNECTING))
        assertTrue(machine.moveTo(StreamState.RTMP_PUBLISHING))
        assertEquals(StreamState.RTMP_PUBLISHING, machine.current)
    }

    @Test
    fun `cannot claim live verified directly from idle`() {
        val machine = SessionStateMachine()
        assertFalse(machine.moveTo(StreamState.LIVE_VERIFIED))
        assertEquals(StreamState.IDLE, machine.current)
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
