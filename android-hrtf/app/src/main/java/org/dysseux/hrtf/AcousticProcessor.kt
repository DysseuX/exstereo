/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.res.AssetManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.json.JSONObject
import kotlin.math.*

/** Experimental ITSADIVE/VIKING hybrid: minimum phase plus analytical head-size ITD. */
object AcousticProcessor {
    const val OUTPUT_SAMPLES = 2200
    data class Reference(val positions: Array<DoubleArray>, val ir: Array<Array<DoubleArray>>, val sampleRate: Int)

    fun load(assets: AssetManager): Reference {
        val manifest = assets.open("reference_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val bytes = assets.open("reference.bin").use { it.readBytes() }
        requireUi(sha256(bytes) == manifest.getString("reference_sha256"),R.string.error_reference_corrupt)
        return readReference(bytes).also {
            require(it.positions.size == manifest.getInt("directions") && it.ir[0][0].size == manifest.getInt("reference_samples"))
            require(it.sampleRate == manifest.getInt("sample_rate"))
        }
    }

    internal fun readReference(bytes: ByteArray): Reference {
        requireUi(bytes.size >= 24 && String(bytes, 0, 8, Charsets.US_ASCII) == "HRTFREF1",R.string.error_reference_format)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }
        val m = data.int; val ears = data.int; val samples = data.int; val rate = data.int
        require(m in 1..2000 && ears == 2 && samples in 2..2048 && rate in 8000..96000)
        require(bytes.size.toLong() == 24L + m * (3L + ears * samples) * 8)
        val positions = Array(m) { DoubleArray(3) { data.double } }
        val ir = Array(m) { Array(2) { DoubleArray(samples) { data.double } } }
        validateReference(Reference(positions, ir, rate))
        return Reference(positions, ir, rate)
    }

    fun synthesize(reference: Reference, left: Array<FloatArray>, right: Array<FloatArray>,
                   headWidthCm: Double, headDepthCm: Double, cancelled: () -> Boolean = { false },
                   progress: (Int, Int) -> Unit = { _, _ -> }): Array<Array<FloatArray>> {
        validateReference(reference)
        validateHead(headWidthCm, headDepthCm)
        val m = reference.positions.size
        require(left.size == m && right.size == m)
        val frequencies = left.first().size
        requireUi(frequencies >= 2 && left.all { it.size == frequencies && it.all(Float::isFinite) } &&
            right.all { it.size == frequencies && it.all(Float::isFinite) },R.string.error_model_output)
        var nfft = 2048
        while (nfft < 2 * (reference.ir[0][0].size + 2 * (frequencies - 1))) nfft *= 2
        val itd = analyticalItd(reference.positions, headWidthCm, headDepthCm)
        val maximumDelay = ceil(itd.maxOf { abs(it) } * reference.sampleRate).toInt()
        requireUi(nfft + 30 + maximumDelay <= OUTPUT_SAMPLES,R.string.error_reference_size)
        val result = Array(m) { Array(2) { FloatArray(OUTPUT_SAMPLES) } }
        var peak = 0.0
        for (direction in 0 until m) {
            checkCancelled(cancelled)
            for (ear in 0..1) {
                val real = DoubleArray(nfft); val imaginary = DoubleArray(nfft)
                reference.ir[direction][ear].copyInto(real)
                fft(real, imaginary, inverse = false)
                val prediction = if (ear == 0) left[direction] else right[direction]
                val magnitude = DoubleArray(nfft / 2 + 1) { bin ->
                    val source = bin.toDouble() / (nfft / 2) * (frequencies - 1)
                    val low = floor(source).toInt(); val high = min(low + 1, frequencies - 1); val fraction = source - low
                    val db = prediction[low].coerceIn(-80f, 30f) * (1 - fraction) + prediction[high].coerceIn(-80f, 30f) * fraction
                    hypot(real[bin], imaginary[bin]) * 10.0.pow(db / 20)
                }
                val impulse = minimumPhase(magnitude, nfft)
                val delay = max((if (ear == 0) -itd[direction] else itd[direction]) * reference.sampleRate, 0.0)
                val delayed = fractionalDelay(impulse, delay, OUTPUT_SAMPLES)
                for (i in delayed.indices) {
                    requireUi(delayed[i].isFinite(),R.string.error_synthesis_nonfinite)
                    peak = max(peak, abs(delayed[i]))
                    result[direction][ear][i] = delayed[i].toFloat()
                }
            }
            progress(direction + 1, m)
        }
        requireUi(peak > 1e-12,R.string.error_synthesis_silent)
        val gain = .95 / peak
        for (direction in result) {
            checkCancelled(cancelled)
            for (ear in direction) for (i in ear.indices) ear[i] = (ear[i] * gain).toFloat()
        }
        return result
    }

    internal fun analyticalItd(positions: Array<DoubleArray>, width: Double, depth: Double): DoubleArray {
        validateHead(width, depth)
        val radius = sqrt(width * depth) / 200
        return DoubleArray(positions.size) { i ->
            val lateral = asin((sin(Math.toRadians(positions[i][0])) * cos(Math.toRadians(positions[i][1]))).coerceIn(-1.0, 1.0))
            radius / 343 * (lateral + sin(lateral))
        }
    }

    internal fun minimumPhase(magnitude: DoubleArray, nfft: Int): DoubleArray {
        val real = DoubleArray(nfft); val imag = DoubleArray(nfft)
        for (i in magnitude.indices) real[i] = ln(max(magnitude[i], 1e-10))
        for (i in 1 until nfft / 2) real[nfft - i] = real[i]
        fft(real, imag, inverse = true)
        for (i in 1 until nfft / 2) real[i] *= 2
        for (i in nfft / 2 + 1 until nfft) real[i] = 0.0
        imag.fill(0.0)
        fft(real, imag, inverse = false)
        for (i in real.indices) {
            val amplitude = exp(real[i]); val phase = imag[i]
            real[i] = amplitude * cos(phase); imag[i] = amplitude * sin(phase)
        }
        fft(real, imag, inverse = true)
        return real
    }

    internal fun fractionalDelay(ir: DoubleArray, delay: Double, outputSize: Int): DoubleArray {
        require(delay.isFinite() && delay >= 0)
        val integer = floor(delay).toInt(); val fraction = delay - integer
        require(integer + ir.size + 30 <= outputSize)
        val kernel = DoubleArray(31) { i ->
            val x = i - 15 - fraction
            val sinc = if (abs(x) < 1e-15) 1.0 else sin(PI * x) / (PI * x)
            sinc * besselI0(8 * sqrt(max(0.0, 1 - ((i - 15) / 15.0).pow(2)))) / besselI0(8.0)
        }
        val sum = kernel.sum(); for (i in kernel.indices) kernel[i] /= sum
        val result = DoubleArray(outputSize)
        for (i in ir.indices) for (k in kernel.indices) result[integer + i + k] += ir[i] * kernel[k]
        return result
    }

    private fun besselI0(x: Double): Double {
        var term = 1.0; var sum = 1.0
        for (k in 1..100) { term *= x * x / (4.0 * k * k); sum += term; if (term < sum * 1e-16) break }
        return sum
    }

    internal fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        require(n >= 2 && n and (n - 1) == 0 && imag.size == n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        var size = 2
        while (size <= n) {
            val angle = (if (inverse) 2 else -2) * PI / size
            val stepR = cos(angle); val stepI = sin(angle)
            for (offset in 0 until n step size) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until size / 2) {
                    val a = offset + k; val b = a + size / 2
                    val r = real[b] * wr - imag[b] * wi; val im = real[b] * wi + imag[b] * wr
                    real[b] = real[a] - r; imag[b] = imag[a] - im
                    real[a] += r; imag[a] += im
                    val nextR = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = nextR
                }
            }
            size *= 2
        }
        if (inverse) for (i in 0 until n) { real[i] /= n; imag[i] /= n }
    }

    private fun validateReference(reference: Reference) {
        require(reference.sampleRate in 8000..96000 && reference.positions.isNotEmpty())
        require(reference.positions.all { it.size == 3 && it.all(Double::isFinite) && abs(it[1]) <= 90.000001 && it[2] > 0 })
        val samples = reference.ir.firstOrNull()?.firstOrNull()?.size ?: 0
        require(samples >= 2 && reference.ir.size == reference.positions.size)
        require(reference.ir.all { ears -> ears.size == 2 && ears.all { ir -> ir.size == samples && ir.all(Double::isFinite) && ir.any { it != 0.0 } } })
    }

    internal fun validateHead(width: Double, depth: Double) {
        requireUi(width.isFinite() && depth.isFinite() && width in 8.0..30.0 && depth in 8.0..35.0,R.string.head_range)
    }

    internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException() }
}
