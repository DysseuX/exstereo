/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import androidx.core.graphics.ColorUtils
import androidx.viewpager.widget.ViewPager
import org.fossify.musicplayer.R
import org.fossify.musicplayer.activities.SpatialPlayerActivity
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

/** Real Android view rendering with fixture metadata, without a media service or emulator. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SpatialUiTest {
    private fun field(activity: SpatialPlayerActivity, name: String): Any =
        SpatialPlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)!!

    private fun set(activity: SpatialPlayerActivity, name: String, value: Any?) =
        SpatialPlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)

    private fun call(activity: SpatialPlayerActivity, name: String) =
        SpatialPlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun player(): SpatialPlayerActivity {
        // Only construct its UI: playback/permission scanning stays outside this visual test.
        val controller = Robolectric.buildActivity(SpatialPlayerActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        call(activity, "buildInterface")
        controller.visible()
        return activity
    }

    private fun layout(activity: SpatialPlayerActivity, width: Int = 393, height: Int = 851): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        repeat(2) {
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, width, height)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        return content
    }

    private fun snapshot(view: View, name: String) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val file = File("build/reports/sky-ui/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun selectedExstereoIconRendersWithItsLauncherBackground() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("exstereo", context.getString(R.string.spatial_app_name))
        val icon = context.getDrawable(R.mipmap.exstereo_launcher)!!
        val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 256, 256)
        icon.draw(Canvas(image))
        val file = File("build/reports/sky-ui/exstereo-icon.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun threePagesKeepNavigationAndRenderWithReadableMetadata() {
        val activity = player()
        val pager = field(activity, "pager") as ViewPager
        assertEquals(3, pager.adapter!!.count)
        assertEquals(1, pager.currentItem)
        (field(activity, "titleView") as TextView).text = "Там, где свет"
        (field(activity, "artistView") as TextView).text = "Северный берег · Тихие волны"
        (field(activity, "modeBadge") as TextView).text = "Виртуальный 7.1  ·  Настроить"
        (field(activity, "emptyPlayer") as View).visibility = View.GONE
        (field(activity, "positionView") as TextView).text = "1:24"
        (field(activity, "durationView") as TextView).text = "4:08"
        val seek = field(activity, "seekBar") as SpatialSeekBar
        seek.isEnabled = true; seek.max = 248; seek.progress = 84
        (field(activity, "playButton") as SpatialPlayButton).setPlaying(true)
        val root = layout(activity)
        snapshot(root, "player")
        val button = field(activity, "playButton") as View
        assertTrue("Transport must remain on screen", button.getLocationInWindowCompat()[1] + button.height <= 851)
        assertEquals(48, seek.thumb.intrinsicWidth)

        val tabs = field(activity, "pageButtons") as List<*>
        val rowClass = Class.forName("org.fossify.musicplayer.activities.SpatialPlayerActivity\$Row")
        val rowConstructor = rowClass.declaredConstructors.first { it.parameterCount == 7 }.apply { isAccessible = true }
        val noop: () -> Unit = {}
        val rows = listOf("Там, где свет", "Тихие волны", "Ночной город", "После дождя", "Ближе к небу")
            .mapIndexed { index, name -> rowConstructor.newInstance(name, "Северный берег", index == 0, null, false, "4:08", noop) }
        val compactQueueAdapter = field(activity, "playerQueueAdapter")
        compactQueueAdapter.javaClass.getDeclaredMethod("submit", List::class.java).apply { isAccessible = true }.invoke(compactQueueAdapter, rows)
        layout(activity)
        val compactList = descendants(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
            .single { it.contentDescription == "Треки очереди" }
        assertTrue(compactList.height > 400)
        assertEquals(5, compactList.adapter!!.itemCount)
        val first = compactList.getChildAt(0) as LinearLayout
        assertEquals(3, first.childCount)
        assertEquals("Северный берег", (first.getChildAt(0) as TextView).text.toString())
        assertEquals("Там, где свет", (first.getChildAt(1) as TextView).text.toString())
        assertEquals("4:08", (first.getChildAt(2) as TextView).text.toString())
        assertEquals(44, first.height)
        assertTrue(first.getChildAt(2).right <= compactList.width)
        snapshot(root, "player-compact-queue")
        val longRows = (0 until 24).map { index -> rowConstructor.newInstance(
            "Очень длинное название песни $index", "Исполнитель с длинным именем", index == 0, null, false, "4:08", noop) }
        compactQueueAdapter.javaClass.getDeclaredMethod("submit", List::class.java).apply { isAccessible = true }.invoke(compactQueueAdapter, longRows)
        layout(activity)
        for (index in 0 until compactList.childCount) {
            val row = compactList.getChildAt(index) as LinearLayout
            val time = row.getChildAt(2) as TextView
            assertTrue("Duration stays inside each recycled row", time.right <= row.width - row.paddingRight)
            assertTrue("Duration has room for its complete text", time.width >= time.paint.measureText(time.text.toString()))
            assertTrue("Metadata cannot overlap duration", row.getChildAt(1).right <= time.left)
        }
        snapshot(root, "player-long-queue-scrollbar")
        val queueAdapter = field(activity, "queueAdapter")
        queueAdapter.javaClass.getDeclaredMethod("submit", List::class.java).apply { isAccessible = true }.invoke(queueAdapter, rows)
        (field(activity, "queueStatus") as TextView).text = "В очереди: 5"
        (tabs[0] as View).performClick()
        assertEquals(0, pager.currentItem)
        // Finish the smooth tap transition before taking a static screenshot.
        pager.setCurrentItem(1, false)
        pager.setCurrentItem(0, false)
        layout(activity)
        assertEquals(0, pager.currentItem)
        assertFalse("Player is clipped outside the queue page", button.getGlobalVisibleRect(Rect()))
        assertEquals("No separate mini-player takes space above tabs", 3, (root as ViewGroup).childCount)
        assertEquals(pager.bottom, (tabs[0] as View).parent.let { it as View }.top)
        snapshot(root, "queue")
        val queueViews = descendants(root).toList()
        assertTrue(queueViews.any { it.contentDescription == activity.getString(R.string.queue_add) })
        assertTrue(queueViews.any { it.contentDescription == activity.getString(R.string.queue_remove) })
        queueViews.filterIsInstance<Button>().single { it.text == activity.getString(R.string.spatial_player_library) }.performClick()
        assertEquals("Library does not replace the current queue tab", 0, pager.currentItem)
        assertEquals("The library button opens the complete library",
            org.fossify.musicplayer.activities.MainActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
        pager.setCurrentItem(0, false)
        assertEquals(pager.getLocationInWindowCompat()[0],
            ((field(activity, "queueStatus") as View).parent as View).getLocationInWindowCompat()[0])

        set(activity, "permissionGranted", true)
        set(activity, "tracksByFolder", sortedMapOf(
            "/Music/Избранное" to emptyList<org.fossify.musicplayer.models.Track>(),
            "/Music/Тихие волны" to emptyList(), "/Music/Ночной город" to emptyList(),
            "/Music/Opus 7.1" to emptyList()))
        call(activity, "showFolders")
        (tabs[2] as View).performClick()
        assertEquals(2, pager.currentItem)
        pager.setCurrentItem(1, false)
        pager.setCurrentItem(2, false)
        layout(activity)
        assertEquals(2, pager.currentItem)
        assertFalse("Folders must not contain playback controls", button.getGlobalVisibleRect(Rect()))
        assertEquals(pager.bottom, (tabs[2] as View).parent.let { it as View }.top)
        snapshot(root, "folders")
        assertEquals(pager.getLocationInWindowCompat()[0],
            ((field(activity, "folderTitle") as View).parent as View).getLocationInWindowCompat()[0])
        (tabs[1] as View).performClick()
        assertEquals(1, pager.currentItem)
        // End the paused smooth scroll before inspecting the viewport, as above.
        pager.setCurrentItem(2, false)
        pager.setCurrentItem(1, false)
        layout(activity)
        assertTrue("Playback controls are visible on the central page", button.getGlobalVisibleRect(Rect()))
    }

    @Test fun smallScreenAndLargeTextKeepSeekAndButtonsReachable() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-mdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        val activity = player()
        (field(activity, "titleView") as TextView).text = "Очень длинное название музыкальной композиции"
        (field(activity, "artistView") as TextView).text = "Исполнитель с длинным именем"
        (field(activity, "modeBadge") as TextView).text = "Виртуальный 7.1  ·  Настроить"
        (field(activity, "emptyPlayer") as View).visibility = View.GONE
        val root = layout(activity, 320, 640)
        val seek = field(activity, "seekBar") as SpatialSeekBar
        assertTrue(seek.width >= 240)
        assertTrue(seek.height >= 48)
        assertEquals(64, (field(activity, "playButton") as View).width)
        val play = field(activity, "playButton") as View
        assertTrue("Play stays above tabs without scrolling", play.getLocationInWindowCompat()[1] + play.height <= 640 - 48)
        assertFalse(generateSequence(play.parent as? View) { it.parent as? View }.any { it is ScrollView })
        assertEquals(2.6f, (field(activity, "artistView") as TextView).textSize - (field(activity, "titleView") as TextView).textSize, .05f)
        assertTrue("Font scale is preserved", (field(activity, "titleView") as TextView).textSize >= 20f)
        snapshot(root, "small-screen")
    }

    @Test fun textContrastSurvivesAllBlueSurfaces() {
        for (surface in listOf(SpatialStyle.background, SpatialStyle.panel, SpatialStyle.card, SpatialStyle.selected)) {
            assertTrue(ColorUtils.calculateContrast(SpatialStyle.ink, surface) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(SpatialStyle.secondary, surface) >= 4.5)
        }
    }

    private fun View.getLocationInWindowCompat() = IntArray(2).also { getLocationInWindow(it) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
