/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.musicplayer.R
import org.fossify.musicplayer.databinding.ActivityEqualizerBinding
import org.fossify.musicplayer.databinding.EqualizerBandBinding
import org.fossify.musicplayer.helpers.EQUALIZER_PRESET_FLAT
import org.fossify.musicplayer.playback.SimpleEqualizer
import org.fossify.musicplayer.views.SpatialStyle
import org.fossify.musicplayer.views.showSpatialChoiceDialog
import java.text.DecimalFormat
import kotlin.math.roundToInt

/** Controls the 30-band native EQ. */
class EqualizerActivity : SimpleActivity() {
    private val bandViews = ArrayList<EqualizerBandBinding>()
    private val binding by viewBinding(ActivityEqualizerBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpatialStyle.initialize(this)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.equalizerNestedScrollview))
        setupMaterialScrollListener(binding.equalizerNestedScrollview, binding.equalizerAppbar)
        initMediaPlayer()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.equalizerAppbar, NavigationIcon.Arrow)
        SpatialStyle.initialize(this)
        refreshControls()
        applySpatialPalette()
    }

    private fun initMediaPlayer() {
        SimpleEqualizer.initialize(this)
        SimpleEqualizer.migrateLegacy(this)
        binding.equalizerBandsHolder.removeAllViews()
        bandViews.clear()
        SimpleEqualizer.frequencies.forEachIndexed { band, frequency ->
            val row = EqualizerBandBinding.inflate(layoutInflater, binding.equalizerBandsHolder, false)
            binding.equalizerBandsHolder.addView(row.root)
            bandViews += row
            row.equalizerBandLabel.text = formatFrequency(frequency)
            row.equalizerBandSeekBar.apply {
                max = 24
                keyProgressIncrement = 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        SimpleEqualizer.setBand(this@EqualizerActivity, band, (progress - 12) * 100)
                        refreshControls()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
            }
        }
        binding.equalizerPreset.setOnClickListener {
            val presets = listOf(EQUALIZER_PRESET_FLAT, 0, 1, 2)
            showSpatialChoiceDialog(this, getString(R.string.equalizer), presets.map(::presetName),
                presets.indexOf(SimpleEqualizer.current.preset)) { index ->
                SimpleEqualizer.selectPreset(this, presets[index])
                refreshControls()
            }
        }
        binding.equalizerReset.setOnClickListener {
            SimpleEqualizer.resetToFlat(this)
            refreshControls()
        }
        refreshControls()
        applySpatialPalette()
    }

    private fun refreshControls() {
        val settings = SimpleEqualizer.current
        binding.equalizerPreset.text = presetName(settings.preset)
        binding.equalizerUnavailable.visibility = if (settings.legacyPending) View.VISIBLE else View.GONE
        bandViews.forEachIndexed { band, row ->
            val gain = settings.levels[band] / 100.0
            val number = (if (gain > 0) "+" else "") + DecimalFormat("0.#").format(gain)
            row.equalizerBandSeekBar.progress = (gain + 12).roundToInt()
            row.equalizerBandGain.text = getString(R.string.equalizer_gain, number)
            row.equalizerBandSeekBar.contentDescription = "${row.equalizerBandLabel.text}, ${row.equalizerBandGain.text}"
        }
    }

    private fun presetName(preset: Int) = getString(when (preset) {
        EQUALIZER_PRESET_FLAT -> R.string.equalizer_flat
        0 -> R.string.equalizer_bass
        1 -> R.string.equalizer_voice
        2 -> R.string.equalizer_soft_highs
        else -> org.fossify.commons.R.string.custom
    })

    private fun formatFrequency(value: Double): String {
        val scaled = if (value >= 1000) value / 1000 else value
        return getString(if (value >= 1000) R.string.equalizer_khz else R.string.equalizer_hz,
            DecimalFormat("0.##").format(scaled))
    }

    @Suppress("DEPRECATION")
    private fun applySpatialPalette() {
        if (android.os.Build.VERSION.SDK_INT >= 29) binding.root.isForceDarkAllowed = false
        binding.root.setBackgroundColor(SpatialStyle.background)
        binding.equalizerHolder.setBackgroundColor(SpatialStyle.background)
        binding.equalizerAppbar.setBackgroundColor(SpatialStyle.background)
        binding.equalizerToolbar.apply {
            title = getString(R.string.equalizer_30_title)
            setBackgroundColor(SpatialStyle.background)
            setTitleTextColor(SpatialStyle.ink)
            navigationIcon?.mutate()?.setTint(SpatialStyle.ink)
        }
        fun recolor(view: View) {
            if (view is TextView) view.setTextColor(SpatialStyle.ink)
            if (view is ViewGroup) repeat(view.childCount) { recolor(view.getChildAt(it)) }
        }
        recolor(binding.equalizerHolder)
        binding.equalizerDescription.setTextColor(SpatialStyle.secondary)
        binding.equalizerUnavailable.setTextColor(SpatialStyle.secondary)
        bandViews.forEach { row ->
            row.equalizerBandSeekBar.apply {
                progressTintList = ColorStateList.valueOf(SpatialStyle.accent)
                thumbTintList = ColorStateList.valueOf(SpatialStyle.accent)
                progressBackgroundTintList = ColorStateList.valueOf(SpatialStyle.line)
            }
        }
        listOf(binding.equalizerPreset, binding.equalizerReset).forEach {
            it.backgroundTintList = null
            it.background = SpatialStyle.ripple(this, SpatialStyle.selected, 20)
            it.setTextColor(SpatialStyle.ink)
        }
        window.statusBarColor = SpatialStyle.background
        window.navigationBarColor = SpatialStyle.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}
