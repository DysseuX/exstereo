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
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
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

/** Real settings controls, without starting playback or loading a native HRTF library. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SpatialAudioUiTest {
    private fun activity(width: Int = 393, height: Int = 851): SpatialAudioActivity {
        SpatialPreferences.update(SpatialSettings(mode = 0, virtualStereo = true))
        val controller = Robolectric.buildActivity(SpatialAudioActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        SpatialAudioActivity::class.java.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        controller.visible()
        layout(activity, width, height)
        return activity
    }

    private fun field(activity: SpatialAudioActivity, name: String) =
        SpatialAudioActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)!!

    private fun layout(activity: SpatialAudioActivity, width: Int = 393, height: Int = 851): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        // Include the real edge-to-edge padding, which is normally delivered
        // by Window rather than by this UI-only activity harness.
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

    private fun userStep(bar: SeekBar, right: Boolean = true) {
        val key = if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        assertTrue(bar.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key)))
    }

    private fun View.windowPosition() = IntArray(2).also { getLocationInWindow(it) }

    private fun scrollTo(content: View, target: View) {
        val scroll = children(content).filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, scroll.scrollY + target.windowPosition()[1] - scroll.windowPosition()[1] - 28)
    }

    private fun snapshot(content: View, name: String) {
        val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(image))
        val file = File("build/reports/spatial-controls/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun mirrorCheckboxAppliesBothSpeakerCoordinatesImmediately() {
        val activity = activity()
        val content = layout(activity)
        val checkbox = children(content).filterIsInstance<CheckBox>().first { it.text == "Зеркальное перемещение пары" }
        assertFalse(checkbox.isChecked)
        checkbox.performClick()
        assertTrue(SpatialPreferences.current.mirrorSpeakers)
        for (field in listOf("azimuth", "elevation", "distance")) {
            val before = SpatialPreferences.current.stereoSpeakers[0]
            userStep(field(activity, field) as SeekBar)
            val pair = SpatialPreferences.current.stereoSpeakers
            assertNotEquals(before, pair[0])
            assertEquals(pair[0].copy(azimuth = -pair[0].azimuth), pair[1])
        }
        checkbox.performClick()
        val right = SpatialPreferences.current.stereoSpeakers[1]
        userStep(field(activity, "azimuth") as SeekBar)
        assertEquals(right, SpatialPreferences.current.stereoSpeakers[1])
    }

    @Test fun twoSpeakerControlsUpdateImmediatelyAndKeepIndependentSurroundLayout() {
        val activity = activity()
        val selector = field(activity, "speakerSelector") as Spinner
        assertEquals(2, selector.count)
        val content = layout(activity)
        scrollTo(content, field(activity, "scene") as View)
        snapshot(content, "stereo")
        children(content).filterIsInstance<ScrollView>().first().scrollTo(0, 0)
        val originalSurround = SpatialPreferences.current.speakers
        userStep(field(activity, "azimuth") as SeekBar)
        userStep(field(activity, "elevation") as SeekBar)
        userStep(field(activity, "distance") as SeekBar)
        val stereo = SpatialPreferences.current.stereoSpeakers
        assertTrue(stereo[0].azimuth > -30f)
        assertTrue(stereo[0].elevation > 0f)
        assertTrue(stereo[0].distanceMeters > 1f)
        assertEquals(originalSurround, SpatialPreferences.current.speakers)

        activity.findViewById<View>(101).performClick()
        layout(activity)
        assertEquals(2, selector.count)
        assertEquals(stereo, SpatialPreferences.current.stereoSpeakers)
        activity.findViewById<View>(102).performClick()
        layout(activity)
        assertEquals(7, selector.count)
        selector.setSelection(6)
        layout(activity)
        userStep(field(activity, "distance") as SeekBar)
        assertTrue(SpatialPreferences.current.speakers[6].distanceMeters > 1f)
        assertEquals(stereo, SpatialPreferences.current.stereoSpeakers)
        activity.findViewById<View>(100).performClick()
        layout(activity)
        assertEquals(stereo[0].distanceMeters,
            .5f + (field(activity, "distance") as SeekBar).progress * .05f, .001f)
    }

    @Test fun manualHrtfControlsAreLiveAndResetWithoutChangingSpeakerPositions() {
        val activity = activity()
        val content = layout(activity)
        children(content).first { it.contentDescription == "Открыть настройку HRTF" }.performClick()
        layout(activity)
        val blend = children(content).filterIsInstance<SeekBar>().first { it.contentDescription == "Сила HRTF" }
        val yaw = children(content).filterIsInstance<SeekBar>().first { it.contentDescription == "Поворот слушателя" }
        val balance = children(content).filterIsInstance<SeekBar>().first { it.contentDescription == "Баланс HRTF" }
        userStep(blend, right = false); userStep(yaw); userStep(balance)
        assertTrue(SpatialPreferences.current.hrtfBlend < 1f)
        assertTrue(SpatialPreferences.current.listenerYaw > 0f)
        assertTrue(SpatialPreferences.current.hrtfBalanceDb > 0f)
        layout(activity)
        scrollTo(content, children(content).filterIsInstance<TextView>().first { it.text == "Ручная калибровка" })
        snapshot(content, "hrtf")
        val positions = SpatialPreferences.current.stereoSpeakers
        children(content).filterIsInstance<Button>().first { it.text == "Сбросить ручную калибровку" }.performClick()
        assertEquals(1f, SpatialPreferences.current.hrtfBlend)
        assertEquals(0f, SpatialPreferences.current.listenerYaw)
        assertEquals(0f, SpatialPreferences.current.hrtfBalanceDb)
        assertEquals(positions, SpatialPreferences.current.stereoSpeakers)
        assertEquals(100, blend.progress)
        assertEquals(180, yaw.progress)
        assertEquals(60, balance.progress)
    }

    @Test fun stereoBypassHidesVirtualControlsAndNoApplyButtonIsNeeded() {
        val activity = activity()
        val content = layout(activity)
        val scene = field(activity, "scene") as View
        val toggle = children(content).filterIsInstance<CheckBox>().first()
        assertTrue(scene.isShown)
        assertFalse(children(content).filterIsInstance<TextView>().any { it.text.contains("Применить расположение") })
        toggle.performClick()
        layout(activity)
        assertFalse(SpatialPreferences.current.virtualStereo)
        assertFalse(scene.isShown)
        activity.findViewById<View>(102).performClick()
        layout(activity)
        assertTrue(scene.isShown)
        assertEquals(7, (field(activity, "speakerSelector") as Spinner).count)

        // Export actual view pixels around the geometry controls for visual review.
        scrollTo(content, scene)
        snapshot(content, "surround")
    }

    @Test fun smallScreenAndLargeTextKeepManualControlsAndResetReachable() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-mdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = activity(320, 640)
        val content = layout(activity, 320, 640)
        children(content).first { it.contentDescription == "Открыть настройку HRTF" }.performClick()
        layout(activity, 320, 640)
        val reset = children(content).filterIsInstance<Button>().first { it.text == "Сбросить ручную калибровку" }
        val title = children(content).filterIsInstance<TextView>().first { it.text == "Звук в наушниках" }
        assertTrue("Large title must fit with system insets", title.layout.height <=
            title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
        val scroll = children(content).filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        assertTrue("Reset must be reachable by scrolling", reset.windowPosition()[1] >= scroll.windowPosition()[1])
        assertTrue("Reset must fit above the viewport bottom", reset.windowPosition()[1] + reset.height <=
            scroll.windowPosition()[1] + scroll.height)
        assertTrue("Font scaling must apply before creating the controls", reset.textSize >= 19f)
        val textLayout = reset.layout
        assertEquals(0, textLayout.getEllipsisCount(textLayout.lineCount - 1))
        assertTrue("All reset text lines must fit", textLayout.height <= reset.height - reset.compoundPaddingTop - reset.compoundPaddingBottom)
        for (bar in children(content).filterIsInstance<SeekBar>()) {
            if (!bar.isShown) continue
            assertTrue("Slider touch area stays usable", bar.width >= 200 && bar.height >= 48)
            assertTrue("Slider fits horizontally", bar.windowPosition()[0] >= 0 && bar.windowPosition()[0] + bar.width <= 320)
        }
        snapshot(content, "small-screen")
    }
}
