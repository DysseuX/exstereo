/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ts.AdtsExtractor
import java.io.EOFException

/** ADTS frame clocks are exact for VBR too; byte-rate estimates are not. */
@UnstableApi
internal class IndexedAdtsExtractor : IndexedRawAudioExtractor() {
    private val header = ByteArray(7)
    override fun sniff(input: ExtractorInput) = AdtsExtractor().sniff(input)
    override fun resetReader() = Unit
    override fun nextPacket(input: ExtractorInput): Packet? = try {
        readPacket(input)
    } catch (_: EOFException) {
        // As with the standard reader, a truncated last frame is not playable.
        null
    }
    private fun readPacket(input: ExtractorInput): Packet? {
        while (true) {
            input.resetPeekPosition()
            if (!input.peekFully(header, 0, 7, true)) return null
            fun b(i: Int) = header[i].toInt() and 255
            if (b(0) == 0x49 && b(1) == 0x44 && b(2) == 0x33) {
                val tag = ByteArray(10)
                input.resetPeekPosition(); input.peekFully(tag, 0, 10)
                var size = 0
                for (i in 6..9) {
                    if (tag[i].toInt() and 128 != 0) throw ParserException.createForMalformedContainer("Invalid ID3 size", null)
                    size = (size shl 7) or tag[i].toInt()
                }
                input.skipFully(10 + size); continue
            }
            if (b(0) != 255 || b(1) and 0xf6 != 0xf0 || b(2) ushr 2 and 15 > 12) {
                input.skipFully(1); continue
            }
            val size = ((b(3) and 3) shl 11) or (b(4) shl 3) or (b(5) ushr 5)
            val headerSize = if (b(1) and 1 == 1) 7 else 9
            if (size <= headerSize) { input.skipFully(1); continue }
            val config = AacUtil.buildAudioSpecificConfig((b(2) ushr 6) + 1,
                (b(2) ushr 2) and 15, ((b(2) and 1) shl 2) or (b(3) ushr 6))
            val parsed = AacUtil.parseAudioSpecificConfig(config)
            val position = input.position
            // Retain ADTS framing: FFmpeg also needs it for CRC/multiple raw data blocks.
            val bytes = ByteArray(size)
            input.readFully(bytes, 0, size)
            return Packet(position, bytes, 1024 * ((b(6) and 3) + 1), parsed.sampleRateHz,
                Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(parsed.sampleRateHz)
                    .setChannelCount(parsed.channelCount).setInitializationData(listOf(config))
                    .setMaxInputSize(8192).build())
        }
    }
}
