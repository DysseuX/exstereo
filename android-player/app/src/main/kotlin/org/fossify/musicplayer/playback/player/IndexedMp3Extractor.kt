/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.ByteArrayOutputStream

/** Keep Media3's ID3/gapless parsing, but index actual frames even without a Xing header. */
@UnstableApi
internal class IndexedMp3Extractor : IndexedRawAudioExtractor() {
    private val parser = Mp3Extractor().apply { disableSeeking() }
    private lateinit var format: Format
    private val pending = ByteArrayOutputStream(4096)
    private var ready: ByteArray? = null
    private val scratch = ByteArray(4096)
    init {
        parser.init(object : ExtractorOutput {
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) {
                    // Media3 considers audio/mpeg independently decodable and drops preroll.
                    // This private MIME retains Layer III's bit-reservoir dependencies in SampleQueue.
                    this@IndexedMp3Extractor.format = format.buildUpon().setSampleMimeType(MIME).build()
                }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val n = input.read(scratch, 0, minOf(length, scratch.size))
                    if (n > 0) pending.write(scratch, 0, n)
                    return n
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    check(length <= scratch.size)
                    data.readBytes(scratch, 0, length); pending.write(scratch, 0, length)
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    check(offset == 0 && size == pending.size())
                    ready = pending.toByteArray(); pending.reset()
                }
            }
        })
    }
    override fun sniff(input: ExtractorInput) = parser.sniff(input)
    override fun nextPacket(input: ExtractorInput): Packet? {
        val holder = PositionHolder()
        while (ready == null) {
            if (parser.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return null
        }
        val bytes = ready!!; ready = null
        val header = ParsableByteArray(bytes).readInt()
        return Packet(input.position - bytes.size, bytes,
            MpegAudioUtil.parseMpegAudioFrameSampleCount(header), format.sampleRate, format)
    }
    override fun resetReader() { ready = null; pending.reset(); parser.seek(0, 0) }
    override fun isIndependentPacket(packet: Packet): Boolean {
        val bytes = packet.bytes
        val header = ParsableByteArray(bytes).readInt()
        if ((header ushr 17) and 3 != 1) return true // Layer I/II have no bit reservoir.
        val side = 4 + if ((header ushr 16) and 1 == 0) 2 else 0
        if (bytes.size <= side + 1) return false
        return bytes[side].toInt() == 0 &&
            (((header ushr 19) and 3 != 3) || (bytes[side + 1].toInt() and 0x80 == 0))
    }
    companion object { const val MIME = "audio/x-exstereo-mpeg" }
}
