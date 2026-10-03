/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class)
class SpatialInputResamplerTest {
    @Test fun highResolutionBecomesAudible48kWithoutChangingDuration() {
        val resampler = SpatialInputResampler()
        for (rate in listOf(88200,96000,176400,192000)) {
            assertEquals(48000, resampler.configure(AudioFormat(rate,2,C.ENCODING_PCM_16BIT)).sampleRate)
            if (rate == 88200) resampler.flush()
            else resampler.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
            var bytes = 0
            var peak = 0
            fun drain() {
                val output = resampler.output.order(ByteOrder.nativeOrder())
                bytes += output.remaining()
                while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.short.toInt()))
            }
            var position = 0
            val frames = rate / 10
            while (position < frames) {
                val count = minOf(997, frames-position)
                val input = ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder())
                repeat(count) { frame ->
                    val sample = (3000*sin(2*PI*440*(position+frame)/rate)).toInt().toShort()
                    input.putShort(sample).putShort(sample)
                }
                input.flip()
                resampler.queueInput(input)
                assertFalse(input.hasRemaining())
                drain()
                position += count
            }
            resampler.queueEndOfStream()
            drain()
            assertTrue(resampler.isEnded)
            // Sonic rounds the final fractional resampling position to a frame.
            assertEquals(0, bytes % 4)
            assertTrue("$rate Hz: expected 4800 frames, got ${bytes/4}", kotlin.math.abs(bytes/4 - 4800) <= 1)
            assertTrue(peak > 1000)
            assertEquals((bytes/4).toLong()*1000000/48000,
                resampler.getDurationAfterProcessorApplied(100000L))
        }
        resampler.configure(AudioFormat(44100,2,C.ENCODING_PCM_16BIT))
        assertFalse(resampler.isActive)
        resampler.reset()
    }
}
