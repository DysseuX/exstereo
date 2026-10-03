/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import kotlin.math.sqrt

/**
 * PCM amplitude envelope after clipping and latency trimming.
 * AudioTrack/Bluetooth buffering can make the display lead headphone playback.
 */
object PlaybackEnergy {
    // Pack level and timestamp into one volatile word. Stale readings expire before
    // the 49.7-day millisecond wraparound; publishing requires no allocation or lock.
    @Volatile private var measurement = 0L

    fun publish(sumSquares: Double, peak: Float, sampleCount: Int, nowNanos: Long = System.nanoTime()) {
        if (sampleCount <= 0) return
        if (!sumSquares.isFinite() || sumSquares < 0.0 || !peak.isFinite()) {
            clear()
            return
        }
        val rms = sqrt(sumSquares / sampleCount).toFloat().coerceIn(0f, 1f)
        // Compress quiet levels and retain attacks; silence maps to zero.
        val energy = sqrt((rms * 1.8f + peak.coerceIn(0f, 1f) * 0.12f).coerceIn(0f, 1f))
        val millis = (nowNanos / 1_000_000L) and 0xffffffffL
        measurement = (energy.toRawBits().toLong() shl 32) or millis
    }

    fun level(nowNanos: Long = System.nanoTime()): Float {
        val value = measurement
        if (value == 0L) return 0f
        val ageMillis = ((nowNanos / 1_000_000L) - (value and 0xffffffffL)) and 0xffffffffL
        // Expire burst-delivered decoder levels; the view smooths their release.
        if (ageMillis >= 300L) return 0f
        return Float.fromBits((value ushr 32).toInt())
    }

    fun clear() { measurement = 0L }
}
