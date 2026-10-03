/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.ogg.OggExtractor

/** RFC 7845 §4.6: decode at least 80 ms before an Opus seek target to rebuild state.
 * Vorbis also benefits from restoring its overlap. Media3 still trims at the user's target.
 */
@UnstableApi
internal class PrerollOggExtractor : Extractor {
    private val parser = OggExtractor()
    private fun preroll(timeUs: Long) = (timeUs - 80_000L).coerceAtLeast(0L)
    override fun sniff(input: ExtractorInput) = parser.sniff(input)
    override fun init(output: ExtractorOutput) {
        parser.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(object : SeekMap {
                override fun isSeekable() = seekMap.isSeekable
                override fun getDurationUs() = seekMap.durationUs
                override fun getSeekPoints(timeUs: Long) = seekMap.getSeekPoints(preroll(timeUs))
            })
        })
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = parser.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = parser.seek(position, preroll(timeUs))
    override fun release() = parser.release()
}
