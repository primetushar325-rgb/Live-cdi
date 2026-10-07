package com.mihad.live.engine

import com.pedro.common.frame.MediaFrame
import com.pedro.common.removeInfo
import com.pedro.common.toByteArray
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedBufferSliceTest {

    @Test
    fun `uses offset plus size and does not mutate the encoder buffer`() {
        val encoderBuffer = ByteBuffer.wrap(byteArrayOf(99, 10, 11, 12, 98))
        val payload = encoderBuffer.removeInfo(
            MediaFrame.Info(offset = 1, size = 3, timestamp = 0L, isKeyFrame = false)
        )
        val bytes = ByteArray(payload.remaining())
        payload.get(bytes)

        assertArrayEquals(byteArrayOf(10, 11, 12), bytes)
        assertEquals(0, encoderBuffer.position())
        assertEquals(5, encoderBuffer.limit())
    }

    @Test
    fun `byte array copy respects a sliced buffer view without moving its position`() {
        val backing = ByteBuffer.wrap(byteArrayOf(90, 91, 10, 11, 12, 92))
        backing.position(2)
        backing.limit(5)
        val view = backing.slice()
        view.position(1)

        assertArrayEquals(byteArrayOf(10, 11, 12), view.toByteArray())
        assertEquals(1, view.position())
    }
}
