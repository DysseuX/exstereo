/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/** Bound realtime HRTF/DSP cost; preserve 44.1/48 kHz and resample hi-res to 48 kHz. */
@UnstableApi
class SpatialInputResampler private constructor(private val sonic: SonicAudioProcessor) : AudioProcessor by sonic {
    constructor() : this(SonicAudioProcessor())

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        sonic.setOutputSampleRateHz(if (inputAudioFormat.sampleRate > 48000) 48000 else SonicAudioProcessor.SAMPLE_RATE_NO_CHANGE)
        return sonic.configure(inputAudioFormat)
    }

    // Kotlin delegation does not forward Java interface default methods.
    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) = sonic.flush(streamMetadata)
    @Deprecated("Use flush(StreamMetadata)")
    override fun flush() = sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        sonic.getDurationAfterProcessorApplied(durationUs)
}
