/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import android.content.Context
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage

object NativeSpatialEngine {
    init { System.loadLibrary("spatial_audio") }
    external fun create(sampleRate: Int, sofaPath: String): Long
    external fun createConfigured(
        sampleRate: Int, sofaPath: String, renderer: Int, upmixer: Int,
        azimuths: FloatArray, elevations: FloatArray
    ): Long
    external fun createLive(sampleRate: Int, sofaPath: String, renderer: Int, upmixer: Int, parameters: FloatArray): Long

    /** Called on the playback/validation worker; may copy a bundled SOFA. */
    fun createForSettings(sampleRate: Int, context: Context, snapshot: SpatialSettings): Long {
        val settings = snapshot.normalized()
        val sofa = if (settings.renderer == SpatialSettings.RENDERER_OPENAL) ""
            else HrtfProfiles.path(context, settings.hrtf)
        check(settings.renderer != SpatialSettings.RENDERER_SOFALIZER || sofa.isNotEmpty()) {
            AppLanguage.string(R.string.audio_sofalizer_needs_sofa)
        }
        return createLive(sampleRate, sofa, settings.renderer, settings.upmixer, settings.nativeParameters())
    }
    external fun latencyFrames(handle: Long): Int
    external fun process(
        handle: Long, input: FloatArray, channels: Int, mode: Int, width: Float,
        surroundGain: Float, lfeGain: Float, azimuths: FloatArray, elevations: FloatArray
    ): FloatArray
    external fun processLive(handle: Long, input: FloatArray, channels: Int, parameters: FloatArray): FloatArray
    external fun setEqualizer(handle: Long, gainsDb: FloatArray)
    external fun reset(handle: Long)
    external fun release(handle: Long)
}
