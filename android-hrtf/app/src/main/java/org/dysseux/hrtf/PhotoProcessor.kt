/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.graphics.Bitmap
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Calibrated crop and Canny contours matching the desktop pipeline. */
object PhotoProcessor {
    data class Calibration(
        val canalX: Double, val canalY: Double,
        val ref1X: Double, val ref1Y: Double, val ref2X: Double, val ref2Y: Double,
        val referenceMm: Double, val mirror: Boolean = false, val threshold: Double = 0.15
    )

    fun prepare(bitmap: Bitmap, calibration: Calibration, cancelled: () -> Boolean = { false }): Bitmap {
        requireUi(!bitmap.isRecycled,R.string.error_photo_closed)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = calibratedGray(pixels, bitmap.width, bitmap.height, calibration, cancelled)
        val values = contours(gray, calibration.threshold, cancelled)
        val output = IntArray(256 * 256) { i ->
            val value = values[i]
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(output, 256, 256, Bitmap.Config.ARGB_8888)
    }

    internal fun calibratedGray(pixels: IntArray, width: Int, height: Int, c: Calibration,
                                cancelled: () -> Boolean = { false }): DoubleArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        for ((x, y) in listOf(c.canalX to c.canalY, c.ref1X to c.ref1Y, c.ref2X to c.ref2Y)) {
            requireUi(x.isFinite() && y.isFinite() && x >= 0 && x < width && y >= 0 && y < height,R.string.error_point_outside)
        }
        val distance = hypot(c.ref2X - c.ref1X, c.ref2Y - c.ref1Y)
        requireUi(c.referenceMm.isFinite() && c.referenceMm in 1.0..200.0 && distance >= 5,R.string.error_ruler_scale)
        val side = 95 * distance / c.referenceMm
        requireUi(side >= 128 && side <= 4 * max(width, height),R.string.error_ear_scale)
        val result = DoubleArray(512 * 512)
        for (y in 0 until 512) {
            checkCancelled(cancelled)
            val sy = c.canalY - side / 2 + (y + .5) * side / 512
            for (x in 0 until 512) {
                val sx = c.canalX - side / 2 + ((if (c.mirror) 511 - x else x) + .5) * side / 512
                if (sx < 0 || sx >= width || sy < 0 || sy >= height) continue
                val px = sx - .5; val py = sy - .5
                val ix = floor(px).toInt(); val iy = floor(py).toInt()
                var red = 0.0; var green = 0.0; var blue = 0.0
                for (dy in -1..2) for (dx in -1..2) {
                    val weight = cubic(px - ix - dx) * cubic(py - iy - dy)
                    val rgb = pixels[(iy + dy).coerceIn(0, height - 1) * width + (ix + dx).coerceIn(0, width - 1)]
                    red += ((rgb ushr 16) and 255) * weight
                    green += ((rgb ushr 8) and 255) * weight
                    blue += (rgb and 255) * weight
                }
                // Pillow's RGB -> L fixed-point coefficients, after byte-valued crop sampling.
                val r = red.toInt().coerceIn(0, 255); val g = green.toInt().coerceIn(0, 255); val b = blue.toInt().coerceIn(0, 255)
                result[y * 512 + x] = ((19595 * r + 38470 * g + 7471 * b + 32768) shr 16) / 255.0
            }
        }
        return result
    }

    internal fun contours(gray: DoubleArray, threshold: Double, cancelled: () -> Boolean = { false }): IntArray {
        require(gray.size == 512 * 512 && gray.all { it.isFinite() })
        requireUi(threshold.isFinite() && threshold in .02..0.9,R.string.error_threshold)
        val sigma = 1.2
        val kernel = DoubleArray(11) { exp(-.5 * ((it - 5) / sigma).pow(2)) }
        val sum = kernel.sum(); for (i in kernel.indices) kernel[i] /= sum
        val vertical = DoubleArray(gray.size); val smooth = DoubleArray(gray.size)
        for (y in 0 until 512) {
            checkCancelled(cancelled)
            for (x in 0 until 512) {
                var value = 0.0
                for (k in -5..5) value += gray[reflect(y + k, 512) * 512 + x] * kernel[k + 5]
                vertical[y * 512 + x] = value
            }
        }
        for (y in 0 until 512) for (x in 0 until 512) {
            var value = 0.0
            for (k in -5..5) value += vertical[y * 512 + reflect(x + k, 512)] * kernel[k + 5]
            smooth[y * 512 + x] = value
        }
        val magnitude = DoubleArray(gray.size); val directions = IntArray(gray.size)
        for (y in 0 until 512) {
            checkCancelled(cancelled)
            for (x in 0 until 512) {
                var gx = 0.0; var gy = 0.0
                for (offset in -1..1) {
                    val weight = if (offset == 0) 2.0 else 1.0
                    gx += weight * (smooth[reflect(y + offset, 512) * 512 + reflect(x + 1, 512)] -
                        smooth[reflect(y + offset, 512) * 512 + reflect(x - 1, 512)])
                    gy += weight * (smooth[reflect(y + 1, 512) * 512 + reflect(x + offset, 512)] -
                        smooth[reflect(y - 1, 512) * 512 + reflect(x + offset, 512)])
                }
                val i = y * 512 + x
                magnitude[i] = hypot(gx, gy)
                directions[i] = ((Math.rint(atan2(gy, gx) / (PI / 4)).toInt() % 4) + 4) % 4
            }
        }
        val ordered = magnitude.copyOf().apply { sort() }
        val p = (ordered.size - 1) * .995; val lower = floor(p).toInt()
        val scale = ordered[lower] * (1 - p + lower) + ordered[lower + 1] * (p - lower)
        requireUi(scale >= 1e-5,R.string.error_no_contours)
        val strong = max(scale * threshold, 1e-5); val weak = max(scale * threshold * .4, 1e-6)
        val eligible = BooleanArray(gray.size); val edges = BooleanArray(gray.size)
        val queue = IntArray(gray.size); var tail = 0; var head = 0
        val offsets = intArrayOf(1, 513, 512, 511)
        for (y in 3 until 509) for (x in 3 until 509) {
            val i = y * 512 + x; val m = magnitude[i]; val offset = offsets[directions[i]]
            if (m >= magnitude[i - offset] && m >= magnitude[i + offset] && m >= weak) {
                eligible[i] = true
                if (m >= strong) { edges[i] = true; queue[tail++] = i }
            }
        }
        while (head < tail) {
            if (head % 4096 == 0) checkCancelled(cancelled)
            val i = queue[head++]
            for (dy in -1..1) for (dx in -1..1) {
                val next = i + dy * 512 + dx
                if (eligible[next] && !edges[next]) { edges[next] = true; queue[tail++] = next }
            }
        }
        requireUi(tail >= 30,R.string.error_few_contours)
        return lanczosHalf(edges, cancelled)
    }

    // Separable Lanczos-3 with antialias support scaled by 2, as Pillow resize(256, LANCZOS).
    private fun lanczosHalf(edges: BooleanArray, cancelled: () -> Boolean): IntArray {
        val temporary = IntArray(256 * 512); val output = IntArray(256 * 256)
        val weights = Array(256) { dst ->
            val center = (dst + .5) * 2
            val start = max(0, (center - 6 + .5).toInt())
            val end = min(512, (center + 6 + .5).toInt())
            val values = DoubleArray(end - start) { k -> lanczos((start + k - center + .5) / 2) }
            val sum = values.sum(); for (k in values.indices) values[k] /= sum
            start to values
        }
        for (y in 0 until 512) {
            checkCancelled(cancelled)
            for (x in 0 until 256) {
                val (start, coefficients) = weights[x]
                var value = 0.0
                coefficients.forEachIndexed { k, weight -> if (edges[y * 512 + start + k]) value += 255 * weight }
                temporary[y * 256 + x] = floor(value + .5).toInt().coerceIn(0, 255)
            }
        }
        for (y in 0 until 256) for (x in 0 until 256) {
            val (start, coefficients) = weights[y]
            var value = 0.0
            coefficients.forEachIndexed { k, weight -> value += temporary[(start + k) * 256 + x] * weight }
            output[y * 256 + x] = floor(value + .5).toInt().coerceIn(0, 255)
        }
        return output
    }

    private fun reflect(index: Int, size: Int) = if (index < 0) -index - 1 else if (index >= size) 2 * size - index - 1 else index
    private fun cubic(value: Double): Double {
        val x = abs(value)
        return if (x < 1) ((1.5 * x - 2.5) * x) * x + 1 else if (x < 2) ((-.5 * x + 2.5) * x - 4) * x + 2 else 0.0
    }
    private fun lanczos(x: Double) = if (x == 0.0) 1.0 else if (abs(x) >= 3) 0.0 else sin(PI * x) * sin(PI * x / 3) / (PI * PI * x * x / 3)
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException() }
}
