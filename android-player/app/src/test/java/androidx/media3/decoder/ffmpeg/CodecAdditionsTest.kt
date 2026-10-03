/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package androidx.media3.decoder.ffmpeg

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.exoplayer.RendererCapabilities
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.fossify.musicplayer.playback.player.RecoveringAc3Extractor
import org.fossify.musicplayer.playback.player.AudioOnlyRenderersFactory
import org.fossify.musicplayer.playback.player.SpatialExtractorsFactory
import org.fossify.musicplayer.spatial.SpatialAudioProcessor
import org.fossify.musicplayer.spatial.SpatialPreferences
import org.fossify.musicplayer.spatial.SpatialSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CodecAdditionsTest {
    private data class Result(val frames: Long, val peak: Int, val rms: Double, val hash: String,
                              val preview: ByteArray, val extractor: String, val mime: String, val maxError: Int)
    /** Full file is decoded incrementally; retain two seconds starting at audible PCM for DSP checks. */
    private fun decode(file: File, rate: Int, channels: Int): Result {
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3_JOC))
        var decoder: FfmpegAudioDecoder? = null
        var lastFormat: Format? = null
        var frames = 0L
        var peak = 0
        var squares = 0.0
        var ended = false
        val reference = if (file.name == "core.dts") ByteBuffer.wrap(File(file.parentFile, "core.dts.s16le").readBytes()).order(ByteOrder.LITTLE_ENDIAN) else null
        var maxError = 0
        val hash = MessageDigest.getInstance("SHA-256")
        val preview = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 120_000_000_000L

        fun drain(): Boolean {
            val d = decoder ?: return false
            val output = d.dequeueOutputBuffer() ?: return false
            try {
                if (output.isEndOfStream) ended = true
                else if (!output.shouldBeSkipped) {
                    assertEquals(rate, d.sampleRate)
                    assertEquals(channels, d.channelCount)
                    val data = output.data!!.order(ByteOrder.nativeOrder())
                    val bytes = ByteArray(data.remaining())
                    data.duplicate().get(bytes)
                    hash.update(bytes)
                    assertEquals(0, bytes.size % (channels*2))
                    frames += bytes.size/(channels*2)
                    var chunkPeak = 0
                    while (data.hasRemaining()) {
                        val sample = data.short.toInt()
                        if (reference != null) maxError = maxOf(maxError, kotlin.math.abs(sample - reference.short.toInt()))
                        peak = maxOf(peak, kotlin.math.abs(sample))
                        chunkPeak = maxOf(chunkPeak, kotlin.math.abs(sample))
                        squares += sample.toDouble()*sample
                    }
                    if (chunkPeak > 1000 || preview.size() > 0) {
                        val keep = minOf(bytes.size, 2*rate*channels*2 - preview.size())
                        if (keep > 0) preview.write(bytes, 0, keep)
                    }
                }
            } finally { output.release() }
            return true
        }
        fun packet(bytes: ByteArray?, timeUs: Long) {
            val d = decoder ?: error("Sample arrived before its format")
            var input = d.dequeueInputBuffer()
            while (input == null) {
                check(System.nanoTime() < deadline) { "Decoder stalled: ${file.name}" }
                if (!drain()) Thread.sleep(1)
                input = d.dequeueInputBuffer()
            }
            if (bytes == null) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            else {
                input.ensureSpaceForWrite(bytes.size)
                input.data!!.put(bytes)
                input.timeUs = timeUs
                input.flip()
            }
            d.queueInputBuffer(input)
            while (drain()) { }
        }
        val capture = object : TrackOutput {
            private val pending = ByteArrayOutputStream()
            override fun format(format: Format) {
                lastFormat = format
                if (decoder == null) decoder = FfmpegAudioDecoder(format, 4, 4, 65536, false)
            }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val bytes = ByteArray(minOf(length, 65536))
                val read = input.read(bytes, 0, bytes.size)
                if (read > 0) pending.write(bytes, 0, read)
                else if (read == -1 && !allowEndOfInput) throw EOFException()
                return read
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val bytes = ByteArray(length)
                data.readBytes(bytes, 0, length)
                pending.write(bytes)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                val bytes = pending.toByteArray()
                packet(bytes.copyOfRange(bytes.size-offset-size, bytes.size-offset), timeUs)
                pending.reset()
                if (offset > 0) pending.write(bytes, bytes.size-offset, offset)
            }
        }
        var extractor: Extractor? = null
        try {
            RandomAccessFile(file, "r").use { source ->
                val reader = DataReader { buffer, offset, length -> source.read(buffer, offset, length) }
                var input = DefaultExtractorInput(reader, 0, source.length())
                val selected = SpatialExtractorsFactory().createExtractors(Uri.fromFile(file), emptyMap()).first { candidate ->
                    try { candidate.sniff(input) } catch (_: EOFException) { false }
                    finally { input.resetPeekPosition() }
                }
                extractor = selected
                selected.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput =
                        if (type == C.TRACK_TYPE_AUDIO) capture else DiscardingTrackOutput()
                    override fun endTracks() = Unit
                    override fun seekMap(seekMap: SeekMap) = Unit
                })
                val seek = PositionHolder()
                while (true) {
                    check(System.nanoTime() < deadline) { "Extractor stalled: ${file.name}" }
                    when (selected.read(input, seek)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            source.seek(seek.position)
                            input = DefaultExtractorInput(reader, seek.position, source.length())
                        }
                    }
                }
                packet(null, 0)
                while (!ended) {
                    check(System.nanoTime() < deadline)
                    if (!drain()) Thread.sleep(1)
                }
            }
            assertTrue("Silent ${file.name}", frames > 0 && peak > 100)
            val rms = kotlin.math.sqrt(squares/(frames*channels))/32768
            assertTrue("Inaudible ${file.name}: $rms", rms > 0.0001)
            return Result(frames, peak, rms, hash.digest().joinToString("") { "%02x".format(it) },
                preview.toByteArray(), extractor!!.javaClass.simpleName, lastFormat!!.sampleMimeType!!, maxError)
        } finally { decoder?.release(); extractor?.release() }
    }

    @Test fun apeDtsAndLosslessDtsHdMatchIndependentFullFileDecode() {
        val directory = File(System.getenv("SPATIAL_CODEC_SAMPLES") ?: "../../artifacts/codec-samples")
        val manifest = org.json.JSONArray(File(directory, "manifest.json").readText())
        for (i in 0 until manifest.length()) {
            val item = manifest.getJSONObject(i)
            val file = File(directory, item.getString("name"))
            val channels = item.getInt("channels")
            val rate = item.getInt("rate")
            val result = decode(file, rate, channels)
            assertEquals(file.name, item.getLong("frames"), result.frames)
            if (file.name == "core.dts") {
                // Lossy DTS float synthesis can differ by one PCM16 LSB across FFmpeg/SIMD versions.
                println("DTS core maximum PCM16 difference: ${result.maxError}")
                assertTrue("DTS core differs by ${result.maxError} LSB", result.maxError <= 2)
            } else assertEquals(file.name, item.getString("pcm_sha256"), result.hash)
            println("PASS ${file.name}: ${result.extractor}, $rate Hz, $channels channels, ${result.frames} frames, PCM SHA-256 ${result.hash}")
            SpatialPreferences.initialize(RuntimeEnvironment.getApplication())
            SpatialPreferences.update(SpatialSettings(mode = 2))
            val processor = SpatialAudioProcessor(RuntimeEnvironment.getApplication())
            try {
                processor.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
                processor.flush(StreamMetadata.DEFAULT)
                val input = ByteBuffer.allocateDirect(result.preview.size).order(ByteOrder.nativeOrder()).put(result.preview)
                input.flip()
                var peak = 0
                fun drainPcm() {
                    val output = processor.output.order(ByteOrder.nativeOrder())
                    while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.short.toInt()))
                }
                while (input.hasRemaining()) { processor.queueInput(input); drainPcm() }
                processor.queueEndOfStream(); drainPcm()
                assertTrue("Silent HRTF ${file.name}", peak > 100)
                assertTrue(processor.isEnded)
            } finally { processor.reset() }
        }
    }
    @Test fun softwareRendererAcceptsApeAndEightChannelDtsHd() {
        val context = RuntimeEnvironment.getApplication()
        val factory = object : AudioOnlyRenderersFactory(context) {
            fun sinkForTest() = buildAudioSink(context, false, false)
        }
        val sink = factory.sinkForTest()
        try {
            val renderer = FfmpegAudioRenderer(null, null, sink)
            for ((mime, channels) in listOf("audio/x-ape" to 2, MimeTypes.AUDIO_DTS to 6, MimeTypes.AUDIO_DTS_HD to 8)) {
                val format = Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48000).build()
                assertEquals(mime, C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)))
            }
        } finally { sink.reset() }
    }
}
