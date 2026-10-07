package com.mihad.live.engine

import com.pedro.common.MonotonicTimestampNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicTimestampNormalizerTest {

    @Test
    fun `preserves naturally increasing source timestamps`() {
        val normalizer = MonotonicTimestampNormalizer()
        assertEquals(1_000_000L, normalizer.normalize(1_000_000L, FRAME_30_FPS_NS))
        assertEquals(34_333_333L, normalizer.normalize(34_333_333L, FRAME_30_FPS_NS))
        assertEquals(67_666_666L, normalizer.normalize(67_666_666L, FRAME_30_FPS_NS))
    }

    @Test
    fun `moves a loop reset forward and keeps later frames continuous`() {
        val normalizer = MonotonicTimestampNormalizer()
        val first = normalizer.normalize(1_000_000_000L, FRAME_30_FPS_NS)
        val beforeLoop = normalizer.normalize(1_033_333_333L, FRAME_30_FPS_NS)
        val firstAfterLoop = normalizer.normalize(0L, FRAME_30_FPS_NS)
        val nextAfterLoop = normalizer.normalize(33_333_333L, FRAME_30_FPS_NS)

        assertTrue(beforeLoop > first)
        assertEquals(beforeLoop + FRAME_30_FPS_NS, firstAfterLoop)
        assertEquals(firstAfterLoop + FRAME_30_FPS_NS, nextAfterLoop)
    }

    @Test
    fun `repeated timestamps advance by at least one frame duration`() {
        val normalizer = MonotonicTimestampNormalizer()
        val first = normalizer.normalize(5_000L, FRAME_30_FPS_NS)
        val second = normalizer.normalize(5_000L, FRAME_30_FPS_NS)
        val third = normalizer.normalize(5_000L, FRAME_30_FPS_NS)

        assertEquals(first + FRAME_30_FPS_NS, second)
        assertEquals(second + FRAME_30_FPS_NS, third)
    }

    @Test
    fun `reset starts a new independent presentation timeline`() {
        val normalizer = MonotonicTimestampNormalizer()
        normalizer.normalize(2_000_000L, FRAME_30_FPS_NS)
        normalizer.normalize(35_000_000L, FRAME_30_FPS_NS)
        normalizer.reset()

        assertEquals(0L, normalizer.normalize(0L, FRAME_30_FPS_NS))
    }

    private companion object {
        const val FRAME_30_FPS_NS = 33_333_333L
    }
}
