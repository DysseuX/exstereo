/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package androidx.media3.decoder.ffmpeg

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.extractor.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import org.fossify.musicplayer.playback.player.SpatialExtractorsFactory

/** Actual Media3 extractors and actual FFmpeg JNI, using host builds of the APK's sources. */
@UnstableApi
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FormatDecodeTest {
    private data class Packet(val data: ByteArray, val timeUs: Long)
    private class Capture : TrackOutput {
        lateinit var format: Format
        val packets = mutableListOf<Packet>()
        private val pending = ByteArrayOutputStream()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count > 0) pending.write(bytes, 0, count)
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            packets += Packet(bytes.copyOfRange(bytes.size-offset-size, bytes.size-offset), timeUs)
            pending.reset()
            if (offset > 0) pending.write(bytes, bytes.size-offset, offset)
        }
    }

    private fun extract(bytes: ByteArray): Capture {
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            if (position == bytes.size) -1 else minOf(length, bytes.size-position).also {
                bytes.copyInto(buffer, offset, position, position+it)
                position += it
            }
        }
        var input = DefaultExtractorInput(reader, 0, bytes.size.toLong())
        val extractor = SpatialExtractorsFactory().createExtractors().first { candidate ->
            // BundledExtractorsAdapter treats a short non-matching file as a failed sniff.
            try { candidate.sniff(input) } catch (_: java.io.EOFException) { false } finally { input.resetPeekPosition() }
        }
        val capture = Capture()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) capture else Capture()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val seek = PositionHolder()
        try {
            for (iteration in 0..10000) {
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> return capture
                    Extractor.RESULT_SEEK -> {
                        position = seek.position.toInt()
                        input = DefaultExtractorInput(reader, seek.position, bytes.size.toLong())
                    }
                }
            }
            error("Extractor did not finish")
        } finally { extractor.release() }
    }

    @Test fun m4aFlacAndOpusVariantsProduceAudiblePcm() {
        assertTrue("Build host ffmpegJNI before this test", FfmpegLibrary.isAvailable())
        val loader = javaClass.classLoader!!
        val manifest = JSONArray(loader.getResource("audio-formats/manifest.json")!!.readText())
        assertTrue(manifest.length() >= 30)
        for (index in 0 until manifest.length()) {
            val item = manifest.getJSONObject(index)
            val name = item.getString("name")
            val capture = extract(loader.getResource("audio-formats/$name")!!.readBytes())
            assertTrue("No packets: $name", capture.packets.isNotEmpty())
            val channels = item.getInt("channels")
            val decoder = FfmpegAudioDecoder(capture.format, 4, 4, 65536, false)
            var samples = 0L
            val peaks = IntArray(channels)
            val real = DoubleArray(channels)
            val imaginary = DoubleArray(channels)
            var ended = false
            val deadline = System.nanoTime() + 20_000_000_000L
            fun drain(): Boolean {
                val output: SimpleDecoderOutputBuffer = decoder.dequeueOutputBuffer() ?: return false
                try {
                    if (output.isEndOfStream) ended = true
                    else if (!output.shouldBeSkipped) {
                        val data = output.data!!.order(ByteOrder.nativeOrder())
                        assertEquals(name, item.getInt("rate"), decoder.sampleRate)
                        assertEquals(name, channels, decoder.channelCount)
                        assertEquals(name, 0, data.remaining() % (channels*2))
                        while (data.hasRemaining()) {
                            val channel = (samples % channels).toInt()
                            val sample = data.short.toInt()
                            peaks[channel] = maxOf(peaks[channel], kotlin.math.abs(sample))
                            val frequency = if (channels >= 6 && channel == 3) 80 else 330+110*channel
                            val phase = 2*kotlin.math.PI*frequency*(samples/channels)/item.getInt("rate")
                            real[channel] += sample*kotlin.math.cos(phase)
                            imaginary[channel] += sample*kotlin.math.sin(phase)
                            samples++
                        }
                    }
                } finally { output.release() }
                return true
            }
            try {
                for (packet in capture.packets + Packet(byteArrayOf(), 0)) {
                    var input = decoder.dequeueInputBuffer()
                    while (input == null) {
                        assertTrue("Decoder stalled: $name", System.nanoTime() < deadline)
                        if (!drain()) Thread.sleep(1)
                        input = decoder.dequeueInputBuffer()
                    }
                    if (packet.data.isEmpty()) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    else {
                        input.ensureSpaceForWrite(packet.data.size)
                        input.data!!.put(packet.data)
                        input.timeUs = packet.timeUs
                        input.flip()
                    }
                    decoder.queueInputBuffer(input)
                    while (drain()) { }
                }
                while (!ended) {
                    assertTrue("No end of stream: $name", System.nanoTime() < deadline)
                    if (!drain()) Thread.sleep(1)
                }
                assertTrue("Too little PCM: $name", samples >= item.getInt("rate")*channels*.25)
                assertTrue("Silent decoded channel: $name ${peaks.toList()}", peaks.all { it > 100 })
                for (channel in 0 until channels) {
                    val magnitude = kotlin.math.hypot(real[channel], imaginary[channel])/(samples/channels)
                    assertTrue("Wrong channel mapping/tone: $name ch=$channel magnitude=$magnitude", magnitude > 300)
                }
                println("PASS $name: ${decoder.name}, ${decoder.sampleRate} Hz, ${decoder.channelCount} ch, samples=$samples, peaks=${peaks.toList()}")
            } finally { decoder.release() }
        }
    }
}
