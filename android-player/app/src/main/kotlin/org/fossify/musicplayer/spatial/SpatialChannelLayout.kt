/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

/** Standard decoded WAVE layouts (Opus mapping family 1), never encoded Vorbis order. */
object SpatialChannelLayout {
    fun nativeChannels(channels: Int): Int {
        require(channels in 1..8) { "Supported speaker layouts have 1–8 channels" }
        return when (channels) { 3, 4, 5, 7 -> 8; else -> channels }
    }

    fun expand(samples: FloatArray, channels: Int): FloatArray {
        val outputChannels = nativeChannels(channels)
        require(samples.size % channels == 0)
        if (outputChannels == channels) return samples
        val result = FloatArray(samples.size / channels * 8)
        for (frame in 0 until samples.size / channels) {
            val src = frame * channels
            val dst = frame * 8
            result[dst] = samples[src]
            result[dst + 1] = samples[src + 1]
            if (channels == 4) { // quad: FL FR BL BR
                result[dst + 4] = samples[src + 2]
                result[dst + 5] = samples[src + 3]
            } else {
                result[dst + 2] = samples[src + 2]
                if (channels == 5) { // 5.0: FL FR FC BL BR
                    result[dst + 4] = samples[src + 3]
                    result[dst + 5] = samples[src + 4]
                } else if (channels == 7) { // 6.1: FL FR FC LFE BC SL SR
                    result[dst + 3] = samples[src + 3]
                    result[dst + 4] = samples[src + 4] * 0.70710677f
                    result[dst + 5] = samples[src + 4] * 0.70710677f
                    result[dst + 6] = samples[src + 5]
                    result[dst + 7] = samples[src + 6]
                }
            }
        }
        return result
    }
}
