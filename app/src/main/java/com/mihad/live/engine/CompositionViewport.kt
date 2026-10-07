package com.mihad.live.engine

import com.pedro.encoder.utils.ViewPort
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fit/fill + bounded zoom/pan calculation shared by the UI preview and the output
 * surface. Values are converted to the same normalized crop in both canvases.
 */
object CompositionViewport {

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun toRootEncoder(): ViewPort = ViewPort(x, y, width, height)
    }

    fun calculate(
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        settings: CompositionSettings
    ): Rect {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(canvasWidth > 0 && canvasHeight > 0)
        val normalized = settings.normalized()
        val fit = min(canvasWidth.toDouble() / sourceWidth, canvasHeight.toDouble() / sourceHeight)
        val fill = max(canvasWidth.toDouble() / sourceWidth, canvasHeight.toDouble() / sourceHeight)
        val base = if (normalized.mode == CompositionMode.FIT) fit else fill
        val scale = base * normalized.zoom
        val displayWidth = sourceWidth * scale
        val displayHeight = sourceHeight * scale

        val availableX = kotlin.math.abs(canvasWidth - displayWidth) / 2.0
        val availableY = kotlin.math.abs(canvasHeight - displayHeight) / 2.0
        val left = (canvasWidth - displayWidth) / 2.0 + normalized.panX * availableX
        val top = (canvasHeight - displayHeight) / 2.0 + normalized.panY * availableY

        val width = displayWidth.roundToInt().coerceAtLeast(1)
        val height = displayHeight.roundToInt().coerceAtLeast(1)
        val x = left.roundToInt()
        // GL viewport origin is bottom-left; UI composition coordinates start top-left.
        val yBottom = (canvasHeight - (top + displayHeight)).roundToInt()
        return Rect(x, yBottom, width, height)
    }

    /** Same crop/fit composition on a preview surface with a different pixel size. */
    fun forPreview(
        sourceWidth: Int,
        sourceHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        settings: CompositionSettings
    ): Rect = calculate(sourceWidth, sourceHeight, previewWidth, previewHeight, settings)
}
