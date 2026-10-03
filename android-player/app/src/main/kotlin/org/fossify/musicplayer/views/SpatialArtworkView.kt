/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.appcompat.widget.AppCompatImageView

/** Rounded cover clipping compatible with software canvases. */
class SpatialArtworkView(context: Context, private val radiusDp: Float = 24f) : AppCompatImageView(context) {
    private val outline = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = radiusDp * resources.displayMetrics.density
        outline.rewind()
        outline.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipPath(outline)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
