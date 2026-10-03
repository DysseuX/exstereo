/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only */

package org.fossify.musicplayer.spatial

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/** Exercises the packaged JNI configuration entrypoint and per-backend EOS. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpatialBackendTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(settings: SpatialSettings, input: ShortArray, channels: Int): ShortArray {
        SpatialPreferences.initialize(context)
        val previous = SpatialPreferences.current
        val processor = SpatialAudioProcessor(context)
        val bytes = ByteArrayOutputStream()
        fun drain() {
            val buffer = processor.output
            val block = ByteArray(buffer.remaining())
            buffer.get(block)
            bytes.write(block)
        }
        try {
            SpatialPreferences.update(settings)
            processor.configure(AudioFormat(48000, channels, C.ENCODING_PCM_16BIT))
            processor.flush()
            var offset = 0
            var chunk = 0
            val chunks = intArrayOf(1, 31, 127, 509)
            while (offset < input.size) {
                val samples = minOf(chunks[chunk++ % chunks.size] * channels, input.size - offset)
                val buffer = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder())
                repeat(samples) { buffer.putShort(input[offset++]) }
                buffer.flip()
                while (buffer.hasRemaining()) { processor.queueInput(buffer); drain() }
            }
            processor.queueEndOfStream()
            drain()
            assertTrue(processor.isEnded)
            val output = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.nativeOrder())
            return ShortArray(output.remaining() / 2) { output.short }
        } finally {
            processor.reset()
            SpatialPreferences.update(previous)
        }
    }

    @Test fun allNineCombinationsKeepBypassSamplesAndShortEos() {
        for (renderer in 0..2) for (upmixer in 0..2) for (frames in listOf(1, 4099)) {
            val settings = SpatialSettings(renderer = renderer, upmixer = upmixer, mode = 0, virtualStereo = false)
            val input = ShortArray(frames * 2) { (sin(it * 0.043) * 5000).toInt().toShort() }
            val output = render(settings, input, 2)
            assertEquals("$renderer/$upmixer duration", input.size, output.size)
            for (i in input.indices)
                assertTrue("$renderer/$upmixer bypass sample $i", abs(input[i].toInt() - output[i]) <= 1)
        }
    }

    @Test fun allNineCombinationsProduceSpatialStereo() {
        val frames = 8197
        for (renderer in 0..2) for (upmixer in 0..2) {
            val input = ShortArray(frames * 2) { i ->
                val frequency = if (i % 2 == 0) 0.08 else 0.115
                (sin((i / 2) * frequency) * 3000).toInt().toShort()
            }
            val output = render(SpatialSettings(renderer = renderer, upmixer = upmixer), input, 2)
            assertEquals(frames * 2, output.size)
            assertTrue("$renderer/$upmixer silence", output.any { abs(it.toInt()) > 5 })
            assertTrue("$renderer/$upmixer clipped", output.all { abs(it.toInt()) < 32767 })
        }
    }

    @Test fun openAlDoesNotReadSavedSofaAndSofalizerSelectsKemar() {
        // A stored SOFA may be unavailable: OpenAL must exclusively use its
        // builtin profile and preserve this saved selection for other engines.
        val saved = "user_" + "a".repeat(64)
        val settings = SpatialSettings(renderer = SpatialSettings.RENDERER_OPENAL, hrtf = saved)
        var handle = 0L
        try {
            handle = NativeSpatialEngine.createForSettings(48000, context, settings)
            assertTrue(handle != 0L)
            assertEquals(saved, settings.hrtf)
        } finally {
            if (handle != 0L) NativeSpatialEngine.release(handle)
        }
        assertEquals("kemar", SpatialSettings(renderer = SpatialSettings.RENDERER_SOFALIZER).withCompatibleHrtf().hrtf)
        assertEquals("default", SpatialSettings().withCompatibleHrtf().hrtf)
    }
}
