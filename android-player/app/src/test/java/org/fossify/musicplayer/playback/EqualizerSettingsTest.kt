/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.audiofx.Equalizer
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.musicplayer.R
import org.fossify.musicplayer.activities.EqualizerActivity
import org.fossify.musicplayer.databinding.ActivityEqualizerBinding
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.helpers.EQUALIZER_PRESET_CUSTOM
import org.fossify.musicplayer.helpers.EQUALIZER_PRESET_FLAT
import org.fossify.musicplayer.views.SpatialStyle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowEqualizer
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, shadows = [EqualizerSettingsTest.DeviceEqualizer::class], qualifiers = "ru-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EqualizerSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        SimpleEqualizer.release()
        context.getSharedPrefs().edit().clear().commit()
        DeviceEqualizer.supported = true
    }

    @Test fun newInstallationHasThirtyZeroGainsWithoutHardwareEqualizer() {
        SimpleEqualizer.initialize(context)
        assertEquals(30, SimpleEqualizer.current.levels.size)
        assertTrue(SimpleEqualizer.current.levels.all { it == 0 })
        assertFalse(SimpleEqualizer.current.legacyPending)
        assertEquals(EQUALIZER_PRESET_FLAT, SimpleEqualizer.current.preset)
    }

    @Test fun oldAsymmetricDeviceCurveIsInterpolatedAndRetainedAsBackup() {
        context.config.equalizerPreset = EQUALIZER_PRESET_CUSTOM
        val backup = "{\"0\":1500,\"2\":500}"
        context.config.equalizerBands = backup
        SimpleEqualizer.initialize(context)
        assertTrue(SimpleEqualizer.current.legacyPending)
        SimpleEqualizer.migrateLegacy(context, Equalizer(0, 42))
        assertEquals(300, SimpleEqualizer.current.levels[6]) // 100 Hz
        assertEquals(0, SimpleEqualizer.current.levels[16]) // absent 1 kHz is zero dB
        assertEquals(-700, SimpleEqualizer.current.levels[26]) // 10 kHz
        assertEquals(backup, context.config.equalizerBands)
        assertFalse(SimpleEqualizer.current.legacyPending)
        val migrated = SimpleEqualizer.current
        SimpleEqualizer.release()
        SimpleEqualizer.initialize(context)
        assertEquals(migrated, SimpleEqualizer.current)
    }

    @Test fun explicitDevicePresetZeroMigratesOnlyOnceAndNewCurveSurvivesRestart() {
        context.config.equalizerPreset = 0
        SimpleEqualizer.initialize(context)
        SimpleEqualizer.migrateLegacy(context, Equalizer(0, 42))
        assertEquals(-500, SimpleEqualizer.current.levels[6])
        assertEquals(100, SimpleEqualizer.current.levels[16])
        assertEquals(600, SimpleEqualizer.current.levels[26])
        SimpleEqualizer.setBand(context, 29, 1200)
        SimpleEqualizer.release()
        SimpleEqualizer.initialize(context)
        SimpleEqualizer.migrateLegacy(context, Equalizer(0, 43))
        assertEquals(1200, SimpleEqualizer.current.levels[29])
        SimpleEqualizer.resetToFlat(context)
        SimpleEqualizer.release()
        SimpleEqualizer.initialize(context)
        assertTrue(SimpleEqualizer.current.levels.all { it == 0 })
        assertEquals(EQUALIZER_PRESET_FLAT, SimpleEqualizer.current.preset)
    }

    @Test fun malformedSavedCurveFallsBackSafelyAndGainsAreBounded() {
        context.getSharedPrefs().edit().putString(SimpleEqualizer.SETTINGS_KEY, "{\"levels\":null}").commit()
        SimpleEqualizer.initialize(context)
        assertEquals(List(30) { 0 }, SimpleEqualizer.current.levels)
        SimpleEqualizer.setBand(context, 29, Int.MAX_VALUE)
        assertEquals(1200, SimpleEqualizer.current.levels[29])
    }

    @Test fun thirtyVisibleRowsKeyboardAndResetControlTheSavedDspSnapshot() {
        SpatialStyle.select(context, "mint")
        val activity = Robolectric.buildActivity(EqualizerActivity::class.java).get()
        activity.setTheme(R.style.AppTheme)
        val binding = EqualizerActivity::class.java.getDeclaredMethod("getBinding").apply { isAccessible = true }
            .invoke(activity) as ActivityEqualizerBinding
        activity.setContentView(binding.root)
        EqualizerActivity::class.java.getDeclaredMethod("initMediaPlayer").apply { isAccessible = true }.invoke(activity)
        assertEquals(30, binding.equalizerBandsHolder.childCount)
        val frequency = binding.equalizerBandsHolder.getChildAt(17).findViewById<TextView>(R.id.equalizer_band_label)
        assertTrue(frequency.text.toString().replace(',', '.').startsWith("1.25"))
        assertEquals(View.GONE, binding.equalizerUnavailable.visibility)
        val last = binding.equalizerBandsHolder.getChildAt(29).findViewById<SeekBar>(R.id.equalizer_band_seek_bar)
        assertEquals(12, last.progress)
        assertTrue(last.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(100, SimpleEqualizer.current.levels[29])
        assertEquals(1f, SimpleEqualizer.current.gainsDb()[29])
        assertEquals(EQUALIZER_PRESET_CUSTOM, SimpleEqualizer.current.preset)
        assertTrue(binding.equalizerReset.performClick())
        assertTrue(SimpleEqualizer.current.levels.all { it == 0 })
        assertEquals(12, last.progress)
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(851, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, 393, 851)
        val image = Bitmap.createBitmap(393, 851, Bitmap.Config.ARGB_8888)
        binding.root.draw(Canvas(image))
        File("build/reports/spatial-themes/equalizer-mint.png").apply {
            parentFile?.mkdirs()
            outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
    }

    @Test fun unavailableHardwareLeavesBackupIntactAndDoesNotDisableThirtyBandControls() {
        DeviceEqualizer.supported = false
        context.config.equalizerPreset = EQUALIZER_PRESET_CUSTOM
        val backup = "{\"0\":1800}"
        context.config.equalizerBands = backup
        SimpleEqualizer.initialize(context)
        SimpleEqualizer.migrateLegacy(context)
        assertTrue(SimpleEqualizer.current.legacyPending)
        assertEquals(backup, context.config.equalizerBands)
        val activity = Robolectric.buildActivity(EqualizerActivity::class.java).get()
        activity.setTheme(R.style.AppTheme)
        val binding = EqualizerActivity::class.java.getDeclaredMethod("getBinding").apply { isAccessible = true }
            .invoke(activity) as ActivityEqualizerBinding
        activity.setContentView(binding.root)
        EqualizerActivity::class.java.getDeclaredMethod("initMediaPlayer").apply { isAccessible = true }.invoke(activity)
        assertEquals(View.VISIBLE, binding.equalizerUnavailable.visibility)
        assertEquals(30, binding.equalizerBandsHolder.childCount)
        assertTrue(binding.equalizerPreset.isEnabled)
        val first = binding.equalizerBandsHolder.getChildAt(0).findViewById<SeekBar>(R.id.equalizer_band_seek_bar)
        assertTrue(first.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(100, SimpleEqualizer.current.levels[0])
        assertFalse(SimpleEqualizer.current.legacyPending)
        assertEquals(View.GONE, binding.equalizerUnavailable.visibility)
        assertEquals(backup, context.config.equalizerBands)
    }

    @Test fun skyPublicationScreenshotsShowAllThirtyBandsAcrossScrollPositions() {
        SpatialStyle.select(context, "sky")
        val controller = Robolectric.buildActivity(EqualizerActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        val binding = EqualizerActivity::class.java.getDeclaredMethod("getBinding").apply { isAccessible = true }
            .invoke(activity) as ActivityEqualizerBinding
        activity.setContentView(binding.root)
        EqualizerActivity::class.java.getDeclaredMethod("initMediaPlayer").apply { isAccessible = true }.invoke(activity)
        // The partial activity harness does not call onResume. Use the same
        // inherited toolbar setup and palette pass as the running screen.
        activity.setupTopAppBar(binding.equalizerAppbar, NavigationIcon.Arrow)
        EqualizerActivity::class.java.getDeclaredMethod("applySpatialPalette").apply { isAccessible = true }.invoke(activity)
        assertNotNull(binding.equalizerToolbar.navigationIcon)
        controller.visible()
        repeat(2) {
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(851, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, 393, 851)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(binding.root.isForceDarkAllowed)
        assertEquals(30, binding.equalizerBandsHolder.childCount)
        fun capture(name: String) {
            val bitmap = Bitmap.createBitmap(1179, 2553, Bitmap.Config.ARGB_8888)
            binding.root.draw(Canvas(bitmap).apply { scale(3f, 3f) })
            File("build/reports/equalizer30/$name.png").apply {
                parentFile?.mkdirs()
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        capture("10-equalizer")
        binding.equalizerNestedScrollview.scrollTo(0, binding.equalizerNestedScrollview.height * 2 / 3)
        assertTrue(binding.equalizerNestedScrollview.scrollY > 0)
        capture("10a-equalizer-middle")
        binding.equalizerNestedScrollview.scrollTo(0, binding.equalizerHolder.height)
        capture("10b-equalizer-highs")
        val last = binding.equalizerBandsHolder.getChildAt(29)
        val position = IntArray(2).also { last.getLocationInWindow(it) }
        val origin = IntArray(2).also { binding.root.getLocationInWindow(it) }
        val top = position[1] - origin[1]
        assertTrue("The final band must remain visible after scrolling", top >= 0 && top + last.height <= 851)
    }

    @Implements(Equalizer::class)
    class DeviceEqualizer : ShadowEqualizer() {
        companion object { var supported = true }
        private val bandLevels = shortArrayOf(-300, 400, -100)
        @Implementation fun getNumberOfBands(): Short = if (supported) 3 else 0
        @Implementation fun getBandLevelRange(): ShortArray = shortArrayOf(-1200, 1500)
        @Implementation fun getNumberOfPresets(): Short = 1
        @Implementation fun getCenterFreq(band: Short): Int = intArrayOf(100000, 1000000, 10000000)[band.toInt()]
        @Implementation fun getBandLevel(band: Short): Short = bandLevels[band.toInt()]
        @Implementation fun usePreset(preset: Short) {
            require(preset == 0.toShort())
            shortArrayOf(-500, 100, 600).copyInto(bandLevels)
        }
    }
}
