/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only */

package org.fossify.musicplayer.spatial

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.fossify.musicplayer.playback.player.AudioOnlyRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Exercises the real extractor, MediaCodec renderer, sink mapping and processor chain. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OpusDecodeTest {
    @Test
    fun opus71ReachesProcessorInWaveOrderBeforeStereoOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File.createTempFile("channel-order-", ".opus", context.cacheDir)
        instrumentation.context.assets.open("channel_order_7_1.opus").use { source ->
            fixture.outputStream().use { source.copyTo(it) }
        }

        val capture = ChannelCaptureProcessor()
        val completed = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException>()
        val trackConfig = AtomicReference<AudioSink.AudioTrackConfig>()
        val decoder = AtomicReference<String>()
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val renderers = object : AudioOnlyRenderersFactory(context) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioProcessors(arrayOf(capture))
                        .build()
                }
                val selector = DefaultTrackSelector(context).apply {
                    parameters = buildUponParameters()
                        .setConstrainAudioChannelCountToDeviceCapabilities(false)
                        .setAudioOffloadPreferences(
                            AudioOffloadPreferences.Builder()
                                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                                .build()
                        )
                        .build()
                }
                player = ExoPlayer.Builder(context, renderers)
                    .setTrackSelector(selector)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setSpatializationBehavior(android.media.AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
                            .build(),
                        false,
                    )
                    .build().apply {
                        addAnalyticsListener(object : AnalyticsListener {
                            override fun onAudioDecoderInitialized(
                                eventTime: AnalyticsListener.EventTime,
                                decoderName: String,
                                initializedTimestampMs: Long,
                                initializationDurationMs: Long,
                            ) {
                                decoder.set(decoderName)
                            }

                            override fun onAudioTrackInitialized(
                                eventTime: AnalyticsListener.EventTime,
                                audioTrackConfig: AudioSink.AudioTrackConfig,
                            ) {
                                trackConfig.set(audioTrackConfig)
                            }
                        })
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) completed.countDown()
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                failure.set(error)
                                completed.countDown()
                            }
                        })
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                        prepare()
                        play()
                    }
            }
            assertTrue("The 3.6-second Opus fixture did not finish", completed.await(45, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Media3 playback failed", it) }
            assertNotNull("No actual audio decoder was initialized", decoder.get())
            capture.assertChannelOrder("decoder=${decoder.get()}")
            val output = trackConfig.get()
            assertNotNull("The real AudioTrack was not initialized", output)
            requireNotNull(output)
            assertEquals("Only the rendered stereo signal should reach AudioTrack", AudioFormat.CHANNEL_OUT_STEREO, output.channelConfig)
            assertEquals(SAMPLE_RATE, output.sampleRate)
            assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
            assertEquals(false, output.offload)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            fixture.delete()
        }
    }

    /** Consumes decoded PCM before HRTF and emits silence, so tests do not play loud tones. */
    private class ChannelCaptureProcessor : BaseAudioProcessor() {
        private val energies = Array(CHANNELS) { DoubleArray(CHANNELS) }
        private val sine = DoubleArray(CHANNELS)
        private val cosine = DoubleArray(CHANNELS)
        private val samples = IntArray(CHANNELS)
        private val observedChannels = mutableSetOf<Int>()
        private val observedRates = mutableSetOf<Int>()
        private var frames = 0L

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val channels = inputAudioFormat.channelCount
            observedChannels.add(channels)
            observedRates.add(inputAudioFormat.sampleRate)
            inputBuffer.order(ByteOrder.nativeOrder())
            val frameCount = inputBuffer.remaining() / (channels * 2)
            check(inputBuffer.remaining() % (channels * 2) == 0) { "Partial PCM frame" }
            val output = replaceOutputBuffer(frameCount * 2 * 2)
            repeat(frameCount) {
                val slot = (frames / SLOT_FRAMES).toInt()
                val withinSlot = (frames % SLOT_FRAMES).toInt()
                val measure = slot in 0 until CHANNELS && withinSlot in MEASURE_START until MEASURE_END
                val phase = if (measure) 2.0 * PI * FREQUENCIES[slot] * withinSlot / SAMPLE_RATE else 0.0
                repeat(channels) { channel ->
                    val value = inputBuffer.short.toDouble() / 32768.0
                    if (measure && channel < CHANNELS) {
                        energies[slot][channel] += value * value
                        if (channel == slot) {
                            sine[slot] += value * sin(phase)
                            cosine[slot] += value * cos(phase)
                        }
                    }
                }
                if (measure) samples[slot]++
                output.putInt(0)
                frames++
            }
            output.flip()
        }

        fun assertChannelOrder(detail: String) {
            assertEquals("The decoder must preserve all eight channels: $detail", setOf(CHANNELS), observedChannels)
            assertEquals(setOf(SAMPLE_RATE), observedRates)
            assertTrue("Too few decoded frames: $frames", frames >= CHANNELS * SLOT_FRAMES - SAMPLE_RATE / 20)
            for (slot in 0 until CHANNELS) {
                val label = CHANNEL_NAMES[slot]
                assertEquals("Missing measurement window for $label", MEASURE_END - MEASURE_START, samples[slot])
                val expectedEnergy = energies[slot][slot]
                val rms = sqrt(expectedEnergy / samples[slot])
                assertTrue("$label is missing or attenuated: rms=$rms; $detail", rms > 0.08)
                val leakage = energies[slot].filterIndexed { index, _ -> index != slot }.sum()
                assertTrue(
                    "$label appears at the wrong index: energies=${energies[slot].contentToString()}; $detail",
                    expectedEnergy > 20.0 * leakage,
                )
                val tonalCorrelation = sqrt(
                    2.0 * (sine[slot] * sine[slot] + cosine[slot] * cosine[slot]) / (samples[slot] * expectedEnergy)
                )
                assertTrue("Unexpected frequency in $label: correlation=$tonalCorrelation; $detail", tonalCorrelation > 0.65)
            }
        }
    }

    companion object {
        private const val CHANNELS = 8
        private const val SAMPLE_RATE = 48_000
        private const val SLOT_FRAMES = 21_600 // 450 ms per channel, 3.6 seconds total.
        private const val MEASURE_START = 7_200 // Interior of the 100–350 ms tone burst.
        private const val MEASURE_END = 14_400
        private val CHANNEL_NAMES = arrayOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
        private val FREQUENCIES = doubleArrayOf(320.0, 440.0, 580.0, 70.0, 760.0, 920.0, 1160.0, 1400.0)
    }
}
