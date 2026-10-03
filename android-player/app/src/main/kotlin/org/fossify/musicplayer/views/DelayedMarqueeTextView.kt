/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

/** Start after three seconds; move at 18 dp/s and pause at both ends. */
class DelayedMarqueeTextView(context: Context) : AppCompatTextView(context) {
    private var ready = false
    private var active = false
    private var offset = 0f
    private var direction = 1
    private var lastFrame = 0L
    private var generation = 0
    private val overflow get() = ((layout?.getLineWidth(0) ?: 0f) -
        (width - compoundPaddingLeft - compoundPaddingRight)).coerceAtLeast(0f)
    private val tick = object : Runnable {
        override fun run() {
            if (!canMove()) return
            val now = SystemClock.uptimeMillis()
            val delta = if (lastFrame == 0L) 0f else (now - lastFrame).coerceAtMost(100) / 1000f
            lastFrame = now
            offset = (offset + direction * delta * 18f * resources.displayMetrics.density).coerceIn(0f, overflow)
            invalidate()
            if ((direction > 0 && offset >= overflow) || (direction < 0 && offset <= 0f)) {
                direction = -direction
                lastFrame = 0L
                postDelayed(this, 3000)
            } else postDelayed(this, 33)
        }
    }

    init {
        setSingleLine(true)
        ellipsize = null
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        ready = true
    }

    fun setMotionActive(value: Boolean) {
        if (active == value) return
        active = value
        restart()
    }

    private fun canMove() = active && isAttachedToWindow && isShown && windowVisibility == VISIBLE && overflow > 0f

    private fun restart() {
        // TextView can call overrides during its superclass constructor.
        generation++
        val current = generation
        removeCallbacks(tick)
        offset = 0f; direction = 1; lastFrame = 0L
        invalidate()
        post {
            if (generation == current && canMove()) postDelayed(tick, 3000)
        }
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (ready) restart()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        restart()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
    override fun onDetachedFromWindow() { generation++; removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready) restart()
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipRect(compoundPaddingLeft, 0, width - compoundPaddingRight, height)
        canvas.translate(if (layout?.getParagraphDirection(0) == -1) offset else -offset, 0f)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
