package com.mihad.live.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionViewportTest {

    @Test
    fun `landscape source fits inside vertical output without stretching`() {
        val rect = CompositionViewport.calculate(
            sourceWidth = 1920,
            sourceHeight = 1080,
            canvasWidth = 1080,
            canvasHeight = 1920,
            settings = CompositionSettings(mode = CompositionMode.FIT)
        )
        assertEquals(CompositionViewport.Rect(0, 656, 1080, 608), rect)
    }

    @Test
    fun `portrait source fits inside landscape output without stretching`() {
        val rect = CompositionViewport.calculate(
            sourceWidth = 1080,
            sourceHeight = 1920,
            canvasWidth = 1280,
            canvasHeight = 720,
            settings = CompositionSettings(mode = CompositionMode.FIT)
        )
        assertEquals(CompositionViewport.Rect(438, 0, 405, 720), rect)
    }

    @Test
    fun `fill preserves ratio and crops outside the canvas`() {
        val rect = CompositionViewport.calculate(
            sourceWidth = 1920,
            sourceHeight = 1080,
            canvasWidth = 1080,
            canvasHeight = 1920,
            settings = CompositionSettings(mode = CompositionMode.FILL)
        )
        assertEquals(CompositionViewport.Rect(-1167, 0, 3413, 1920), rect)
    }

    @Test
    fun `zoom and pan change the viewport and reset returns fit`() {
        val fit = CompositionViewport.calculate(1920, 1080, 1080, 1920, CompositionSettings())
        val zoomed = CompositionViewport.calculate(
            1920, 1080, 1080, 1920,
            CompositionSettings(mode = CompositionMode.FIT, zoom = 2f, panX = 1f, panY = -1f)
        )
        assertEquals(1080, fit.width)
        assertEquals(2160, zoomed.width)
        assertEquals(0, CompositionSettings().zoom.compareTo(1f))
    }
}
