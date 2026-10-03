/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PhotoProcessorTest {
    private fun calibration(mirror: Boolean = false) = PhotoProcessor.Calibration(475.0, 475.0, 100.0, 100.0, 400.0, 100.0, 30.0, mirror)

    @Test fun physicalCropCentersCanalAndMirrorsExactly() {
        val pixels = IntArray(950 * 950)
        for (y in 470..480) for (x in 470..480) pixels[y * 950 + x] = -1
        pixels[475 * 950 + 949] = -1
        val normal = PhotoProcessor.calibratedGray(pixels, 950, 950, calibration())
        val mirrored = PhotoProcessor.calibratedGray(pixels, 950, 950, calibration(true))
        assertTrue(normal[256 * 512 + 256] > .78)
        for (y in 0 until 512) for (x in 0 until 512) assertEquals(normal[y * 512 + x], mirrored[y * 512 + 511 - x], 0.0)
    }

    @Test fun rejectsBlankPhotoAndInvalidCalibrationAndHonorsCancellation() {
        assertThrows(IllegalArgumentException::class.java) { PhotoProcessor.contours(DoubleArray(512 * 512), .15) }
        assertThrows(IllegalArgumentException::class.java) { PhotoProcessor.contours(DoubleArray(512 * 512), Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoProcessor.calibratedGray(IntArray(950 * 950), 950, 950, calibration().copy(canalX = 1000.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoProcessor.calibratedGray(IntArray(950 * 950), 950, 950, calibration().copy(ref2X = 100.0))
        }
        assertThrows(CancellationException::class.java) {
            PhotoProcessor.calibratedGray(IntArray(950 * 950), 950, 950, calibration()) { true }
        }
    }
}
