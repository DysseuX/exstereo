/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.fossify.musicplayer.diagnostics.StartupDiagnostics
import org.fossify.musicplayer.playback.SimpleEqualizer
import org.fossify.musicplayer.playback.Equalizer30Settings

/** Converts PCM before AudioTrack can downmix multichannel music. */
@UnstableApi
class SpatialAudioProcessor(context: Context) : BaseAudioProcessor() {
    private val appContext = context.applicationContext
    private var handle = 0L
    private var framesToSkip = 0
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var initializationReported = false
    private var parameterSnapshot: SpatialSettings? = null
    private var equalizerSnapshot: Equalizer30Settings? = null
    private var nativeParameters = FloatArray(0)
    private var diagnosticFrames = 0L
    private var inputPeak = 0f
    private var outputPeak = 0f
    private var processingNanos = 0L

    init {
        SpatialPreferences.initialize(context)
        SimpleEqualizer.initialize(context)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..8) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        PlaybackEnergy.clear()
        if (handle != 0L) NativeSpatialEngine.release(handle)
        handle = 0L
        equalizerSnapshot = null
        inputFrames = 0
        outputFrames = 0
        diagnosticFrames = 0
        inputPeak = 0f
        outputPeak = 0f
        processingNanos = 0
        framesToSkip = 0
        if (inputAudioFormat.sampleRate > 0) {
            StartupDiagnostics.updatePlayback("DSP input", "${inputAudioFormat.sampleRate} Hz; ${inputAudioFormat.channelCount} channels; PCM16")
            val settings = SpatialPreferences.current
            if (!initializationReported) StartupDiagnostics.mark("native audio initialization: ${inputAudioFormat.sampleRate} Hz, ${inputAudioFormat.channelCount} channels, renderer=${settings.renderer}, upmixer=${settings.upmixer}, HRTF=${settings.hrtf}")
            handle = NativeSpatialEngine.createForSettings(inputAudioFormat.sampleRate, appContext, settings)
            check(handle != 0L) { "Cannot initialize spatial audio" }
            framesToSkip = NativeSpatialEngine.latencyFrames(handle)
            if (!initializationReported) {
                StartupDiagnostics.mark("native audio initialized")
                initializationReported = true
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        check(handle != 0L)
        val channels = inputAudioFormat.channelCount
        // Bounded buffers keep JNI allocations independent of decoder buffer size.
        val frames = minOf(inputBuffer.remaining() / (channels * 2), 4096)
        if (frames == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val samples = FloatArray(frames * channels) { inputBuffer.short / 32768f }
        for (sample in samples) inputPeak = maxOf(inputPeak, kotlin.math.abs(sample))
        inputFrames += frames
        writeOutput(process(samples))
    }

    private fun process(input: FloatArray): FloatArray {
        val settings = SpatialPreferences.current
        if (parameterSnapshot !== settings) {
            nativeParameters = settings.nativeParameters()
            parameterSnapshot = settings
            StartupDiagnostics.updatePlayback("spatial", "renderer=${settings.renderer}; upmix=${settings.upmixer}; mode=${settings.mode}; widening=${settings.wideningAlgorithm}")
        }
        val channels = inputAudioFormat.channelCount
        val started = System.nanoTime()
        val equalizer = SimpleEqualizer.current
        if (equalizerSnapshot !== equalizer) {
            NativeSpatialEngine.setEqualizer(handle, equalizer.gainsDb())
            equalizerSnapshot = equalizer
        }
        val output = NativeSpatialEngine.processLive(handle, SpatialChannelLayout.expand(input, channels),
            SpatialChannelLayout.nativeChannels(channels), nativeParameters)
        processingNanos += System.nanoTime() - started
        return output
    }

    private fun writeOutput(samples: FloatArray) {
        val skip = minOf(framesToSkip, samples.size / 2)
        framesToSkip -= skip
        val count = minOf((samples.size / 2 - skip).toLong(), inputFrames - outputFrames).toInt()
        val output = replaceOutputBuffer(count * 4).order(ByteOrder.nativeOrder())
        var sumSquares = 0.0
        var peak = 0f
        for (i in skip * 2 until (skip + count) * 2) {
            val sample = if (samples[i].isFinite()) samples[i] else 0f
            val pcm = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            output.putShort(pcm)
            val emitted = pcm / 32768f
            sumSquares += emitted.toDouble() * emitted
            peak = maxOf(peak, kotlin.math.abs(emitted))
        }
        PlaybackEnergy.publish(sumSquares, peak, count * 2)
        outputPeak = maxOf(outputPeak, peak)
        outputFrames += count
        if (inputFrames - diagnosticFrames >= inputAudioFormat.sampleRate * 2L) {
            StartupDiagnostics.updatePlayback("PCM signal", "frames in=$inputFrames out=$outputFrames; peak in=$inputPeak out=$outputPeak")
            val budgetNanos = (inputFrames - diagnosticFrames).toDouble() / inputAudioFormat.sampleRate * 1_000_000_000
            StartupDiagnostics.updatePlayback("DSP time / audio time", "${(100 * processingNanos / budgetNanos).toInt()}% over last 2 s (100% leaves no playback headroom)")
            diagnosticFrames = inputFrames
            processingNanos = 0
            inputPeak = 0f
            outputPeak = 0f
        }
        output.flip()
    }

    override fun onQueueEndOfStream() {
        if (handle == 0L || inputFrames == 0L) return
        val latency = NativeSpatialEngine.latencyFrames(handle)
        writeOutput(process(FloatArray(latency * inputAudioFormat.channelCount)))
        check(outputFrames == inputFrames) { "Spatial audio changed track duration" }
    }

    override fun onReset() {
        PlaybackEnergy.clear()
        if (handle != 0L) NativeSpatialEngine.release(handle)
        handle = 0L
        parameterSnapshot = null
        equalizerSnapshot = null
    }
}
