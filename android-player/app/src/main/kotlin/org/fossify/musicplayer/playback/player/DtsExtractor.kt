/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.*
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Raw DTS (16/14-bit BE/LE), DTS-HD and DTSHDHDR chunk containers.
 * The FFmpeg parser keeps core and HD extensions together in one access unit.
 */
@UnstableApi
internal class DtsExtractor : IndexedRawAudioExtractor() {
    private val buffer = ByteArray(16384)
    private val info = LongArray(6)
    private var handle = 0L
    private var offset = 0
    private var length = 0
    private var eos = false
    private var initialized = false
    private var chunked = false
    private var dataEnd = Long.MAX_VALUE
    private var dataStart = 0L
    private var headerRead = false
    override fun sniff(input: ExtractorInput): Boolean {
        try {
            val b = ByteArray(10)
            input.peekFully(b, 0, b.size)
            chunked = String(b, 0, 8, Charsets.US_ASCII) == "DTSHDHDR"
            val magic = ByteBuffer.wrap(b).int
            return chunked || magic in intArrayOf(0x7ffe8001, 0xfe7f0180.toInt(), 0x1fffe800, 0xff1f00e8.toInt(), 0x64582025)
        } catch (_: EOFException) { return false }
        finally { input.resetPeekPosition() }
    }
    override fun nextPacket(input: ExtractorInput): Packet? {
        if (!initialized) {
            if (!FfmpegLibrary.isAvailable()) invalid("DTS software decoder unavailable")
            if (chunked && !headerRead) {
                while (true) {
                    val header = ByteArray(16); input.readFully(header, 0, 16)
                    val type = String(header, 0, 8, Charsets.US_ASCII)
                    val size = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getLong(8)
                    if (size <= 0 || size > Long.MAX_VALUE - input.position) invalid("Invalid DTS-HD chunk size")
                    if (type == "STRMDATA") { dataStart = input.position; dataEnd = input.position + size; break }
                    if (size > 16 * 1024 * 1024 || input.position > 16 * 1024 * 1024) invalid("DTS-HD header exceeds limit")
                    input.skipFully(size.toInt())
                }
            }
            headerRead = true
            if (input.position < dataStart) input.skipFully((dataStart - input.position).toInt())
            handle = NativeDtsParser.create(); initialized = true
        }
        while (!eos) {
            if (offset == length) {
                offset = 0
                length = if (input.position >= dataEnd) -1 else input.read(buffer, 0, minOf(buffer.size.toLong(), dataEnd - input.position).toInt())
                if (length == -1) { length = 0; eos = true }
            }
            val position = input.position - (length - offset)
            val packet = NativeDtsParser.parse(handle, buffer, offset, length - offset, position, info)
            offset += info[0].toInt()
            if (packet != null) {
                if (info[1] <= 0 || info[2] <= 0 || info[5] < dataStart) invalid("Invalid DTS frame position/duration")
                return Packet(info[5], packet, info[1].toInt(), info[2].toInt(),
                    Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                        .setChannelCount(info[3].toInt()).setSampleRate(info[4].toInt()).setMaxInputSize(65536).build())
            }
            if (!eos && info[0] == 0L) invalid("DTS parser made no progress")
        }
        return null
    }
    override fun resetReader() {
        if (handle != 0L) NativeDtsParser.release(handle)
        handle = 0L; initialized = false; offset = 0; length = 0; eos = false
    }
    private fun invalid(message: String): Nothing = throw ParserException.createForMalformedContainer(message, null)
}

/** Loaded with the existing FFmpeg JNI, no new runtime library or network access. */
internal object NativeDtsParser {
    external fun create(): Long
    external fun parse(handle: Long, input: ByteArray, offset: Int, length: Int, position: Long, info: LongArray): ByteArray?
    external fun release(handle: Long)
}
