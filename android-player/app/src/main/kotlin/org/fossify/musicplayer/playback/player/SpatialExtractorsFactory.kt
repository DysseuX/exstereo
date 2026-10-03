/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.Ac3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.ogg.OggExtractor
import org.fossify.musicplayer.diagnostics.StartupDiagnostics

/** Try normal containers first, irrespective of a misleading file extension. */
@UnstableApi
class SpatialExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, emptyMap())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf<Extractor>(ApeExtractor(), DtsExtractor()) + defaults.createExtractors(uri, responseHeaders)
            .filterNot { it is Ac3Extractor }
            .map { when (it) {
                is Mp3Extractor -> IndexedMp3Extractor()
                is AdtsExtractor -> IndexedAdtsExtractor()
                is OggExtractor -> PrerollOggExtractor()
                else -> it
            } }.toTypedArray() + RecoveringAc3Extractor()
}

/** Bounded last-resort detection of raw Dolby audio behind a damaged header.
 * Four complete consecutive syncframes are required, not merely a magic byte pair.
 * Index real surviving frames across later gaps; missing audio is not synthesized.
 */
@UnstableApi
internal class RecoveringAc3Extractor : IndexedRawAudioExtractor() {
    private var audioOffset = 0L
    private var reported = false
    private val buffer = ByteArray(65536)
    private var offset = 0
    private var end = 0
    private var eof = false

    override fun sniff(input: ExtractorInput): Boolean {
        val bytes = ByteArray(MAX_PREFIX + 4 * MAX_FRAME)
        var length = 0
        try {
            while (length < bytes.size) {
                val read = input.peek(bytes, length, bytes.size - length)
                if (read == -1) break
                if (read == 0) return false
                length += read
            }
            val header = ByteArray(6)
            for (start in 0 until minOf(MAX_PREFIX, length)) {
                if (bytes[start] != 0x0b.toByte()) continue
                var position = start
                var frames = 0
                while (frames < 4 && position + 6 <= length) {
                    if (bytes[position] != 0x0b.toByte() || bytes[position+1] != 0x77.toByte()) break
                    val bsid = (bytes[position+5].toInt() and 0xff) ushr 3
                    if (bsid > 16) break
                    bytes.copyInto(header, 0, position, position + 6)
                    val size = Ac3Util.parseAc3SyncframeSize(header)
                    if (size !in 6..MAX_FRAME || position + size > length) break
                    position += size
                    frames++
                }
                if (frames == 4) {
                    audioOffset = input.position + start
                    return true
                }
            }
            return false
        } finally { input.resetPeekPosition() }
    }

    override fun nextPacket(input: ExtractorInput): Packet? {
        if (input.position < audioOffset) input.skipFully((audioOffset - input.position).toInt())
        if (!reported) {
            StartupDiagnostics.updatePlayback("container recovery",
                "Raw AC-3/E-AC-3; skipped prefix=$audioOffset bytes. Frame index includes later gaps; missing audio cannot be restored.")
            reported = true
        }
        while (fill(input, 6)) {
            if (buffer[offset] != 0x0b.toByte() || buffer[offset + 1] != 0x77.toByte() ||
                (buffer[offset + 5].toInt() and 0xff) ushr 3 > 16) { offset++; continue }
            val size = Ac3Util.parseAc3SyncframeSize(buffer.copyOfRange(offset, offset + 6))
            if (size !in 6..MAX_FRAME) { offset++; continue }
            if (!fill(input, size)) return null
            val position = input.position - (end - offset)
            val bytes = buffer.copyOfRange(offset, offset + size)
            val info = runCatching { Ac3Util.parseAc3SyncframeInfo(ParsableBitArray(bytes.copyOf(maxOf(128, bytes.size)))) }.getOrNull()
            if (info == null || info.mimeType == null || info.sampleRate <= 0 || info.sampleCount <= 0 || info.channelCount !in 1..8) {
                offset++; continue
            }
            offset += size
            return Packet(position, bytes, info.sampleCount, info.sampleRate,
                Format.Builder().setSampleMimeType(info.mimeType).setSampleRate(info.sampleRate)
                    .setChannelCount(info.channelCount).setMaxInputSize(MAX_FRAME).build())
        }
        return null
    }
    private fun fill(input: ExtractorInput, needed: Int): Boolean {
        if (end - offset >= needed) return true
        if (offset > 0) { buffer.copyInto(buffer, 0, offset, end); end -= offset; offset = 0 }
        while (!eof && end < needed) {
            val n = input.read(buffer, end, buffer.size - end)
            if (n == -1) eof = true else if (n > 0) end += n else return false
        }
        return end >= needed
    }
    override fun resetReader() { offset = 0; end = 0; eof = false }

    private companion object {
        const val MAX_PREFIX = 1024 * 1024
        const val MAX_FRAME = 4096
    }
}
