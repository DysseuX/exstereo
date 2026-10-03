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

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdvancedWideningUiTest {
    private val groups = mapOf(
        6 to listOf("Ширина низкой полосы", "Ширина средней полосы", "Ширина высокой полосы", "Нижняя частота раздела", "Верхняя частота раздела"),
        7 to listOf("Ширина окружения", "Уровень окружения"),
        8 to listOf("Уровень синтезированного Side", "Нижняя граница синтеза Side"),
        9 to listOf("Сила Shuffler", "Частота Shuffler"),
        10 to listOf("Расстройка MicroPitch", "Задержка MicroPitch")
    )
    private fun activity(width: Int = 393, height: Int = 851): SpatialAudioActivity {
        SpatialPreferences.update(SpatialSettings(mode = 1, wideningAlgorithm = 6))
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
    private fun control(content: View, name: String) = children(content).first { it.contentDescription == name }
    private fun userStep(bar: SeekBar) {
        assertTrue(bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
    }
    private fun View.windowPosition() = IntArray(2).also { getLocationInWindow(it) }
    private fun scrollTo(content: View, target: View) {
        val scroll = children(content).filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, scroll.scrollY + target.windowPosition()[1] - scroll.windowPosition()[1] - 12)
    }
    private fun snapshot(content: View, name: String) {
        val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(image))
        val file = File("build/reports/advanced-widening-controls/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun advancedAlgorithmsHaveSeparateLiveControlsAndRetainTheirValues() {
        val activity = activity()
        val content = layout(activity)
        val selector = control(content, "Алгоритм расширения стереобазы") as Spinner
        val originalGeometry = SpatialPreferences.current.stereoSpeakers
        for ((algorithm, names) in groups) {
            selector.setSelection(algorithm)
            layout(activity)
            assertEquals(algorithm, SpatialPreferences.current.wideningAlgorithm)
            assertFalse("StereoWidener controls must not leak into advanced algorithms", control(content, "Расширение ниже раздела").isShown)
            for ((other, controls) in groups) for (name in controls)
                assertEquals("Visibility for $name", algorithm == other, control(content, name).isShown)
            names.forEach { userStep(control(content, it) as SeekBar) }
        }
        val changed = SpatialPreferences.current
        assertTrue(changed.bandLowWidth > 1f)
        assertTrue(changed.bandMidWidth > 1.25f)
        assertTrue(changed.bandHighWidth > 1.5f)
        assertTrue(changed.bandLowCrossover > 200f)
        assertTrue(changed.bandHighCrossover > 3000f)
        assertTrue(changed.paeAmbientWidth > 1.5f)
        assertTrue(changed.paeAmbienceGainDb > 0f)
        assertTrue(changed.sideSynthesisAmount > .5f)
        assertTrue(changed.sideHighpass > 200f)
        assertTrue(changed.shuffleAmount > .5f)
        assertTrue(changed.shuffleFrequency > 700f)
        assertTrue(changed.microCents > 6f)
        assertTrue(changed.microDelayMs > 12f)
        assertEquals(originalGeometry, changed.stereoSpeakers)
        selector.setSelection(6)
        layout(activity)
        (control(content, "Защита корреляции") as Switch).performClick()
        assertFalse(SpatialPreferences.current.correlationGuard)
        assertEquals(changed.copy(wideningAlgorithm = 6, correlationGuard = false), SpatialPreferences.current)
        scrollTo(content, selector)
        snapshot(content, "multiband")
        selector.setSelection(7)
        layout(activity)
        scrollTo(content, selector)
        snapshot(content, "pae-ambience")
    }

    @Test fun roomIsIndependentAndAppliesLiveInEveryListeningMode() {
        val activity = activity()
        val content = layout(activity)
        control(content, "Открыть настройки комнаты").performClick()
        layout(activity)
        val enabled = control(content, "Включить комнату") as Switch
        enabled.performClick()
        val names = listOf("Доля комнаты", "Минимальный размер комнаты", "Поглощение высоких частот", "Время затухания")
        names.forEach { userStep(control(content, it) as SeekBar) }
        val settings = SpatialPreferences.current
        assertTrue(settings.roomEnabled)
        assertTrue(settings.roomMix > .15f)
        assertTrue(settings.roomSize > 5f)
        assertTrue(settings.roomDamping > .5f)
        assertTrue(settings.roomDecay > .35f)
        for (mode in 0..2) {
            activity.findViewById<View>(100 + mode).performClick()
            layout(activity)
            assertTrue(enabled.isShown)
            names.forEach { assertTrue(control(content, it).isShown) }
            assertEquals(settings.copy(mode = mode), SpatialPreferences.current)
        }
        activity.findViewById<View>(100).performClick()
        layout(activity)
        children(content).filterIsInstance<android.widget.CheckBox>().first().performClick()
        assertFalse(SpatialPreferences.current.virtualStereo)
        assertTrue(enabled.isShown)
        assertTrue(SpatialPreferences.current.roomEnabled)
        scrollTo(content, control(content, "Свернуть настройки комнаты"))
        snapshot(content, "room")
        enabled.performClick()
        assertFalse(SpatialPreferences.current.roomEnabled)
        assertEquals(settings.roomMix, SpatialPreferences.current.roomMix)
    }

    @Test fun advancedControlsStayReadableAt320DpAnd130PercentFont() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-mdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = activity(320, 640)
        val content = layout(activity, 320, 640)
        val selector = control(content, "Алгоритм расширения стереобазы") as Spinner
        for ((algorithm, names) in groups) {
            selector.setSelection(algorithm)
            layout(activity, 320, 640)
            val selectedText = selector.selectedView as TextView
            assertEquals(0, selectedText.layout.getEllipsisCount(selectedText.layout.lineCount - 1))
            for (name in names) {
                val bar = control(content, name) as SeekBar
                assertTrue("Touch target: $name", bar.height >= 48 && bar.width >= 200)
                assertTrue("Within screen: $name", bar.windowPosition()[0] >= 0 && bar.windowPosition()[0] + bar.width <= 320)
            }
        }
        scrollTo(content, selector)
        snapshot(content, "small-screen-micropitch")
        control(content, "Открыть настройки комнаты").performClick()
        layout(activity, 320, 640)
        for (name in listOf("Доля комнаты", "Минимальный размер комнаты", "Поглощение высоких частот", "Время затухания")) {
            val bar = control(content, name) as SeekBar
            assertTrue(bar.height >= 48 && bar.width >= 200)
            val group = bar.parent as ViewGroup
            val title = group.getChildAt(group.indexOfChild(bar) - 1) as TextView
            assertTrue("Slider label should wrap without clipping", title.layout.height <= title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
        }
        scrollTo(content, control(content, "Свернуть настройки комнаты"))
        snapshot(content, "small-screen-room")
    }
}
