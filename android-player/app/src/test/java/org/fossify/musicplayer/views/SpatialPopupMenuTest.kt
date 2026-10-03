/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListPopupWindow
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fossify.musicplayer.R
import org.fossify.musicplayer.activities.EqualizerActivity
import org.fossify.musicplayer.activities.SettingsActivity
import org.fossify.musicplayer.activities.SpatialPlayerActivity
import org.fossify.musicplayer.activities.StartupReportActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowListPopupWindow
import org.robolectric.shadows.ShadowPopupMenu
import java.io.File

/** Real framework popup rows, opened by the header button, without the playback service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SpatialPopupMenuTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val expectedTitles = listOf("Эквалайзер", "Настройки", "Диагностика")

    @Before fun before() = reset()
    @After fun after() = reset()

    private fun reset() {
        ShadowPopupMenu.getLatestPopupMenu()?.dismiss()
        context.getSharedPreferences("spatial_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        SpatialStyle.initialize(context)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    private fun player(theme: Int): SpatialPlayerActivity {
        // The real production menu is invoked below; only player/service startup is omitted.
        val controller = Robolectric.buildActivity(SpatialPlayerActivity::class.java)
        val activity = controller.get().apply { setTheme(theme) }
        SpatialPlayerActivity::class.java.getDeclaredMethod("buildInterface")
            .apply { isAccessible = true }.invoke(activity)
        controller.visible()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(851, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 393, 851)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return activity
    }

    private fun openMenu(activity: SpatialPlayerActivity): Pair<PopupMenu, ListPopupWindow> {
        val anchor = descendants(activity.findViewById(android.R.id.content)).single {
            it.contentDescription == activity.getString(R.string.queue_player_menu)
        }
        assertTrue("Header settings button must open the actual menu", anchor.performClick())
        shadowOf(Looper.getMainLooper()).idle()
        val menu = requireNotNull(ShadowPopupMenu.getLatestPopupMenu())
        val popup = requireNotNull(ShadowListPopupWindow.getLatestListPopupWindow())
        assertTrue(shadowOf(menu).isShowing)
        assertTrue(popup.isShowing)
        val root = requireNotNull(popup.listView).rootView
        root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(851, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        return menu to popup
    }

    private fun assertReadable(popup: ListPopupWindow) {
        val surface = (popup.background as ColorDrawable).color
        assertEquals("Popup surface must not come from the legacy dark theme", Color.rgb(248, 251, 253), surface)
        val list = requireNotNull(popup.listView)
        assertEquals(3, list.adapter.count)
        assertEquals("All menu rows must be visible", 3, list.childCount)
        val titles = descendants(list).filterIsInstance<TextView>()
            .filter { it.text.toString() in expectedTitles }.toList()
        assertEquals(expectedTitles, titles.map { it.text.toString() })
        titles.forEach { label ->
            assertEquals(View.VISIBLE, label.visibility)
            assertEquals(1f, label.alpha, 0f)
            assertTrue("${label.text}: foreground ${label.currentTextColor.toUInt().toString(16)} on ${surface.toUInt().toString(16)}",
                ColorUtils.calculateContrast(label.currentTextColor, surface) >= 4.5)
        }
    }

    private fun screenshot(popup: ListPopupWindow, name: String) {
        val root = requireNotNull(popup.listView).rootView
        assertTrue(root.width > 0 && root.height > 0)
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        File("build/reports/spatial-popup/$name.png").apply {
            parentFile?.mkdirs()
            outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
    }

    @Test fun menuTextIsReadableForEveryPastelWithLegacyAndSystemThemes() {
        val hosts = listOf(
            Triple("legacy-dark", R.style.AppTheme, "notnight"),
            Triple("system-light", org.fossify.commons.R.style.AppTheme_Base_System_Light, "notnight"),
            Triple("system-dark", org.fossify.commons.R.style.AppTheme_Base_System, "night")
        )
        hosts.forEach { (name, legacyTheme, nightMode) ->
            RuntimeEnvironment.setQualifiers("ru-rRU-w393dp-h851dp-port-$nightMode-mdpi")
            val activity = player(legacyTheme)
            SpatialStyle.themes.forEach { palette ->
                assertTrue(SpatialStyle.select(activity, palette.id))
                val (menu, popup) = openMenu(activity)
                try {
                    assertReadable(popup)
                    if (palette.id == "sky") screenshot(popup, "menu-$name")
                } finally {
                    menu.dismiss()
                }
            }
        }
    }

    @Test fun visibleMenuRowsStillLaunchEqualizerSettingsAndDiagnostics() {
        val activity = player(R.style.AppTheme)
        val targets = listOf(EqualizerActivity::class.java, SettingsActivity::class.java, StartupReportActivity::class.java)
        targets.forEachIndexed { index, target ->
            val (menu, popup) = openMenu(activity)
            try {
                val list = requireNotNull(popup.listView)
                val row = list.getChildAt(index)
                assertNotNull("The selected menu item must actually be displayed", row)
                assertTrue(list.performItemClick(row, index, list.adapter.getItemId(index)))
                val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
                assertEquals(target.name, intent.component?.className)
                assertNull("One click must start only one screen", shadowOf(activity).nextStartedActivity)
            } finally {
                menu.dismiss()
            }
        }
    }
}
