/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

/* APE framing adapted from FFmpeg libavformat/ape.c.
 * Copyright (c) 2007 Benjamin Zores, based upon libdemac from Dave Chapman.
 * LGPL-2.1-or-later; see third_party/ffmpeg-8.1.3/COPYING.LGPLv2.1.
 */
package org.fossify.musicplayer.playback.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** APE 3.80–3.99 framing, following FFmpeg's LGPL ape.c (see CODEC_ADDITIONS.md).
 * Only seek tables and one compressed frame are retained, never the decoded song.
 */
@UnstableApi
internal class ApeExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var offsets = LongArray(0)
    private var sizes = IntArray(0)
    private var skips = IntArray(0)
    private var blocks = 0
    private var finalBlocks = 0
    private var rate = 0
    private var frame = 0
    private var prefix = 0
    private var ready = false

    override fun sniff(input: ExtractorInput): Boolean {
        try {
            val start = ByteArray(10)
            input.peekFully(start, 0, 10)
            prefix = 0
            if (String(start, 0, 3, Charsets.US_ASCII) == "ID3") {
                if ((6..9).any { start[it].toInt() and 0x80 != 0 }) return false
                prefix = 10 + (6..9).fold(0) { value, i -> (value shl 7) or start[i].toInt() }
                if (start[5].toInt() and 0x10 != 0) prefix += 10
                if (prefix > 1024 * 1024) return false
                input.advancePeekPosition(prefix - 10)
                input.peekFully(start, 0, 6)
            }
            return String(start, 0, 4, Charsets.US_ASCII) == "MAC " && u16(start, 4) in 3800..3990
        } catch (_: EOFException) { return false }
        finally { input.resetPeekPosition() }
    }
    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!ready) readHeader(input)
        if (frame >= offsets.size) return Extractor.RESULT_END_OF_INPUT
        if (input.position != offsets[frame]) {
            seekPosition.position = offsets[frame]
            return Extractor.RESULT_SEEK
        }
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(if (frame == offsets.lastIndex) finalBlocks else blocks).putInt(skips[frame]).array()
        track.sampleData(ParsableByteArray(header), header.size)
        var remaining = sizes[frame]
        var count = 0
        while (remaining > 0) {
            val n = track.sampleData(input, minOf(remaining, 65536), true)
            if (n == -1) {
                if (frame != offsets.lastIndex || count == 0) invalid("Truncated APE frame")
                break
            }
            if (n == 0) invalid("APE read made no progress")
            count += n; remaining -= n
        }
        track.sampleMetadata(frame.toLong() * blocks * 1_000_000 / rate, C.BUFFER_FLAG_KEY_FRAME, count + 8, 0, null)
        frame++
        return Extractor.RESULT_CONTINUE
    }
    private fun readHeader(input: ExtractorInput) {
        input.skipFully(prefix)
        fun bytes(n: Int) = ByteArray(n).also { input.readFully(it, 0, n) }
        fun skip(n: Long) {
            if (n !in 0..MAX_HEADER.toLong()) invalid("APE header exceeds limit")
            input.skipFully(n.toInt())
        }
        val base = bytes(6)
        if (String(base, 0, 4, Charsets.US_ASCII) != "MAC ") invalid("Invalid APE signature")
        val version = u16(base, 4)
        if (version !in 3800..3990) invalid("Unsupported APE version $version")
        var descriptor = 0L
        var headerLength = 32L
        val seekLength: Long
        val wavHeader: Long
        var wavTail = 0L
        var audioSize = 0L
        val compression: Int
        val flags: Int
        val channels: Int
        val bits: Int
        val frames: Long
        if (version >= 3980) {
            val d = bytes(46)
            descriptor = u32(d, 2); headerLength = u32(d, 6)
            seekLength = u32(d, 10); wavHeader = u32(d, 14)
            if (u32(d, 22) > 0x7fffffffL) invalid("APE audio size overflow")
            audioSize = u32(d, 18) or (u32(d, 22) shl 32)
            wavTail = u32(d, 26)
            if (descriptor < 52 || headerLength < 24) invalid("Short APE descriptor/header")
            skip(descriptor - 52)
            val h = bytes(24)
            compression = u16(h, 0); flags = u16(h, 2)
            blocks = bounded(u32(h, 4), 1, MAX_BLOCKS, "blocks")
            finalBlocks = bounded(u32(h, 8), 1, blocks, "final blocks")
            frames = u32(h, 12); bits = u16(h, 16); channels = u16(h, 18)
            rate = bounded(u32(h, 20), 8000, 384000, "sample rate")
            skip(headerLength - 24)
        } else {
            val h = bytes(26)
            compression = u16(h, 0); flags = u16(h, 2); channels = u16(h, 4)
            rate = bounded(u32(h, 6), 8000, 384000, "sample rate")
            wavHeader = u32(h, 10); wavTail = u32(h, 14); frames = u32(h, 18)
            blocks = if (version >= 3950) 73728 * 4 else if (version >= 3900 || compression >= 4000) 73728 else 9216
            finalBlocks = bounded(u32(h, 22), 1, blocks, "final blocks")
            if (flags and 4 != 0) { skip(4); headerLength += 4 }
            seekLength = if (flags and 16 != 0) { headerLength += 4; u32(bytes(4), 0) * 4 } else frames * 4
            bits = if (flags and 1 != 0) 8 else if (flags and 8 != 0) 24 else 16
            if (flags and 32 == 0) skip(wavHeader)
        }
        val total = bounded(frames, 1, 262144, "frame count")
        if (channels !in 1..2 || bits !in listOf(8, 16, 24)) invalid("Unsupported APE channels/bit depth")
        if (compression !in listOf(1000, 2000, 3000, 4000, 5000)) invalid("Invalid APE compression")
        if (seekLength < frames * 4 || seekLength > MAX_HEADER) invalid("Invalid APE seek table")
        val first = prefix + descriptor + headerLength + seekLength + wavHeader + if (version < 3810) frames else 0
        if (first < input.position || first - input.position > MAX_HEADER) invalid("Invalid APE audio offset")
        offsets = LongArray(total); sizes = IntArray(total); skips = IntArray(total)
        val positions = LongArray(total) { prefix + u32(bytes(4), 0) }
        positions[0] = first
        skip(seekLength - frames * 4)
        val end = if (audioSize > 0) {
            if (audioSize > Long.MAX_VALUE - first) invalid("APE audio size overflow")
            first + audioSize
        } else if (input.length > 0) input.length - wavTail else positions.last() + finalBlocks * 8L
        for (i in 0 until total) {
            val position = positions[i]
            val next = if (i + 1 < total) positions[i + 1] else end
            if (position < first || next <= position || (input.length >= 0 && position >= input.length)) invalid("Invalid APE frame offsets")
            val skipBytes = ((position - first) and 3).toInt()
            offsets[i] = position - skipBytes
            skips[i] = skipBytes
            sizes[i] = bounded((next - position + skipBytes + 3) and -4L, 1, MAX_PACKET, "packet size")
        }
        if (version < 3810) {
            for (i in 0 until total) {
                val bitOffset = bytes(1)[0].toInt() and 0xff
                if (bitOffset > 31) invalid("Invalid APE bit offset")
                if (i > 0 && bitOffset > 0) sizes[i - 1] += 4
                skips[i] = skips[i] * 8 + bitOffset
            }
        }
        val extra = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(version.toShort()).putShort(compression.toShort()).putShort(flags.toShort()).putShort(bits.toShort()).array()
        track.format(Format.Builder().setSampleMimeType(MIME).setChannelCount(channels).setSampleRate(rate)
            .setInitializationData(listOf(extra)).setMaxInputSize(65536).build())
        val duration = ((total - 1L) * blocks + finalBlocks) * 1_000_000 / rate
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = duration
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val index = ((timeUs.coerceIn(0, duration) * rate / 1_000_000) / blocks).toInt().coerceIn(0, total - 1)
                fun point(i: Int) = SeekPoint(i.toLong() * blocks * 1_000_000 / rate, offsets[i])
                val firstPoint = point(index)
                return if (firstPoint.timeUs < timeUs && index < total - 1) SeekMap.SeekPoints(firstPoint, point(index + 1))
                    else SeekMap.SeekPoints(firstPoint)
            }
        })
        ready = true
    }
    override fun seek(position: Long, timeUs: Long) {
        frame = if (!ready || position == 0L) 0 else offsets.binarySearch(position).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
    }
    override fun release() = Unit
    companion object {
        const val MIME = "audio/x-ape"
        private const val MAX_HEADER = 16 * 1024 * 1024
        private const val MAX_PACKET = 32 * 1024 * 1024
        private const val MAX_BLOCKS = 73728 * 16
        private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
        private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i+2).toLong() shl 16)
        private fun invalid(message: String): Nothing = throw ParserException.createForMalformedContainer(message, null)
        private fun bounded(value: Long, min: Int, max: Int, what: String): Int {
            if (value !in min.toLong()..max.toLong()) invalid("Invalid APE $what: $value")
            return value.toInt()
        }
    }
}
