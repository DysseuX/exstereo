/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

/** A broad seek target that owns its gesture before the surrounding pager can intercept it. */
class SpatialSeekBar(context: Context, accent: Int, rail: Int) : AppCompatSeekBar(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        progressTintList = null
        thumbTintList = null
        val background = GradientDrawable().apply {
            setColor(rail)
            cornerRadius = dp(5).toFloat()
        }
        val progress = ClipDrawable(GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(5).toFloat()
        }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(background, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            repeat(2) {
                setLayerHeight(it, dp(10))
                setLayerGravity(it, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            }
        }
        val grip = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(SpatialStyle.card)
            setStroke(dp(2), accent)
            setSize(dp(48), dp(48))
        }
        val center = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setSize(dp(12), dp(12))
        }
        thumb = LayerDrawable(arrayOf(grip, center)).apply {
            setLayerSize(1, dp(12), dp(12))
            setLayerGravity(1, Gravity.CENTER)
        }
        thumbOffset = dp(24)
        splitTrack = false
        minimumHeight = dp(48)
        setPadding(dp(24), 0, dp(24), 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}
