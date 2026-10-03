/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage

/** A sparse, bounded index of real frame boundaries, not a bitrate estimate. */
@UnstableApi
internal class RawFrameIndex : SeekMap {
    private var times = LongArray(1024)
    private var positions = LongArray(1024)
    private var count = 0
    private var spacingUs = 250_000L
    private var duration = C.TIME_UNSET
    fun add(timeUs: Long, position: Long) {
        if (count > 0 && timeUs - times[count - 1] < spacingUs) return
        if (count == 65536) {
            for (i in 0 until count / 2) { times[i] = times[i * 2]; positions[i] = positions[i * 2] }
            count /= 2; spacingUs *= 2
        }
        if (count == times.size) { times = times.copyOf(count * 2); positions = positions.copyOf(count * 2) }
        times[count] = timeUs; positions[count] = position; count++
    }
    fun finish(durationUs: Long) { duration = durationUs }
    override fun isSeekable() = count > 0 && duration != C.TIME_UNSET
    override fun getDurationUs() = duration
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        // Decoder overlap/history is rebuilt before the requested audible time.
        val target = (timeUs - 100_000L).coerceIn(0L, (duration - 1).coerceAtLeast(0L))
        val i = floor(times, target)
        return SeekMap.SeekPoints(SeekPoint(times[i], positions[i]))
    }
    fun timeAt(position: Long): Long = times[floor(positions, position)]
    private fun floor(values: LongArray, value: Long): Int {
        var low = 0; var high = count - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (values[mid] <= value) low = mid + 1 else high = mid - 1
        }
        return high.coerceAtLeast(0)
    }
}

/** Progress is only shown while the player is buffering; release clears stale work. */
internal object RawSeekProgress {
    @Volatile private var percentage = -2L
    val label: String?
        get() = when (val current = percentage) {
            -2L -> null
            -1L -> AppLanguage.string(R.string.player_seek_preparing)
            else -> AppLanguage.string(R.string.player_seek_preparing_progress, current)
        }
    private var owner: Any? = null
    @Synchronized fun update(source: Any, position: Long, length: Long) {
        owner = source
        percentage = if (length > 0) (position * 100 / length).coerceIn(0, 100) else -1L
    }
    @Synchronized fun clear(source: Any) { if (owner === source) { owner = null; percentage = -2L } }
}

/** Two streaming passes over local raw audio: headers/index, then ordinary playback.
 * No complete PCM or compressed track is retained. Reads stay on Media3's loader.
 */
@UnstableApi
internal abstract class IndexedRawAudioExtractor : Extractor {
    protected data class Packet(val position: Long, val bytes: ByteArray, val samples: Int,
                                val clockRate: Int, val format: Format)
    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var index = RawFrameIndex()
    private var indexed = false
    private var formatted = false
    private var firstPosition = -1L
    private var baseUs = 0L
    private var samples = 0L
    private var clockRate = 0
    private var firstPlaybackPacket = true
    protected abstract fun nextPacket(input: ExtractorInput): Packet?
    protected abstract fun resetReader()
    protected open fun isIndependentPacket(packet: Packet) = true
    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }
    final override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        try {
            val packet = nextPacket(input)
            if (packet == null) {
                if (indexed) return Extractor.RESULT_END_OF_INPUT
                if (firstPosition < 0) throw ParserException.createForMalformedContainer(AppLanguage.string(R.string.player_raw_no_frames), null)
                index.finish(timeUs())
                output.seekMap(index)
                indexed = true
                RawSeekProgress.clear(this)
                baseUs = 0; samples = 0
                resetReader()
                seekPosition.position = firstPosition
                return Extractor.RESULT_SEEK
            }
            if (packet.samples <= 0 || packet.clockRate <= 0) throw ParserException.createForMalformedContainer(AppLanguage.string(R.string.player_raw_invalid_clock), null)
            if (clockRate == 0) clockRate = packet.clockRate
            if (clockRate != packet.clockRate) throw ParserException.createForUnsupportedContainerFeature(AppLanguage.string(R.string.player_raw_changing_rate))
            if (firstPosition < 0) firstPosition = packet.position
            if (!formatted) { track.format(packet.format); formatted = true }
            val time = timeUs()
            if (!indexed) {
                index.add(time, packet.position)
                RawSeekProgress.update(this, input.position, input.length)
            } else {
                track.sampleData(ParsableByteArray(packet.bytes), packet.bytes.size)
                val flags = if (firstPlaybackPacket || isIndependentPacket(packet)) C.BUFFER_FLAG_KEY_FRAME else 0
                track.sampleMetadata(time, flags, packet.bytes.size, 0, null)
                firstPlaybackPacket = false
            }
            samples += packet.samples
            return Extractor.RESULT_CONTINUE
        } catch (error: Exception) {
            RawSeekProgress.clear(this)
            throw error
        }
    }
    private fun timeUs() = baseUs + if (clockRate > 0) samples * 1_000_000 / clockRate else 0
    final override fun seek(position: Long, timeUs: Long) {
        resetReader()
        firstPlaybackPacket = true
        baseUs = if (indexed) index.timeAt(position) else 0
        samples = 0
        if (!indexed) { index = RawFrameIndex(); firstPosition = -1; clockRate = 0 }
    }
    final override fun release() { resetReader(); RawSeekProgress.clear(this) }
}
