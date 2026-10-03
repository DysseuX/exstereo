/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.ColorUtils
import org.fossify.musicplayer.spatial.PlaybackEnergy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A 64 dp transport disc in a 112 dp canvas using the selected pastel palette.
 * Call [setPlaying] from player state and [setMotionActive] from activity/pager
 * visibility. The normal View click listener and contentDescription are retained.
 * Pulse follows emitted PCM energy approximately, not headphone playback time.
 */
class SpatialPlayButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val compact: Boolean = false
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Path()
    private val glowColor = ColorUtils.blendARGB(SpatialStyle.accent, SpatialStyle.card, 0.45f)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, dp(54f),
            intArrayOf(SpatialStyle.withAlpha(glowColor, 0), SpatialStyle.withAlpha(glowColor, 85),
                SpatialStyle.withAlpha(glowColor, 0)),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, dp(42f),
            intArrayOf(SpatialStyle.withAlpha(SpatialStyle.ink, 38), SpatialStyle.withAlpha(SpatialStyle.ink, 23),
                SpatialStyle.withAlpha(SpatialStyle.ink, 0)),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, -dp(32f), dp(16f), dp(32f),
            ColorUtils.blendARGB(SpatialStyle.accent, SpatialStyle.card, 0.3f),
            SpatialStyle.accent, Shader.TileMode.CLAMP)
    }
    private val particleX = FloatArray(PARTICLE_COUNT)
    private val particleY = FloatArray(PARTICLE_COUNT)
    private val particleSpeed = FloatArray(PARTICLE_COUNT)
    private val particleSize = FloatArray(PARTICLE_COUNT)
    private var playing = false
    private var motionActive = false
    private var framePosted = false
    private var lastFrameNanos = 0L
    private var sparkStartNanos = 0L
    private var envelope = 0f
    private var average = 0f
    private var transient = 0f
    private var burstNumber = 0
    private var motionReady = false

    private val frame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!canAnimate()) {
                stopMotion()
                return
            }
            val now = System.nanoTime()
            val elapsed = if (lastFrameNanos == 0L) 1f / 30f else
                ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNanos = now
            val target = if (playing) PlaybackEnergy.level(now) else 0f
            val tau = if (target > envelope) 0.045f else 0.22f
            envelope += (target - envelope) * (1f - exp(-elapsed / tau))
            average += (target - average) * (1f - exp(-elapsed / 0.45f))
            transient = (envelope - average).coerceIn(0f, 0.35f)
            if (sparkStartNanos != 0L && now - sparkStartNanos >= SPARK_DURATION_NANOS) sparkStartNanos = 0L
            invalidate()
            requestFrame()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumWidth = dp(if (compact) 64f else 112f).toInt()
        minimumHeight = dp(if (compact) 64f else 112f).toInt()
        motionReady = true
    }

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        if (!value) {
            envelope = 0f
            average = 0f
            transient = 0f
        }
        invalidate()
        requestFrame()
    }

    /** False when the central page is hidden or its activity is not resumed. */
    fun setMotionActive(value: Boolean) {
        motionActive = value
        if (value) requestFrame() else stopMotion()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(minimumWidth, widthMeasureSpec), resolveSize(minimumHeight, heightMeasureSpec))
    }

    override fun performClick(): Boolean {
        if (isEnabled && canAnimate()) startSparks()
        return super.performClick()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!motionReady) return
        if (visibility == VISIBLE) requestFrame() else stopMotion()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!motionReady) return
        if (visibility == VISIBLE) requestFrame() else stopMotion()
    }

    private fun canAnimate(): Boolean = motionActive && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

    private fun requestFrame() {
        if (!framePosted && (playing || sparkStartNanos != 0L) && canAnimate()) {
            framePosted = true
            // Thirty bounded draws per second are enough for a soft envelope.
            postDelayed(frame, 33L)
        }
    }

    private fun stopMotion() {
        removeCallbacks(frame)
        framePosted = false
        lastFrameNanos = 0L
        sparkStartNanos = 0L
        envelope = 0f
        average = 0f
        transient = 0f
        invalidate()
    }

    private fun startSparks() {
        sparkStartNanos = System.nanoTime()
        burstNumber++
        repeat(PARTICLE_COUNT) { i ->
            val angle = i * (2.0 * PI / PARTICLE_COUNT) + burstNumber * 0.37
            particleX[i] = cos(angle).toFloat()
            particleY[i] = sin(angle).toFloat()
            particleSpeed[i] = 16f + (i * 7 % 8)
            particleSize[i] = 0.85f + (i % 3) * 0.25f
        }
        requestFrame()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        canvas.save()
        canvas.translate(cx, cy)
        val canvasSize = dp(if (compact) 64f else 112f)
        val fit = minOf(1f, width / canvasSize, height / canvasSize)
        canvas.scale(fit, fit)
        canvas.save()
        canvas.translate(0f, dp(4f))
        canvas.drawCircle(0f, 0f, dp(42f), shadowPaint)
        canvas.restore()
        // No independent sine-wave pulse: silence keeps the halo still.
        val level = (envelope + transient * 0.8f).coerceIn(0f, 1f)
        val glowScale = 0.90f + level * 0.1f
        glowPaint.alpha = if (isEnabled) (112 + level * 143).toInt() else 60
        canvas.save()
        canvas.scale(glowScale, glowScale)
        canvas.drawCircle(0f, 0f, dp(54f), glowPaint)
        canvas.restore()
        drawSparks(canvas)

        canvas.save()
        val discScale = if (isPressed) 0.965f else 1f + level * 0.015f
        canvas.scale(discScale, discScale)
        discPaint.alpha = if (isEnabled) 255 else 110
        canvas.drawCircle(0f, 0f, dp(32f), discPaint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = SpatialStyle.withAlpha(SpatialStyle.card, 85)
        canvas.drawCircle(0f, 0f, dp(31.5f), paint)
        paint.color = SpatialStyle.card
        paint.strokeWidth = dp(2.5f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        if (playing) {
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(-dp(8f), -dp(11f), -dp(3f), dp(11f), dp(2f), dp(2f), paint)
            canvas.drawRoundRect(dp(3f), -dp(11f), dp(8f), dp(11f), dp(2f), dp(2f), paint)
        } else {
            paint.style = Paint.Style.STROKE
            icon.rewind()
            icon.moveTo(-dp(6f), -dp(11f))
            icon.lineTo(dp(11f), 0f)
            icon.lineTo(-dp(6f), dp(11f))
            icon.close()
            canvas.drawPath(icon, paint)
        }
        canvas.restore()
        if (isFocused) {
            paint.color = SpatialStyle.accent
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            canvas.drawCircle(0f, 0f, dp(36f), paint)
        }
        canvas.restore()
    }

    private fun drawSparks(canvas: Canvas) {
        if (sparkStartNanos == 0L) return
        val progress = ((System.nanoTime() - sparkStartNanos).toFloat() / SPARK_DURATION_NANOS).coerceIn(0f, 1f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        repeat(PARTICLE_COUNT) { i ->
            val delay = (i % 4) * 0.025f
            val t = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (progress < delay) return@repeat
            val distance = dp(32f + particleSpeed[i] * (1f - (1f - t) * (1f - t)))
            val opacity = ((1f - t) * (1f - t) * 220).toInt()
            val x = particleX[i] * distance
            val y = particleY[i] * distance
            paint.color = SpatialStyle.withAlpha(glowColor, opacity / 4)
            canvas.drawCircle(x, y, dp(particleSize[i] * 2.3f), paint)
            paint.color = SpatialStyle.withAlpha(glowColor, opacity)
            canvas.drawCircle(x, y, dp(particleSize[i]), paint)
        }
    }

    private companion object {
        const val PARTICLE_COUNT = 16
        const val SPARK_DURATION_NANOS = 1_000_000_000L
    }
}
