/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class AcousticProcessorTest {
    private val positions = arrayOf(doubleArrayOf(0.0, 0.0, 1.47), doubleArrayOf(90.0, 0.0, 1.47),
        doubleArrayOf(270.0, 0.0, 1.47), doubleArrayOf(0.0, 90.0, 1.47))
    private fun reference() = AcousticProcessor.Reference(positions, Array(4) { Array(2) { DoubleArray(128).apply { this[0] = 1.0 } } }, 48000)
    private fun flat() = Array(4) { FloatArray(257) }

    @Test fun headSizeDelaysFarEarWithoutCircularWraparound() {
        val result = AcousticProcessor.synthesize(reference(), flat(), flat(), 18.0, 20.0)
        val expected = AcousticProcessor.analyticalItd(positions, 18.0, 20.0)
        result.forEachIndexed { i, ears ->
            val peaks = ears.map { ear -> ear.indices.maxBy { abs(ear[it]) } }
            assertEquals(expected[i] * 48000, (peaks[1] - peaks[0]).toDouble(), 1.0)
            assertTrue(ears.all { it.takeLast(64).all { abs(it) < 1e-10 } })
        }
        assertTrue(expected[1] > 0); assertTrue(expected[2] < 0)
    }

    @Test fun oneGlobalGainPreservesRelativeEarLevels() {
        val base = reference().ir.take(1).toTypedArray()
        base[0][0][0] = .5
        val result = AcousticProcessor.synthesize(AcousticProcessor.Reference(positions.take(1).toTypedArray(), base, 48000),
            arrayOf(FloatArray(257)), arrayOf(FloatArray(257) { 6.0205999f }), 18.0, 20.0)
        for (i in result[0][0].indices) assertEquals(result[0][0][i] * 4.0, result[0][1][i].toDouble(), 1e-7)
        assertEquals(.95, result[0][1].maxOf { abs(it) }.toDouble(), 1e-7)
    }

    @Test fun fftRoundTripAndMinimumPhaseRetainMagnitude() {
        val original = DoubleArray(2048) { sin(it * .017) * exp(-it / 120.0) }
        val real = original.copyOf(); val imag = DoubleArray(2048)
        AcousticProcessor.fft(real, imag, false)
        val magnitude = DoubleArray(1025) { hypot(real[it], imag[it]) }
        AcousticProcessor.fft(real, imag, true)
        assertArrayEquals(original, real, 1e-11)
        val minimum = AcousticProcessor.minimumPhase(magnitude, 2048)
        imag.fill(0.0)
        AcousticProcessor.fft(minimum, imag, false)
        magnitude.forEachIndexed { i, value -> assertEquals(value, hypot(minimum[i], imag[i]), 1e-9) }
    }

    @Test fun maximumSupportedHeadFitsTemplateAndCancellationStopsBeforeProgress() {
        AcousticProcessor.synthesize(reference(), flat(), flat(), 30.0, 35.0)
        var progressCalls = 0
        try {
            AcousticProcessor.synthesize(reference(), flat(), flat(), 18.0, 20.0, { true }) { _, _ -> progressCalls++ }
            fail("Cancellation must stop synthesis")
        } catch (_: CancellationException) { assertEquals(0, progressCalls) }
    }

    @Test fun rejectsInvalidDimensionsNonfinitePredictionAndMalformedReference() {
        assertThrows(IllegalArgumentException::class.java) { AcousticProcessor.synthesize(reference(), flat(), flat(), 7.0, 20.0) }
        val bad = flat().apply { this[0][0] = Float.NaN }
        assertThrows(IllegalArgumentException::class.java) { AcousticProcessor.synthesize(reference(), bad, flat(), 18.0, 20.0) }
        assertThrows(IllegalArgumentException::class.java) { AcousticProcessor.readReference(ByteArray(24)) }
    }
}
