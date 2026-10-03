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

/** JNI, delay compensation and bundled HRTFs in the actual Android process. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpatialProcessorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(input: ShortArray, channels: Int, settings: SpatialSettings): ShortArray {
        SpatialPreferences.initialize(context)
        val originalSettings = SpatialPreferences.current
        SpatialPreferences.update(settings)
        val processor = SpatialAudioProcessor(context)
        val bytes = ByteArrayOutputStream()
        fun drain() {
            val buffer = processor.output
            val block = ByteArray(buffer.remaining())
            buffer.get(block)
            bytes.write(block)
        }
        try {
            assertEquals(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT),
                processor.configure(AudioFormat(48000, channels, C.ENCODING_PCM_16BIT)))
            processor.flush()
            val chunks = intArrayOf(1, 37, 255, 1201, 4096, 19)
            var position = 0
            var chunk = 0
            while (position < input.size) {
                val count = minOf(chunks[chunk++ % chunks.size] * channels, input.size - position)
                val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
                for (i in position until position + count) buffer.putShort(input[i])
                buffer.flip()
                while (buffer.hasRemaining()) { processor.queueInput(buffer); drain() }
                position += count
            }
            processor.queueEndOfStream()
            drain()
            assertTrue("Processor did not finish after EOS", processor.isEnded)
            val buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.nativeOrder())
            return ShortArray(buffer.remaining() / 2) { buffer.short }
        } finally {
            processor.reset()
            SpatialPreferences.update(originalSettings)
        }
    }

    @Test fun bypassPreservesLengthAndSamplesIncludingShortTracks() {
        for (frames in listOf(1, 511, 1537, 7003)) {
            val source = ShortArray(frames * 2) { (sin(it * 0.037) * 8000).toInt().toShort() }
            val result = render(source, 2, SpatialSettings(mode = 0, virtualStereo = false))
            assertEquals(source.size, result.size)
            for (i in source.indices) assertTrue("Bypass changed sample $i", abs(result[i].toInt() - source[i]) <= 1)
        }
    }

    @Test fun fullWidthRetainsMonoCenter() {
        val source = ShortArray(7003) { (sin(it * 0.05) * 6000).toInt().toShort() }
        val result = render(source, 1, SpatialSettings(mode = 1, width = 2f, virtualStereo = false))
        assertEquals(source.size * 2, result.size)
        for (i in source.indices) {
            assertTrue(abs(result[2 * i].toInt() - source[i]) <= 1)
            assertEquals(result[2 * i], result[2 * i + 1])
        }
    }

    @Test fun allBundledProfilesRenderStereoAndEightChannelMusic() {
        for (profile in HrtfProfiles.entries) {
            for (channels in listOf(2, 8)) {
                val input = ShortArray(8197 * channels) { i ->
                    val frame = i / channels
                    if (i % channels == 0) (sin(frame * 0.075) * 6000).toInt().toShort() else 0
                }
                val output = render(input, channels, SpatialSettings(mode = 2, hrtf = profile.id))
                assertEquals("${profile.id} changed duration", 8197 * 2, output.size)
                assertTrue("${profile.id} produced silence", output.any { abs(it.toInt()) > 10 })
                assertTrue("${profile.id} clipped", output.all { abs(it.toInt()) < 32767 })
            }
        }
    }

    @Test fun liveSettingsJniCarriesStereoPositionsAndManualCalibration() {
        val source = ShortArray(7003 * 2) { (sin(it * 0.037) * 4000).toInt().toShort() }
        for (renderer in 0..2) {
            // Full right/left pan with HRTF mix zero gives a known reference
            // through the real new JNI snapshot, backend buffering and EOS.
            val settings = SpatialSettings(mode = 0, renderer = renderer, virtualStereo = true,
                stereoSpeakers = listOf(SpeakerPosition(-90f), SpeakerPosition(90f)),
                hrtfBlend = 0f, hrtfGainDb = -3f, hrtfBalanceDb = 6f)
            val result = render(source, 2, settings)
            assertEquals(source.size, result.size)
            for (i in source.indices) {
                val gain = if (i % 2 == 0) 0.5011872336 else 1.0
                assertTrue("$renderer calibration sample $i", abs(result[i] - source[i] * gain) <= 2.0)
            }
        }
    }

    @Test fun twoVirtualSpeakersRetainShortTrackDurationInBothStereoModes() {
        for (renderer in 0..2) for (mode in 0..1) for (frames in listOf(1, 511, 1537)) {
            val source = ShortArray(frames * 2) { (sin(it * 0.08) * 4000).toInt().toShort() }
            val settings = SpatialSettings(mode = mode, renderer = renderer, virtualStereo = true,
                stereoSpeakers = listOf(SpeakerPosition(-125f, 40f, 2f), SpeakerPosition(85f, -30f, 0.75f)))
            val result = render(source, 2, settings)
            assertEquals("$renderer/$mode/$frames duration", source.size, result.size)
            assertTrue(result.all { abs(it.toInt()) < 32767 })
        }
    }

    @Test fun wideningSnapshotReachesFfmpegBeforeHeadphoneRendering() {
        val source = ShortArray(7003 * 2) { (sin(it * 0.031) * 3500).toInt().toShort() }
        for (renderer in 0..2) {
            val settings = SpatialSettings(mode = 1, renderer = renderer, virtualStereo = false,
                wideningAlgorithm = 1, wideningAmount = .5f)
            val result = render(source, 2, settings)
            assertEquals(source.size, result.size)
            // extrastereo m=2 mixed 50% with dry gives M +/- 1.5*S.
            for (frame in 0 until source.size / 2) {
                val left = source[frame * 2].toDouble()
                val right = source[frame * 2 + 1].toDouble()
                val mid = (left + right) / 2
                val side = (left - right) / 2
                assertTrue(abs(result[frame * 2] - (mid + 1.5 * side)) <= 2)
                assertTrue(abs(result[frame * 2 + 1] - (mid - 1.5 * side)) <= 2)
            }
        }
    }

    @Test fun v3SnapshotCarriesMultibandParametersThroughRealJni() {
        val source = ShortArray(7003 * 2) { index ->
            val frame = index / 2
            (sin(frame * if (index % 2 == 0) .071 else .193) * 3500).toInt().toShort()
        }
        for (renderer in 0..2) {
            val settings = SpatialSettings(mode = 1, renderer = renderer, virtualStereo = false,
                wideningAlgorithm = 6, wideningAmount = 1f, bandLowWidth = 1.5f,
                bandMidWidth = 1.5f, bandHighWidth = 1.5f, correlationGuard = false)
            val result = render(source, 2, settings)
            assertEquals(source.size, result.size)
            // The three complementary bands sum to S. Equal width=1.5 with
            // the guard disabled must give M +/- 1.5*S, including the first sample.
            for (frame in 0 until source.size / 2) {
                val left = source[frame * 2].toDouble()
                val right = source[frame * 2 + 1].toDouble()
                val mid = (left + right) / 2
                val side = (left - right) / 2
                assertTrue("$renderer v3 left sample $frame", abs(result[frame * 2] - (mid + 1.5 * side)) <= 2)
                assertTrue("$renderer v3 right sample $frame", abs(result[frame * 2 + 1] - (mid - 1.5 * side)) <= 2)
            }
        }
    }

    @Test fun v3RoomParametersPreserveDryAtZeroAndAddReflectionsWhenEnabled() {
        val source = ShortArray(8197 * 2) { (sin(it * .077) * 3000).toInt().toShort() }
        for (renderer in 0..2) {
            val settings = SpatialSettings(mode = 0, renderer = renderer, virtualStereo = false,
                roomEnabled = false, roomMix = .6f, roomSize = 4f, roomDamping = .3f, roomDecay = .5f)
            val dry = render(source, 2, settings)
            val zero = render(source, 2, settings.copy(roomEnabled = true, roomMix = 0f))
            assertArrayEquals("$renderer zero room mix", dry, zero)
            val room = render(source, 2, settings.copy(roomEnabled = true))
            assertEquals(dry.size, room.size)
            assertTrue("$renderer room snapshot did not reach native", room.indices.any { abs(room[it].toInt() - dry[it]) > 10 })
            assertTrue("$renderer room clipped", room.all { abs(it.toInt()) < 32767 })
        }
    }
}
