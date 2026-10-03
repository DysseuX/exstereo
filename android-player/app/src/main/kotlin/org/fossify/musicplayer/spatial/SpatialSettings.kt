/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import android.content.Context
import android.content.SharedPreferences
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage
import kotlin.math.pow

data class SpeakerPosition(val azimuth: Float, val elevation: Float = 0f, val distanceMeters: Float = 1f) {
    fun normalized() = SpeakerPosition(
        azimuth.bounded(-180f, 180f, 0f), elevation.bounded(-60f, 60f, 0f),
        distanceMeters.bounded(0.5f, 5f, 1f)
    )
}

private fun Float.bounded(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback

data class SpatialSettings(
    val hrtf: String = "default",
    val mode: Int = 2,
    val width: Float = 1.25f,
    val surroundDb: Float = -6f,
    val lfeDb: Float = 0f,
    val speakers: List<SpeakerPosition> = defaultSpeakers(),
    val renderer: Int = RENDERER_STEAM,
    val upmixer: Int = UPMIX_FREESURROUND,
    val stereoSpeakers: List<SpeakerPosition> = defaultStereoSpeakers(),
    val virtualStereo: Boolean = true,
    val mirrorSpeakers: Boolean = false,
    val hrtfBlend: Float = 1f,
    val listenerYaw: Float = 0f,
    val listenerPitch: Float = 0f,
    val hrtfBalanceDb: Float = 0f,
    val hrtfGainDb: Float = 0f,
    val wideningAlgorithm: Int = 0,
    val wideningAmount: Float = 0.5f,
    val widenerLow: Float = 0f,
    val widenerHigh: Float = 0.5f,
    val widenerCrossover: Float = 1000f,
    val transientProtection: Boolean = true,
    val widenerAmplitudePreserving: Boolean = false,
    val bandLowWidth: Float = 1f,
    val bandMidWidth: Float = 1.25f,
    val bandHighWidth: Float = 1.5f,
    val bandLowCrossover: Float = 200f,
    val bandHighCrossover: Float = 3000f,
    val correlationGuard: Boolean = true,
    val paeAmbientWidth: Float = 1.5f,
    val paeAmbienceGainDb: Float = 0f,
    val sideSynthesisAmount: Float = .5f,
    val sideHighpass: Float = 200f,
    val shuffleAmount: Float = .5f,
    val shuffleFrequency: Float = 700f,
    val microCents: Float = 6f,
    val microDelayMs: Float = 12f,
    val roomEnabled: Boolean = false,
    val roomMix: Float = .15f,
    val roomSize: Float = 5f,
    val roomDamping: Float = .5f,
    val roomDecay: Float = .35f
) {
    val surroundGain: Float get() = 10f.pow(surroundDb / 20f)
    val lfeGain: Float get() = 10f.pow(lfeDb / 20f)

    /** Mirror across the scene's front/back axis; center has no partner. */
    fun moveSpeaker(index: Int, position: SpeakerPosition): SpatialSettings {
        val positions = (if (mode == 2) speakers else stereoSpeakers).toMutableList()
        if (index !in positions.indices) return this
        val next = position.normalized()
        positions[index] = next
        val partner = if (mode == 2) listOf(1, 0, -1, 4, 3, 6, 5).getOrElse(index) { -1 }
            else 1 - index
        if (mirrorSpeakers && partner in positions.indices) positions[partner] = next.copy(azimuth = -next.azimuth)
        return if (mode == 2) copy(speakers = positions) else copy(stereoSpeakers = positions)
    }

    // Steam's built-in profile has no SOFA file. FFmpeg must always get a real
    // profile; this choice is also reflected in the selector and saved state.
    fun withCompatibleHrtf(): SpatialSettings =
        if (renderer == RENDERER_SOFALIZER && hrtf == "default") copy(hrtf = "kemar") else this

    fun normalized(): SpatialSettings = copy(
        mode = mode.coerceIn(0, 2), renderer = renderer.coerceIn(0, 2), upmixer = upmixer.coerceIn(0, 2),
        width = width.bounded(0f, 2f, 1.25f), surroundDb = surroundDb.bounded(-30f, 0f, -6f),
        lfeDb = lfeDb.bounded(-30f, 0f, 0f),
        speakers = defaultSpeakers().mapIndexed { i, fallback -> (speakers.getOrNull(i) ?: fallback).normalized() },
        stereoSpeakers = defaultStereoSpeakers().mapIndexed { i, fallback -> (stereoSpeakers.getOrNull(i) ?: fallback).normalized() },
        hrtfBlend = hrtfBlend.bounded(0f, 1f, 1f), listenerYaw = listenerYaw.bounded(-180f, 180f, 0f),
        listenerPitch = listenerPitch.bounded(-60f, 60f, 0f),
        hrtfBalanceDb = hrtfBalanceDb.bounded(-6f, 6f, 0f), hrtfGainDb = hrtfGainDb.bounded(-12f, 6f, 0f),
        wideningAlgorithm = wideningAlgorithm.coerceIn(0, 10),
        wideningAmount = wideningAmount.bounded(0f, 1f, 0.5f),
        widenerLow = widenerLow.bounded(0f, 1f, 0f), widenerHigh = widenerHigh.bounded(0f, 1f, 0.5f),
        widenerCrossover = widenerCrossover.bounded(200f, 4000f, 1000f),
        bandLowWidth = bandLowWidth.bounded(0f, 2f, 1f),
        bandMidWidth = bandMidWidth.bounded(0f, 2f, 1.25f),
        bandHighWidth = bandHighWidth.bounded(0f, 2f, 1.5f),
        bandLowCrossover = bandLowCrossover.bounded(80f, 1000f, 200f),
        bandHighCrossover = bandHighCrossover.bounded(1500f, 8000f, 3000f),
        paeAmbientWidth = paeAmbientWidth.bounded(0f, 3f, 1.5f),
        paeAmbienceGainDb = paeAmbienceGainDb.bounded(-12f, 6f, 0f),
        sideSynthesisAmount = sideSynthesisAmount.bounded(0f, 1f, .5f),
        sideHighpass = sideHighpass.bounded(20f, 1000f, 200f),
        shuffleAmount = shuffleAmount.bounded(-1f, 1f, .5f),
        shuffleFrequency = shuffleFrequency.bounded(100f, 2000f, 700f),
        microCents = microCents.bounded(0f, 20f, 6f), microDelayMs = microDelayMs.bounded(5f, 30f, 12f),
        roomMix = roomMix.bounded(0f, 1f, .15f), roomSize = roomSize.bounded(2f, 12f, 5f),
        roomDamping = roomDamping.bounded(0f, 1f, .5f), roomDecay = roomDecay.bounded(.1f, 1.5f, .35f)
    ).withCompatibleHrtf()

    /** JNI snapshot v3: the first 44 values remain compatible with v2. */
    internal fun nativeParameters(): FloatArray = FloatArray(63).also { values ->
        values[0] = mode.toFloat()
        values[1] = width
        values[2] = surroundGain
        values[3] = lfeGain
        values[4] = if (virtualStereo) 1f else 0f
        values[5] = hrtfBlend
        values[6] = listenerYaw
        values[7] = listenerPitch
        values[8] = hrtfBalanceDb
        values[9] = hrtfGainDb
        (speakers + stereoSpeakers).forEachIndexed { i, position ->
            values[10 + i * 3] = position.azimuth
            values[11 + i * 3] = position.elevation
            values[12 + i * 3] = position.distanceMeters
        }
        values[37] = wideningAlgorithm.toFloat()
        values[38] = wideningAmount
        values[39] = widenerLow
        values[40] = widenerHigh
        values[41] = widenerCrossover
        values[42] = if (transientProtection) 1f else 0f
        values[43] = if (widenerAmplitudePreserving) 1f else 0f
        values[44] = bandLowWidth
        values[45] = bandMidWidth
        values[46] = bandHighWidth
        values[47] = bandLowCrossover
        values[48] = bandHighCrossover
        values[49] = if (correlationGuard) 1f else 0f
        values[50] = paeAmbientWidth
        values[51] = paeAmbienceGainDb
        values[52] = sideSynthesisAmount
        values[53] = sideHighpass
        values[54] = shuffleAmount
        values[55] = shuffleFrequency
        values[56] = microCents
        values[57] = microDelayMs
        values[58] = if (roomEnabled) 1f else 0f
        values[59] = roomMix
        values[60] = roomSize
        values[61] = roomDamping
        values[62] = roomDecay
    }

    companion object {
        const val RENDERER_STEAM = 0
        const val RENDERER_OPENAL = 1
        const val RENDERER_SOFALIZER = 2
        const val UPMIX_FREESURROUND = 0
        const val UPMIX_PAE_PAD = 1
        const val UPMIX_FFMPEG = 2
        val rendererNames get() = listOf(
            AppLanguage.string(R.string.audio_renderer_steam), AppLanguage.string(R.string.audio_renderer_openal),
            AppLanguage.string(R.string.audio_renderer_sofalizer)
        )
        val upmixerNames get() = listOf(
            AppLanguage.string(R.string.audio_upmixer_freesurround), AppLanguage.string(R.string.audio_upmixer_pae),
            AppLanguage.string(R.string.audio_upmixer_ffmpeg)
        )
        val wideningAlgorithmNames get() = listOf(
            AppLanguage.string(R.string.audio_algorithm_ms), AppLanguage.string(R.string.audio_algorithm_extrastereo),
            AppLanguage.string(R.string.audio_algorithm_stereowiden), AppLanguage.string(R.string.audio_algorithm_haas),
            AppLanguage.string(R.string.audio_algorithm_allpass), AppLanguage.string(R.string.audio_algorithm_velvet),
            AppLanguage.string(R.string.audio_algorithm_multiband), AppLanguage.string(R.string.audio_algorithm_pae),
            AppLanguage.string(R.string.audio_algorithm_side), AppLanguage.string(R.string.audio_algorithm_shuffler),
            AppLanguage.string(R.string.audio_algorithm_micropitch)
        )
        // WAVE order excluding LFE: front L/R/C, back L/R, side L/R.
        val speakerNames get() = listOf(
            AppLanguage.string(R.string.audio_speaker_fl), AppLanguage.string(R.string.audio_speaker_fr),
            AppLanguage.string(R.string.audio_speaker_c), AppLanguage.string(R.string.audio_speaker_bl),
            AppLanguage.string(R.string.audio_speaker_br), AppLanguage.string(R.string.audio_speaker_sl),
            AppLanguage.string(R.string.audio_speaker_sr)
        )
        val stereoSpeakerNames get() = listOf(
            AppLanguage.string(R.string.audio_speaker_l), AppLanguage.string(R.string.audio_speaker_r)
        )
        fun defaultStereoSpeakers() = listOf(SpeakerPosition(-30f), SpeakerPosition(30f))
        fun defaultSpeakers() = listOf(-30f, 30f, 0f, -150f, 150f, -90f, 90f)
            .map { SpeakerPosition(it) }
    }
}

/** Immutable snapshots cross from the UI to the audio thread. */
object SpatialPreferences {
    @Volatile var current = SpatialSettings()
        private set
    private var preferences: SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("spatial_audio", Context.MODE_PRIVATE)
        preferences = prefs
        val defaults = SpatialSettings()
        current = SpatialSettings(
            hrtf = prefs.getString("hrtf", "default").orEmpty().let { key ->
                if (HrtfProfiles.entries(context).any { it.id == key }) key else "default"
            },
            mode = prefs.getInt("mode", defaults.mode).coerceIn(0, 2),
            width = prefs.getFloat("width", defaults.width).coerceIn(0f, 2f),
            surroundDb = prefs.getFloat("surround_db", defaults.surroundDb).coerceIn(-30f, 0f),
            lfeDb = prefs.getFloat("lfe_db", defaults.lfeDb).coerceIn(-30f, 0f),
            renderer = prefs.getInt("renderer", defaults.renderer).takeIf { it in 0..2 } ?: defaults.renderer,
            upmixer = prefs.getInt("upmixer", defaults.upmixer).takeIf { it in 0..2 } ?: defaults.upmixer,
            speakers = defaults.speakers.mapIndexed { index, position ->
                SpeakerPosition(prefs.getFloat("azimuth_$index", position.azimuth),
                    prefs.getFloat("elevation_$index", position.elevation), prefs.getFloat("distance_$index", 1f))
            },
            stereoSpeakers = defaults.stereoSpeakers.mapIndexed { index, position ->
                SpeakerPosition(prefs.getFloat("stereo_azimuth_$index", position.azimuth),
                    prefs.getFloat("stereo_elevation_$index", position.elevation),
                    prefs.getFloat("stereo_distance_$index", 1f))
            },
            virtualStereo = prefs.getBoolean("virtual_stereo", defaults.virtualStereo),
            mirrorSpeakers = prefs.getBoolean("mirror_speakers", defaults.mirrorSpeakers),
            hrtfBlend = prefs.getFloat("hrtf_blend", defaults.hrtfBlend),
            listenerYaw = prefs.getFloat("listener_yaw", defaults.listenerYaw),
            listenerPitch = prefs.getFloat("listener_pitch", defaults.listenerPitch),
            hrtfBalanceDb = prefs.getFloat("hrtf_balance_db", defaults.hrtfBalanceDb),
            hrtfGainDb = prefs.getFloat("hrtf_gain_db", defaults.hrtfGainDb),
            wideningAlgorithm = prefs.getInt("widening_algorithm", defaults.wideningAlgorithm),
            wideningAmount = prefs.getFloat("widening_amount", defaults.wideningAmount),
            widenerLow = prefs.getFloat("widener_low", defaults.widenerLow),
            widenerHigh = prefs.getFloat("widener_high", defaults.widenerHigh),
            widenerCrossover = prefs.getFloat("widener_crossover", defaults.widenerCrossover),
            transientProtection = prefs.getBoolean("transient_protection", defaults.transientProtection),
            widenerAmplitudePreserving = prefs.getBoolean("widener_amplitude", defaults.widenerAmplitudePreserving),
            bandLowWidth = prefs.getFloat("band_low_width", defaults.bandLowWidth),
            bandMidWidth = prefs.getFloat("band_mid_width", defaults.bandMidWidth),
            bandHighWidth = prefs.getFloat("band_high_width", defaults.bandHighWidth),
            bandLowCrossover = prefs.getFloat("band_low_crossover", defaults.bandLowCrossover),
            bandHighCrossover = prefs.getFloat("band_high_crossover", defaults.bandHighCrossover),
            correlationGuard = prefs.getBoolean("correlation_guard", defaults.correlationGuard),
            paeAmbientWidth = prefs.getFloat("pae_ambient_width", defaults.paeAmbientWidth),
            paeAmbienceGainDb = prefs.getFloat("pae_ambience_gain_db", defaults.paeAmbienceGainDb),
            sideSynthesisAmount = prefs.getFloat("side_synthesis_amount", defaults.sideSynthesisAmount),
            sideHighpass = prefs.getFloat("side_highpass", defaults.sideHighpass),
            shuffleAmount = prefs.getFloat("shuffle_amount", defaults.shuffleAmount),
            shuffleFrequency = prefs.getFloat("shuffle_frequency", defaults.shuffleFrequency),
            microCents = prefs.getFloat("micro_cents", defaults.microCents),
            microDelayMs = prefs.getFloat("micro_delay_ms", defaults.microDelayMs),
            roomEnabled = prefs.getBoolean("room_enabled", defaults.roomEnabled),
            roomMix = prefs.getFloat("room_mix", defaults.roomMix), roomSize = prefs.getFloat("room_size", defaults.roomSize),
            roomDamping = prefs.getFloat("room_damping", defaults.roomDamping), roomDecay = prefs.getFloat("room_decay", defaults.roomDecay)
        ).normalized()
    }

    @Synchronized fun update(updated: SpatialSettings) {
        val settings = updated.normalized()
        current = settings
        preferences?.edit()?.apply {
            putString("hrtf", settings.hrtf)
            putInt("mode", settings.mode)
            putInt("renderer", settings.renderer)
            putInt("upmixer", settings.upmixer)
            putFloat("width", settings.width)
            putFloat("surround_db", settings.surroundDb)
            putFloat("lfe_db", settings.lfeDb)
            putBoolean("virtual_stereo", settings.virtualStereo)
            putBoolean("mirror_speakers", settings.mirrorSpeakers)
            putFloat("hrtf_blend", settings.hrtfBlend)
            putFloat("listener_yaw", settings.listenerYaw)
            putFloat("listener_pitch", settings.listenerPitch)
            putFloat("hrtf_balance_db", settings.hrtfBalanceDb)
            putFloat("hrtf_gain_db", settings.hrtfGainDb)
            putInt("widening_algorithm", settings.wideningAlgorithm)
            putFloat("widening_amount", settings.wideningAmount)
            putFloat("widener_low", settings.widenerLow)
            putFloat("widener_high", settings.widenerHigh)
            putFloat("widener_crossover", settings.widenerCrossover)
            putBoolean("transient_protection", settings.transientProtection)
            putBoolean("widener_amplitude", settings.widenerAmplitudePreserving)
            putFloat("band_low_width", settings.bandLowWidth)
            putFloat("band_mid_width", settings.bandMidWidth)
            putFloat("band_high_width", settings.bandHighWidth)
            putFloat("band_low_crossover", settings.bandLowCrossover)
            putFloat("band_high_crossover", settings.bandHighCrossover)
            putBoolean("correlation_guard", settings.correlationGuard)
            putFloat("pae_ambient_width", settings.paeAmbientWidth)
            putFloat("pae_ambience_gain_db", settings.paeAmbienceGainDb)
            putFloat("side_synthesis_amount", settings.sideSynthesisAmount)
            putFloat("side_highpass", settings.sideHighpass)
            putFloat("shuffle_amount", settings.shuffleAmount)
            putFloat("shuffle_frequency", settings.shuffleFrequency)
            putFloat("micro_cents", settings.microCents)
            putFloat("micro_delay_ms", settings.microDelayMs)
            putBoolean("room_enabled", settings.roomEnabled)
            putFloat("room_mix", settings.roomMix)
            putFloat("room_size", settings.roomSize)
            putFloat("room_damping", settings.roomDamping)
            putFloat("room_decay", settings.roomDecay)
            settings.speakers.forEachIndexed { i, speaker ->
                putFloat("azimuth_$i", speaker.azimuth)
                putFloat("elevation_$i", speaker.elevation)
                putFloat("distance_$i", speaker.distanceMeters)
            }
            settings.stereoSpeakers.forEachIndexed { i, speaker ->
                putFloat("stereo_azimuth_$i", speaker.azimuth)
                putFloat("stereo_elevation_$i", speaker.elevation)
                putFloat("stereo_distance_$i", speaker.distanceMeters)
            }
        }?.apply()
    }
}
