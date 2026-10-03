/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fossify.musicplayer.R
import org.fossify.musicplayer.activities.SettingsActivity
import org.fossify.musicplayer.activities.SpatialPlayerActivity
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SpatialThemeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetBefore() = reset()
    @After fun resetAfter() = reset()

    private fun reset() {
        context.getSharedPreferences("spatial_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        SpatialStyle.initialize(context)
    }

    private fun settings(): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        SettingsActivity::class.java.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        controller.visible()
        return activity
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    @Test fun settingsSelectionPersistsAndRecolorsNewScreens() {
        val activity = settings()
        val oldRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val mint = descendants(oldRoot).first { it.contentDescription == "Мятная тема" }
        assertTrue(mint.performClick())
        assertEquals("mint", context.getSharedPreferences("spatial_appearance", Context.MODE_PRIVATE).getString("theme_id", null))
        assertEquals("mint", SpatialStyle.initialize(context).id)
        val reopened = settings()
        val root = reopened.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertEquals(SpatialStyle.background, (root.background as ColorDrawable).color)
        assertTrue(descendants(root).any { it.isSelected && it.contentDescription == "Мятная тема, выбрана" })
        val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertFalse(labels.any { it.contains("шрифт", true) || it.contains("цвет значка", true) })
        assertFalse(labels.contains(reopened.getString(org.fossify.commons.R.string.manage_shown_tabs)))
        root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(851, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 393, 851)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val image = Bitmap.createBitmap(393, 851, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        File("build/reports/spatial-themes/settings-mint.png").apply {
            parentFile?.mkdirs()
            outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
    }

    @Test fun allPastelThemesKeepBodyTextReadable() {
        assertEquals(6, SpatialStyle.themes.size)
        assertEquals(6, SpatialStyle.themes.map { it.id }.distinct().size)
        SpatialStyle.themes.forEach { theme ->
            listOf(theme.ink, theme.secondary).forEach { foreground ->
                listOf(theme.background, theme.panel, theme.card, theme.selected).forEach { surface ->
                    assertTrue("${theme.id} text contrast", ColorUtils.calculateContrast(foreground, surface) >= 4.5)
                }
            }
        }
    }

    @Test fun selectedPaletteReachesPlayerAndCompactHeaderKeepsAllActionsVisible() {
        SpatialStyle.select(context, "mint")
        fun player(): SpatialPlayerActivity {
            val controller = Robolectric.buildActivity(SpatialPlayerActivity::class.java)
            val activity = controller.get()
            activity.setTheme(R.style.AppTheme)
            SpatialPlayerActivity::class.java.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
            fun field(name: String) = SpatialPlayerActivity::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(activity)
            (field("titleView") as TextView).text = "Демонстрационный трек"
            (field("artistView") as TextView).text = "Пример интерфейса"
            (field("modeBadge") as TextView).text = "Стерео · Настроить"
            (field("emptyPlayer") as View).visibility = View.GONE
            controller.visible()
            return activity
        }
        fun render(activity: SpatialPlayerActivity, width: Int, height: Int, name: String): ViewGroup {
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertEquals(SpatialStyle.background, (root.background as ColorDrawable).color)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            File("build/reports/spatial-themes/$name.png").apply {
                parentFile?.mkdirs()
                outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            image.recycle()
            return root
        }
        render(player(), 393, 851, "player-mint")
        RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-mdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        val compact = player()
        val root = render(compact, 320, 640, "player-mint-320")
        val header = root.getChildAt(0) as ViewGroup
        val actions = descendants(header).filter { it.isClickable }.toList()
        assertEquals(3, actions.size)
        val rootLocation = IntArray(2).apply { root.getLocationInWindow(this) }
        var previousRight = 0
        actions.forEach { button ->
            val location = IntArray(2).apply { button.getLocationInWindow(this) }
            val left = location[0] - rootLocation[0]
            val right = left + button.width
            assertTrue("Header action keeps the approved 44px touch target", button.width >= 44 && button.height >= 44)
            assertTrue("Header action remains inside the screen", left >= previousRight && right <= 320)
            previousRight = right
        }
        val brand = descendants(header).filterIsInstance<TextView>().single { it.text == "exstereo" }
        assertEquals("Brand title must not wrap under the icons", 1, brand.lineCount)
    }

    @Test fun invalidSavedThemeFallsBackWithoutBreakingTheScreen() {
        context.getSharedPreferences("spatial_appearance", Context.MODE_PRIVATE).edit().putString("theme_id", "removed-theme").commit()
        assertEquals("sky", SpatialStyle.initialize(context).id)
        assertFalse(SpatialStyle.select(context, "unknown-theme"))
        assertEquals("sky", SpatialStyle.current.id)
    }

    @Test fun chooserUsesSavedThemeAndReportsSelectedIndex() {
        SpatialStyle.select(context, "lavender")
        val activity = settings()
        var selected = -1
        val dialog = showSpatialChoiceDialog(activity, "Предустановка", listOf("Ровно", "Своя"), 0) { selected = it }
        val radios = descendants(dialog.window!!.decorView).filterIsInstance<RadioButton>().toList()
        assertEquals(2, radios.size)
        assertEquals(SpatialStyle.ink, radios.last().currentTextColor)
        assertEquals(SpatialStyle.accent, radios.last().buttonTintList!!.defaultColor)
        radios.last().performClick()
        assertEquals(1, selected)
        assertFalse(dialog.isShowing)
    }
}
