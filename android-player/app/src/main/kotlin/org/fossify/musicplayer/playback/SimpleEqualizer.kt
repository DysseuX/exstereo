/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.helpers.EQUALIZER_PRESET_CUSTOM
import org.fossify.musicplayer.helpers.EQUALIZER_PRESET_FLAT
import org.fossify.musicplayer.playback.player.SimpleMusicPlayer
import kotlin.math.ln
import kotlin.math.roundToInt

/** Immutable snapshots are shared with the audio thread; no preferences I/O in DSP. */
data class Equalizer30Settings(
    val levels: List<Int> = List(30) { 0 },
    val preset: Int = EQUALIZER_PRESET_FLAT,
    val legacyPending: Boolean = false
) {
    fun gainsDb() = FloatArray(30) { levels[it] / 100f }
}

@UnstableApi
object SimpleEqualizer {
    const val SETTINGS_KEY = "equalizer_30_v1"
    val frequencies = listOf(25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0,
        250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0,
        2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0)
    @Volatile var current = Equalizer30Settings()
        private set
    private var initialized = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val saved = context.getSharedPrefs().getString(SETTINGS_KEY, null)
        val loaded = saved?.let { runCatching {
            val value = Gson().fromJson(it, Equalizer30Settings::class.java)
            require(value.levels.size == 30)
            Equalizer30Settings(value.levels.map { level -> level.coerceIn(-1200, 1200) }, value.preset)
        }.getOrNull() }
        current = loaded ?: Equalizer30Settings(legacyPending = context.config.equalizerPreset != EQUALIZER_PRESET_FLAT)
        initialized = true
    }

    /** The hardware effect is used once, disabled, only to read the old curve. */
    fun setupEqualizer(context: Context, player: SimpleMusicPlayer) {
        initialize(context)
        migrateLegacy(context, player.getAudioSessionId())
    }

    fun migrateLegacy(context: Context, audioSessionId: Int = 0) {
        initialize(context)
        if (!current.legacyPending) return
        var legacy: Equalizer? = null
        try {
            val session = if (audioSessionId > 0) audioSessionId
                else (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            if (session <= 0) return
            legacy = Equalizer(0, session)
            legacy.enabled = false
            migrateLegacy(context, legacy)
        } catch (_: Exception) {
            // The old keys remain intact. A new device-independent curve is
            // available immediately; migration can be retried when audio starts.
        } finally {
            runCatching { legacy?.release() }
        }
    }

    @Synchronized internal fun migrateLegacy(context: Context, legacy: Equalizer) {
        if (!current.legacyPending) return
        val preset = context.config.equalizerPreset
        val range = legacy.bandLevelRange
        require(range.size >= 2 && legacy.numberOfBands > 0)
        val type = object : TypeToken<HashMap<Short, Int>>() {}.type
        val saved = if (preset == EQUALIZER_PRESET_CUSTOM) runCatching {
            Gson().fromJson<HashMap<Short, Int>>(context.config.equalizerBands, type)
        }.getOrNull() else null
        if (preset >= 0) {
            require(preset < legacy.numberOfPresets)
            legacy.usePreset(preset.toShort())
        }
        val points = (0 until legacy.numberOfBands).map { band ->
            val frequency = legacy.getCenterFreq(band.toShort()) / 1000.0
            require(frequency > 0)
            val gain = if (preset >= 0) legacy.getBandLevel(band.toShort()).toInt()
                else saved?.get(band.toShort())?.let {
                    (it.toLong() + range[0]).coerceIn(range[0].toLong(), range[1].toLong()).toInt()
                } ?: 0
            frequency to gain
        }.sortedBy { it.first }
        val migrated = frequencies.map { frequency ->
            val right = points.indexOfFirst { it.first >= frequency }
            when {
                right == 0 -> points.first().second
                right < 0 -> points.last().second
                else -> {
                    val left = points[right - 1]
                    val next = points[right]
                    val width = ln(next.first / left.first)
                    val position = if (width > 0) ln(frequency / left.first) / width else 0.0
                    (left.second + position * (next.second - left.second)).roundToInt()
                }
            }.coerceIn(-1200, 1200)
        }
        save(context, Equalizer30Settings(migrated, EQUALIZER_PRESET_CUSTOM))
    }

    @Synchronized fun setBand(context: Context, band: Int, millibels: Int) {
        initialize(context)
        require(band in 0 until 30)
        val values = current.levels.toMutableList()
        values[band] = millibels.coerceIn(-1200, 1200)
        save(context, Equalizer30Settings(values, EQUALIZER_PRESET_CUSTOM))
    }

    @Synchronized fun selectPreset(context: Context, preset: Int) {
        initialize(context)
        val curve = when (preset) {
            EQUALIZER_PRESET_FLAT -> List(30) { 0 }
            0 -> frequencies.map { if (it <= 125) 400 else if (it <= 250) 200 else 0 }
            1 -> frequencies.map { if (it in 500.0..4000.0) 300 else 0 }
            2 -> frequencies.map { if (it >= 8000) -400 else if (it >= 4000) -200 else 0 }
            else -> return
        }
        save(context, Equalizer30Settings(curve, preset))
    }

    fun resetToFlat(context: Context) = selectPreset(context, EQUALIZER_PRESET_FLAT)

    private fun save(context: Context, settings: Equalizer30Settings) {
        current = settings
        context.getSharedPrefs().edit().putString(SETTINGS_KEY, Gson().toJson(settings)).apply()
        // Keep the old device-specific keys as a migration backup. They are never
        // applied after a v1 curve has been saved and cannot overwrite 30 bands.
    }

    @Synchronized fun release() { initialized = false }
}
