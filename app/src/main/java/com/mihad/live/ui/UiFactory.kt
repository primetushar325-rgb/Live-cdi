package com.mihad.live.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.mihad.live.R
import com.mihad.live.engine.LiveFormat
import kotlin.math.min

internal fun Context.dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

internal fun Context.colorResource(id: Int): Int = ContextCompat.getColor(this, id)

internal fun Context.label(
    text: CharSequence,
    sizeSp: Float = 14f,
    color: Int = R.color.ml_text,
    bold: Boolean = false,
    allCaps: Boolean = false
): TextView = TextView(this).apply {
    this.text = if (allCaps) text.toString().uppercase() else text
    textSize = sizeSp
    setTextColor(colorResource(color))
    if (bold) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    gravity = Gravity.CENTER_VERTICAL
}

internal fun Context.card(strokeColor: Int = R.color.ml_stroke, radiusDp: Int = 20): MaterialCardView =
    MaterialCardView(this).apply {
        radius = dp(radiusDp).toFloat()
        cardElevation = dp(2).toFloat()
        setCardBackgroundColor(colorResource(R.color.ml_surface))
        strokeWidth = dp(1)
        setStrokeColor(colorResource(strokeColor))
        isClickable = false
        isFocusable = false
    }

internal fun Context.button(
    text: String,
    primary: Boolean = false,
    stroke: Boolean = false
): MaterialButton = MaterialButton(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    setTextColor(colorResource(if (primary) R.color.ml_black else R.color.ml_text))
    cornerRadius = dp(16)
    insetTop = 0
    insetBottom = 0
    minHeight = dp(52)
    if (primary) {
        backgroundTintList = ColorStateList.valueOf(colorResource(R.color.ml_cyan))
        elevation = dp(5).toFloat()
    } else {
        backgroundTintList = ColorStateList.valueOf(colorResource(R.color.ml_surface_2))
        if (stroke) {
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(colorResource(R.color.ml_cyan_dim))
        }
    }
}

internal fun Context.vertical(spacing: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (spacing > 0) dividerDrawable = GradientDrawable().apply { setSize(1, dp(spacing)) }
}

internal fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

internal fun LinearLayout.addSpace(heightDp: Int) {
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(heightDp)))
}

internal fun LinearLayout.addHorizontalSpace(widthDp: Int) {
    addView(View(context), LinearLayout.LayoutParams(context.dp(widthDp), 1))
}

internal fun Context.sectionTitle(text: String): TextView = label(
    text = text,
    sizeSp = 12f,
    color = R.color.ml_text_secondary,
    bold = true,
    allCaps = true
).apply { letterSpacing = 0.12f }

internal fun Context.scrollColumn(paddingDp: Int = 22): Pair<ScrollView, LinearLayout> {
    val column = vertical().apply {
        setPadding(dp(paddingDp), dp(16), dp(paddingDp), dp(36))
        clipToPadding = false
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = false
        clipToPadding = false
        addView(column, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return scroll to column
}

internal fun Context.header(title: String, onBack: (() -> Unit)?): LinearLayout = horizontal().apply {
    setPadding(dp(18), dp(12), dp(18), dp(12))
    val back = button(if (onBack == null) "●" else "‹", primary = false, stroke = false).apply {
        minWidth = dp(44)
        maxWidth = dp(44)
        minHeight = dp(44)
        textSize = if (onBack == null) 18f else 30f
        if (onBack == null) setTextColor(colorResource(R.color.ml_cyan))
        setOnClickListener { onBack?.invoke() }
    }
    addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
    addView(View(context), LinearLayout.LayoutParams(dp(14), 1))
    addView(vertical().apply {
        addView(label(title, sizeSp = 18f, bold = true))
        addView(label("Stream. Stay Live.", sizeSp = 11f, color = R.color.ml_text_secondary))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

/** Full-width layout slot with a centered output-ratio canvas (9:16 is capped to a phone-friendly height). */
internal class AspectCanvasHost(context: Context) : FrameLayout(context) {
    var format: LiveFormat = LiveFormat.LANDSCAPE
        set(value) {
            field = value
            requestLayout()
        }
    var maxCanvasHeightPx: Int = context.dp(390)
    var canvasView: View? = null

    init {
        setBackgroundColor(context.colorResource(R.color.ml_bg_elevated))
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: context.dp(320)
        val ratio = format.widthRatio.toFloat() / format.heightRatio.toFloat()
        val childWidth: Int
        val childHeight: Int
        if (ratio >= 1f) {
            childWidth = availableWidth
            childHeight = (childWidth / ratio).toInt().coerceAtLeast(1)
        } else {
            childHeight = min(maxCanvasHeightPx, (availableWidth / ratio).toInt()).coerceAtLeast(1)
            childWidth = (childHeight * ratio).toInt().coerceAtLeast(1)
        }
        val totalHeight = maxOf(childHeight, context.dp(120))
        val child = canvasView
        child?.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(availableWidth, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = canvasView ?: return
        val childLeft = (width - child.measuredWidth) / 2
        child.layout(childLeft, 0, childLeft + child.measuredWidth, child.measuredHeight)
    }
}

internal class RatioVisualView(context: Context, private val format: LiveFormat, private val selected: Boolean) : View(context) {
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = context.colorResource(if (selected) R.color.ml_cyan else R.color.ml_text_muted)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.colorResource(if (selected) R.color.ml_cyan_glow_soft else R.color.ml_surface_2)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.colorResource(R.color.ml_cyan)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ratio = format.widthRatio.toFloat() / format.heightRatio.toFloat()
        val availableW = width - context.dp(4)
        val availableH = height - context.dp(4)
        val boxW: Float
        val boxH: Float
        if (ratio >= 1f) {
            boxW = availableW.toFloat()
            boxH = min(availableH.toFloat(), boxW / ratio)
        } else {
            boxH = availableH.toFloat()
            boxW = min(availableW.toFloat(), boxH * ratio)
        }
        val left = (width - boxW) / 2f
        val top = (height - boxH) / 2f
        val rect = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rect, context.dp(6).toFloat(), context.dp(6).toFloat(), fill)
        canvas.drawRoundRect(rect, context.dp(6).toFloat(), context.dp(6).toFloat(), border)
        canvas.drawCircle(rect.centerX(), rect.centerY(), context.dp(3).toFloat(), dot)
    }
}
