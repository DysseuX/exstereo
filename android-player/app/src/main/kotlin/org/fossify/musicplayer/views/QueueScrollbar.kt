/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.fossify.musicplayer.R

/** 10 dp rail, 24 dp round thumb, 44 dp grab target; shares the actual list offset. */
class QueueScrollbar(context: Context, private val list: RecyclerView) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false
    private var grabOffset = 0f
    private val radius get() = 12f * density
    private val travel get() = (height - 2 * radius).coerceAtLeast(1f)
    private val maximum get() = (list.computeVerticalScrollRange() - list.computeVerticalScrollExtent()).coerceAtLeast(0)
    private val thumbY get() = radius + travel * if (maximum > 0) list.computeVerticalScrollOffset().toFloat() / maximum else 0f

    init {
        contentDescription = context.getString(R.string.player_queue_scroll)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // RecyclerView exposes scroll actions.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { invalidate() }
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maximum <= 0 || height <= 2 * radius) return
        val center = width - radius
        paint.color = SpatialStyle.line
        canvas.drawRoundRect(center - 5 * density, radius, center + 5 * density,
            height - radius, 5 * density, 5 * density, paint)
        paint.color = SpatialStyle.accent
        canvas.drawCircle(center, thumbY.coerceIn(radius, height - radius), radius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (maximum <= 0 || height <= 2 * radius) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                grabOffset = if (kotlin.math.abs(event.y - thumbY) <= radius) event.y - thumbY else 0f
                list.stopScroll()
                parent?.requestDisallowInterceptTouchEvent(true)
                seek(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { seek(event.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                if (event.actionMasked == MotionEvent.ACTION_UP) { seek(event.y); performClick() }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun seek(y: Float) {
        val target = (((y - grabOffset - radius) / travel).coerceIn(0f, 1f) * maximum).toInt()
        list.scrollBy(0, target - list.computeVerticalScrollOffset())
        invalidate()
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
