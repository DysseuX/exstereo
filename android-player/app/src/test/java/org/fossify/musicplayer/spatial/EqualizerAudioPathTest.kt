/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.musicplayer.playback.SimpleEqualizer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the real host JNI, Engine and AudioProcessor PCM path, not a DSP shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class)
class EqualizerAudioPathTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun initialize() {
        context.getSharedPrefs().edit().clear().commit()
        SimpleEqualizer.release()
        SimpleEqualizer.initialize(context)
        SpatialPreferences.initialize(context)
    }

    private fun render(processor: SpatialAudioProcessor, frequency: Double, channels: Int = 2,
                       beforeInput: (Int) -> Unit = {}): ShortArray {
        val result = ArrayList<Short>()
        processor.configure(AudioFormat(48000, channels, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        fun drain() {
            val output = processor.output.order(ByteOrder.nativeOrder())
            while (output.hasRemaining()) result += output.short
        }
        var position = 0
        while (position < 48000) {
            beforeInput(position)
            val frames = minOf(997, 48000 - position)
            val input = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
            repeat(frames) { frame ->
                val sample = (sin(2 * PI * frequency * (position + frame) / 48000) * 3000).toInt().toShort()
                repeat(channels) { channel -> input.putShort(if (channel < 2) sample else 0.toShort()) }
            }
            input.flip()
            processor.queueInput(input)
            assertFalse(input.hasRemaining())
            drain()
            position += frames
        }
        processor.queueEndOfStream()
        drain()
        assertTrue(processor.isEnded)
        assertEquals(96000, result.size)
        return result.toShortArray()
    }

    private fun rms(samples: ShortArray, firstFrame: Int = samples.size / 4,
                    lastFrame: Int = samples.size / 2): Double {
        var squares = 0.0
        for (index in firstFrame * 2 until lastFrame * 2) squares += samples[index].toDouble() * samples[index]
        return sqrt(squares / ((lastFrame - firstFrame) * 2))
    }

    @Test fun settingsReachNativeEqInStereoWideningAndSevenPointOneAndSurviveFlush() {
        val previous = SpatialPreferences.current
        try {
            for (mode in 0..2) {
                SpatialPreferences.update(SpatialSettings(mode = mode, virtualStereo = false))
                val processor = SpatialAudioProcessor(context)
                try {
                    SimpleEqualizer.resetToFlat(context)
                    val flat = render(processor, 1000.0)
                    SimpleEqualizer.setBand(context, 16, -600)
                    val cut = render(processor, 1000.0)
                    val difference = 20 * log10(rms(cut) / rms(flat))
                    assertEquals("mode=$mode", -6.0, difference, .04)
                    SimpleEqualizer.resetToFlat(context)
                    assertArrayEquals(flat, render(processor, 1000.0))
                } finally { processor.reset() }
            }
        } finally {
            SpatialPreferences.update(previous)
            SimpleEqualizer.resetToFlat(context)
        }
    }

    @Test fun thirtiethBandIsPassedThroughJniAndChangesTheAudiblePcm() {
        val previous = SpatialPreferences.current
        val processor = SpatialAudioProcessor(context)
        try {
            SpatialPreferences.update(SpatialSettings(mode = 0, virtualStereo = false))
            SimpleEqualizer.resetToFlat(context)
            val flat = render(processor, 20000.0)
            SimpleEqualizer.setBand(context, 29, -600)
            val cut = render(processor, 20000.0)
            assertEquals(-6.0, 20 * log10(rms(cut) / rms(flat)), .04)
        } finally {
            processor.reset()
            SpatialPreferences.update(previous)
            SimpleEqualizer.resetToFlat(context)
        }
    }

    @Test fun nativeEightChannelMusicReceivesEqAfterBinauralRendering() {
        val previous = SpatialPreferences.current
        val processor = SpatialAudioProcessor(context)
        try {
            SpatialPreferences.update(SpatialSettings(mode = 2, virtualStereo = false))
            SimpleEqualizer.resetToFlat(context)
            val flat = render(processor, 1000.0, channels = 8)
            SimpleEqualizer.setBand(context, 16, -600)
            val cut = render(processor, 1000.0, channels = 8)
            assertEquals(-6.0, 20 * log10(rms(cut) / rms(flat)), .04)
        } finally {
            processor.reset()
            SpatialPreferences.update(previous)
            SimpleEqualizer.resetToFlat(context)
        }
    }

    @Test fun changingSavedCurveDuringPlaybackReachesPcmWithoutFlushOrNewAudioSession() {
        val previous = SpatialPreferences.current
        val processor = SpatialAudioProcessor(context)
        try {
            SpatialPreferences.update(SpatialSettings(mode = 0, virtualStereo = false))
            SimpleEqualizer.resetToFlat(context)
            var updated = false
            val output = render(processor, 1000.0) { position ->
                if (!updated && position >= 24000) {
                    SimpleEqualizer.setBand(context, 16, -600)
                    updated = true
                }
            }
            assertTrue(updated)
            val early = rms(output, 8000, 15000)
            val late = rms(output, 38000, 46000)
            assertEquals(-6.0, 20 * log10(late / early), .04)
        } finally {
            processor.reset()
            SpatialPreferences.update(previous)
            SimpleEqualizer.resetToFlat(context)
        }
    }
}
