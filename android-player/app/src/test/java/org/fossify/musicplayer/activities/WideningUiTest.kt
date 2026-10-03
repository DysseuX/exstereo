/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.musicplayer.R
import org.fossify.musicplayer.spatial.SpatialPreferences
import org.fossify.musicplayer.spatial.SpatialSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Actual settings widgets; intentionally does not start playback or JNI. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WideningUiTest {
    private fun activity(width: Int = 393, height: Int = 851, algorithm: Int = 0): SpatialAudioActivity {
        SpatialPreferences.update(SpatialSettings(mode = 1, wideningAlgorithm = algorithm))
        val controller = Robolectric.buildActivity(SpatialAudioActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        SpatialAudioActivity::class.java.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        controller.visible()
        layout(activity, width, height)
        return activity
    }

    private fun layout(activity: SpatialAudioActivity, width: Int = 393, height: Int = 851): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(content, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 24)).build())
        repeat(2) {
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, width, height)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        return content
    }

    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(children(view.getChildAt(index)))
    }
    private fun control(content: View, name: String): View = children(content).first { it.contentDescription == name }
    private fun userStep(bar: SeekBar, right: Boolean = true) {
        val key = if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        assertTrue(bar.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key)))
    }
    private fun View.windowPosition() = IntArray(2).also { getLocationInWindow(it) }
    private fun scrollTo(content: View, target: View) {
        val scroll = children(content).filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, scroll.scrollY + target.windowPosition()[1] - scroll.windowPosition()[1] - 12)
    }
    private fun snapshot(content: View, name: String) {
        val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(image))
        val file = File("build/reports/widening-controls/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun algorithmsSwitchImmediatelyAndExposeOnlyTheirControls() {
        val activity = activity()
        val content = layout(activity)
        val selector = control(content, "Алгоритм расширения стереобазы") as Spinner
        val width = control(content, "Ширина стереобазы") as SeekBar
        val amount = control(content, "Доля обработки расширения") as SeekBar
        val low = control(content, "Расширение ниже раздела") as SeekBar
        assertEquals(11, selector.count)
        assertTrue(width.isShown)
        assertFalse(amount.isShown)
        assertFalse(low.isShown)
        userStep(width)
        val msWidth = SpatialPreferences.current.width
        val originalGeometry = SpatialPreferences.current.stereoSpeakers
        for (index in 1..10) {
            selector.setSelection(index)
            layout(activity)
            assertEquals(index, SpatialPreferences.current.wideningAlgorithm)
            assertFalse(width.isShown)
            assertTrue(amount.isShown)
            assertEquals(index in 4..5, low.isShown)
        }
        selector.setSelection(3)
        layout(activity)
        assertTrue((control(content, "Описание алгоритма расширения") as TextView).text.contains("боковая составляющая заменяется"))
        scrollTo(content, selector)
        snapshot(content, "ffmpeg-haas")
        selector.setSelection(0)
        layout(activity)
        assertEquals(msWidth, SpatialPreferences.current.width)
        assertEquals(originalGeometry, SpatialPreferences.current.stereoSpeakers)
        activity.findViewById<View>(100).performClick()
        layout(activity)
        assertFalse(selector.isShown)
        activity.findViewById<View>(102).performClick()
        layout(activity)
        assertFalse(selector.isShown)
    }

    @Test fun stereoWidenerParametersUpdateWithoutApplyOrPlaybackRestart() {
        val activity = activity(algorithm = 4)
        val content = layout(activity)
        val amount = control(content, "Доля обработки расширения") as SeekBar
        val low = control(content, "Расширение ниже раздела") as SeekBar
        val high = control(content, "Расширение выше раздела") as SeekBar
        val crossover = control(content, "Частота раздела расширения") as SeekBar
        val protection = control(content, "Защита атак") as Switch
        val preserving = control(content, "Сохранять амплитуду") as Switch
        userStep(amount); userStep(low); userStep(high); userStep(crossover)
        protection.performClick(); preserving.performClick()
        val settings = SpatialPreferences.current
        assertTrue(settings.wideningAmount > .5f)
        assertTrue(settings.widenerLow > 0f)
        assertTrue(settings.widenerHigh > .5f)
        assertTrue(settings.widenerCrossover > 1000f)
        assertFalse(settings.transientProtection)
        assertTrue(settings.widenerAmplitudePreserving)
        assertEquals(4, settings.wideningAlgorithm)
        assertFalse(children(content).filterIsInstance<TextView>().any { it.text == "Применить" })
        scrollTo(content, control(content, "Алгоритм расширения стереобазы"))
        snapshot(content, "stereowidener-allpass")
        scrollTo(content, low)
        snapshot(content, "stereowidener-options")
        (control(content, "Алгоритм расширения стереобазы") as Spinner).setSelection(5)
        layout(activity)
        assertEquals(settings.copy(wideningAlgorithm = 5), SpatialPreferences.current)
    }

    @Test fun smallScreenAndLargeTextKeepWideningControlsReadableAndTouchable() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-mdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = activity(320, 640, algorithm = 5)
        val content = layout(activity, 320, 640)
        val selector = control(content, "Алгоритм расширения стереобазы") as Spinner
        val selectedText = selector.selectedView as TextView
        assertEquals("Algorithm name should not truncate", 0,
            selectedText.layout.getEllipsisCount(selectedText.layout.lineCount - 1))
        for (name in listOf("Доля обработки расширения", "Расширение ниже раздела", "Расширение выше раздела", "Частота раздела расширения")) {
            val bar = control(content, name) as SeekBar
            assertTrue("Touch target for $name", bar.height >= 48 && bar.width >= 200)
            assertTrue("Slider stays within narrow screen", bar.windowPosition()[0] >= 0 && bar.windowPosition()[0] + bar.width <= 320)
        }
        val protection = control(content, "Защита атак") as Switch
        val preserving = control(content, "Сохранять амплитуду") as Switch
        for (toggle in listOf(protection, preserving)) {
            assertTrue("Switch touch target", toggle.height >= 48)
            assertTrue("Switch text must fit", toggle.layout.height <=
                toggle.height - toggle.compoundPaddingTop - toggle.compoundPaddingBottom)
        }
        scrollTo(content, selector)
        snapshot(content, "small-screen")
        scrollTo(content, protection)
        snapshot(content, "small-screen-options")
    }
}
