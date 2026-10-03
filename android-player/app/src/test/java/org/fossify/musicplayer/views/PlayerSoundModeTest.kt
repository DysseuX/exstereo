/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import org.fossify.musicplayer.R
import org.fossify.musicplayer.activities.SpatialAudioActivity
import org.fossify.musicplayer.activities.SpatialPlayerActivity
import org.fossify.musicplayer.spatial.SpatialPreferences
import org.fossify.musicplayer.spatial.SpatialSettings
import org.junit.After
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
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w360dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerSoundModeTest {
    @After fun reset() {
        SpatialPreferences.update(SpatialSettings())
        SpatialStyle.select(RuntimeEnvironment.getApplication(), "sky")
    }

    private fun call(activity: Activity, name: String) = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun field(activity: Activity, name: String) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)!!

    private fun player(): SpatialPlayerActivity {
        val controller = Robolectric.buildActivity(SpatialPlayerActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        call(activity, "buildInterface")
        controller.visible()
        (field(activity, "emptyPlayer") as View).visibility = View.GONE
        (field(activity, "artistView") as TextView).text = "Lana Del Rey"
        (field(activity, "titleView") as TextView).text = "Evening Waves — Extended Edition"
        return activity
    }

    private fun layout(activity: Activity, width: Int = 360, height: Int = 720): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return root
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    private fun position(view: View) = IntArray(2).also { view.getLocationInWindow(it) }

    private fun snapshot(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/player-ui-fix/$name.png").apply {
            parentFile?.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    @Test fun storedModeAppearsBeforePlaybackAndRealModeChoicesRefreshTheHeader() {
        val original = SpatialSettings(mode = 2, width = 1.25f)
        SpatialPreferences.update(original)
        val player = player()
        assertEquals(original, SpatialPreferences.current)
        val label = field(player, "soundModeLabel") as TextView
        val button = field(player, "soundModeButton") as View
        assertEquals("7.1", label.text.toString())
        assertTrue(button.performClick())
        assertEquals(SpatialAudioActivity::class.java.name, shadowOf(player).nextStartedActivity.component?.className)
        assertEquals(original, SpatialPreferences.current)

        val controller = Robolectric.buildActivity(SpatialAudioActivity::class.java)
        val sound = controller.get().apply { setTheme(R.style.AppTheme) }
        call(sound, "buildInterface")
        controller.visible()
        assertTrue(sound.findViewById<RadioButton>(102).isChecked)
        for ((mode, caption) in listOf("2.0", "Шире", "7.1").withIndex()) {
            val choice = sound.findViewById<RadioButton>(100 + mode)
            choice.performClick()
            layout(sound)
            assertEquals(mode, SpatialPreferences.current.mode)
            assertTrue(choice.isChecked)
            call(player, "refreshSoundMode")
            assertEquals(caption, label.text.toString())
            assertTrue(button.contentDescription.contains("Выбрать режим звука"))
            snapshot(layout(player), "mode-$mode")
        }
    }

    @Test fun transportAndTabsStayAnchoredWhileTimeGetsItsOwnRow() {
        val player = player()
        val rowsClass = Class.forName("org.fossify.musicplayer.activities.SpatialPlayerActivity\$Row")
        val constructor = rowsClass.declaredConstructors.first { it.parameterCount == 7 }.apply { isAccessible = true }
        val action: () -> Unit = {}
        val rows = (0 until 20).map { constructor.newInstance("Песня $it", "Исполнитель", it == 0, null, false, "4:08", action) }
        val adapter = field(player, "playerQueueAdapter")
        adapter.javaClass.getDeclaredMethod("submit", List::class.java).apply { isAccessible = true }.invoke(adapter, rows)
        val root = layout(player)
        val queue = descendants(root).filterIsInstance<RecyclerView>().single { it.contentDescription == "Треки очереди" }
        val frame = queue.parent as View
        assertArrayEquals(intArrayOf(0, 57), position(frame))
        assertEquals(398, frame.height)
        val scrollbar = descendants(root).filterIsInstance<QueueScrollbar>().single()
        assertArrayEquals(intArrayOf(316, 66), position(scrollbar))
        assertEquals(371, scrollbar.height)
        for ((name, coordinates) in mapOf(
            "soundModeButton" to intArrayOf(256, 2), "artwork" to intArrayOf(14, 468),
            "artistView" to intArrayOf(74, 468), "titleView" to intArrayOf(74, 493),
            "playButton" to intArrayOf(148, 598), "shuffleButton" to intArrayOf(16, 607),
            "repeatButton" to intArrayOf(300, 607), "positionView" to intArrayOf(18, 574)
        )) assertArrayEquals(name, coordinates, position(field(player, name) as View))
        val seek = field(player, "seekBar") as View
        assertEquals(546, position(seek)[1] + seek.height / 2)
        assertTrue(seek.height >= 48)
        val tabs = field(player, "pageButtons") as List<*>
        assertEquals(672, position(tabs[1] as View)[1])
        snapshot(root, "player-layout")
    }

    @Test fun fullThumbNeverIntersectsTimeAtEitherEndWithLongDurationsAndLargeText() {
        for ((width, height, scale) in listOf(Triple(360, 720, 1f), Triple(320, 640, 1.3f))) {
            RuntimeEnvironment.setQualifiers("ru-rRU-w${width}dp-h${height}dp-port-mdpi")
            RuntimeEnvironment.setFontScale(scale)
            val player = player()
            val seek = field(player, "seekBar") as SpatialSeekBar
            val elapsed = field(player, "positionView") as TextView
            val duration = field(player, "durationView") as TextView
            val play = field(player, "playButton") as View
            seek.isEnabled = true; seek.max = 5852
            duration.text = "1:37:32"
            for (progress in listOf(0, 91, 5852)) {
                seek.progress = progress
                elapsed.text = if (progress == 0) "0:00" else if (progress == 91) "1:31" else "1:37:32"
                val root = layout(player, width, height)
                for (label in listOf(elapsed, duration)) {
                    assertTrue("The entire thumb ends before the time row", position(label)[1] >= position(seek)[1] + seek.height + 4)
                    assertTrue("Long time strings fit", label.paint.measureText(label.text.toString()) <= label.width)
                    assertTrue("Time does not overlap transport", position(label)[1] + label.height + 4 <= position(play)[1])
                }
                snapshot(root, "timeline-$width-$progress")
            }
        }
    }

    @Test fun headerTextAndSolidIconsStayReadableAcrossPastelsAndSystemThemes() {
        for (night in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("ru-rRU-w360dp-h720dp-port-$night-mdpi")
            for (palette in SpatialStyle.themes) {
                SpatialStyle.select(RuntimeEnvironment.getApplication(), palette.id)
                val player = player()
                val root = layout(player) as ViewGroup
                assertFalse("Automatic darkening must not recolor the pastel UI", root.isForceDarkAllowed)
                val header = root.getChildAt(0) as ViewGroup
                val labels = descendants(header).filterIsInstance<TextView>().toList()
                assertEquals(2, labels.size)
                for (label in labels) {
                    assertEquals(palette.ink, label.currentTextColor)
                    assertTrue(ColorUtils.calculateContrast(label.currentTextColor, palette.background) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(label.currentTextColor, palette.selected) >= 4.5)
                }
                val brand = labels.single { it.text == "exstereo" }
                assertEquals(Gravity.CENTER_VERTICAL, brand.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                val icons = descendants(header).filterIsInstance<ImageView>().filter { it != header.getChildAt(0) }.toList()
                assertEquals(3, icons.size)
                for (icon in icons) {
                    val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                    val originalBounds = Rect(icon.drawable.bounds)
                    icon.drawable.setBounds(0, 0, 24, 24)
                    icon.drawable.draw(Canvas(bitmap))
                    icon.drawable.bounds = originalBounds
                    val pixels = IntArray(24 * 24)
                    bitmap.getPixels(pixels, 0, 24, 0, 0, 24, 24)
                    assertTrue("Icon has solid, dark strokes instead of faded system artwork",
                        pixels.count { Color.alpha(it) >= 250 && ColorUtils.calculateContrast(it or Color.BLACK, palette.panel) >= 4.5 } > 15)
                    bitmap.recycle()
                }
                if (palette.id == "sky") snapshot(root, "header-$night")
            }
        }
    }

    @Test fun smallScreenAndLargeTextKeepModeAndTransportTargetsUsable() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-mdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        SpatialPreferences.update(SpatialSettings(mode = 1))
        val player = player()
        val root = layout(player, 320, 640)
        for (name in listOf("soundModeButton", "shuffleButton", "repeatButton", "playButton")) {
            val view = field(player, name) as View
            assertTrue(view.width >= 44 && view.height >= 44)
            assertTrue(position(view)[0] >= 0 && position(view)[0] + view.width <= 320)
        }
        val label = field(player, "soundModeLabel") as TextView
        assertTrue(label.paint.measureText(label.text.toString()) <= label.width)
        assertTrue(label.layout.height <= label.height)
        val play = field(player, "playButton") as View
        assertEquals(640 - 48 - 10, position(play)[1] + play.height)
        snapshot(root, "small-screen")
    }
}
