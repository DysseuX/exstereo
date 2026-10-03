/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import org.fossify.commons.databinding.BottomTablayoutItemBinding
import org.fossify.commons.views.MySearchMenu
import org.fossify.musicplayer.R
import org.fossify.musicplayer.adapters.ViewPagerAdapter
import org.fossify.musicplayer.databases.SongsDatabase
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.helpers.*
import org.fossify.musicplayer.models.Playlist
import org.fossify.musicplayer.views.LibraryStyle
import org.fossify.musicplayer.views.SpatialStyle
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.Executor

/** Tests library adapters, search and menus without starting playback or scanning. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var database: SongsDatabase? = null

    @Before fun reset() {
        context.config.showTabs = ALL_TABS_MASK
        context.config.lastUsedViewPagerPage = 0
        SpatialStyle.select(context, "sky")
    }

    @After fun finish() {
        ShadowDialog.getLatestDialog()?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        database?.close()
        SongsDatabase.destroyInstance()
        SpatialStyle.select(context, "sky")
    }

    private fun call(activity: MainActivity, method: String) =
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)

    private fun library(withFragments: Boolean = false, width: Int = 393, height: Int = 851): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        // AppCompat normally installs this in onCreate, before the permission/scanning lifecycle.
        // The retained fast-scroller inflates AppCompatImageView through that factory.
        activity.delegate.installViewFactory()
        call(activity, "buildInterface")
        if (withFragments) {
            database = Room.inMemoryDatabaseBuilder(context, SongsDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(Executor { it.run() }).build()
            SongsDatabase::class.java.getDeclaredField("db").apply { isAccessible = true }.set(null, database)
            listOf("Избранное", "Ночной город", "Проверка 7.1", "Стерео", "Тихие волны").forEachIndexed { i, title ->
                database!!.PlaylistsDao().insert(Playlist(i + 1, title))
            }
            call(activity, "initFragments")
        }
        controller.visible()
        layout(activity, width, height)
        return activity
    }

    private fun layout(activity: MainActivity, width: Int = 393, height: Int = 851): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return root
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("Library adapter did not finish loading fixture rows")
    }

    private fun snapshot(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width * 3, root.height * 3, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap).apply { scale(3f, 3f) })
        File("build/reports/library-restoration/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    private fun disableFixtureAnimations(list: RecyclerView) {
        // Disable fixture animations so the screenshot contains fully visible adapter rows.
        list.layoutAnimation = null
        list.itemAnimator?.endAnimations()
        list.itemAnimator = null
        fun clear(view: View) {
            view.clearAnimation()
            if (view is ViewGroup) repeat(view.childCount) { clear(view.getChildAt(it)) }
        }
        clear(list)
        list.requestLayout()
    }

    private fun assertIconIsReadable(icon: Drawable, surface: Int) {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 48, 48)
        icon.draw(Canvas(bitmap))
        var opaque = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) >= 250) {
                opaque++
                assertTrue("Rendered library icon must contrast with its panel", ColorUtils.calculateContrast(pixel, surface) >= 4.5)
            }
        }
        assertTrue("The icon contains visible strokes", opaque > 10)
        bitmap.recycle()
    }

    private fun assertVisibleRowText(root: View, list: RecyclerView) {
        assertEquals("All five actual playlist row Views are laid out", 5, list.childCount)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        try {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index)
                val text = row.findViewById<TextView>(R.id.playlist_title)
                val region = Rect(0, 0, text.width, text.height)
                (root as ViewGroup).offsetDescendantRectToMyCoords(text, region)
                assertTrue("Playlist title is inside the screen", region.top >= 0 && region.bottom <= root.height && region.width() > 0)
                var inkPixels = 0
                for (y in region.top until region.bottom) for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(root.width)) {
                    if (ColorUtils.calculateContrast(bitmap.getPixel(x, y), SpatialStyle.card) >= 3.0) inkPixels++
                }
                assertTrue("Playlist text is present in the actual rendered frame: ${text.text}", inkPixels > 20)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun allSixSectionsAndOriginalActionsRemainAvailableAndSearchFiltersRealRows() {
        val activity = library(withFragments = true)
        val tabs = activity.findViewById<TabLayout>(R.id.main_tabs_holder)
        val pager = activity.findViewById<ViewPager>(R.id.view_pager)
        val adapter = pager.adapter as ViewPagerAdapter
        val search = activity.findViewById<MySearchMenu>(R.id.main_menu)
        assertEquals(6, tabs.tabCount); assertEquals(6, adapter.count)
        assertEquals(TabLayout.MODE_SCROLLABLE, tabs.tabMode)
        val menu = search.requireToolbar().menu
        for (item in listOf(R.id.sort, R.id.rescan_media, R.id.sleep_timer, R.id.equalizer, R.id.settings,
            R.id.create_new_playlist, R.id.create_playlist_from_folder, R.id.import_playlist, R.id.manage_library_tabs)) {
            assertTrue("Action $item remains available", menu.findItem(item).isVisible)
        }
        val list = activity.findViewById<RecyclerView>(R.id.playlists_list)
        waitFor {
            list.adapter?.itemCount == 5 && listOf(R.id.folders_list, R.id.artists_list, R.id.albums_list, R.id.tracks_list, R.id.genres_list)
                .all { activity.findViewById<RecyclerView>(it)?.adapter != null }
        }
        disableFixtureAnimations(list)
        val libraryRoot = layout(activity)
        snapshot(libraryRoot, "09-library")
        assertVisibleRowText(libraryRoot, list)
        search.binding.topToolbarSearch.setText("Ночной")
        assertEquals(1, list.adapter!!.itemCount)
        search.closeSearch()
        assertEquals(5, list.adapter!!.itemCount)
        for (position in 0 until tabs.tabCount) {
            pager.setCurrentItem(position, false)
            layout(activity)
            assertEquals(position, tabs.selectedTabPosition)
            assertNotNull(adapter.getFragmentAt(position))
            assertEquals(position == 0, menu.findItem(R.id.import_playlist).isVisible)
            assertTrue(menu.findItem(R.id.sort).isVisible)
        }
        assertNull("No duplicate playback controls on the library screen", activity.findViewById<View>(R.id.current_track_bar))
    }

    @Test fun libraryMenuLaunchesRealScreensAndPlaylistAndTimerDialogs() {
        val activity = library()
        val search = activity.findViewById<MySearchMenu>(R.id.main_menu)
        val menu = search.requireToolbar().menu
        assertTrue(menu.performIdentifierAction(R.id.equalizer, 0))
        assertEquals(EqualizerActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(menu.performIdentifierAction(R.id.settings, 0))
        assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(menu.performIdentifierAction(R.id.import_playlist, 0))
        assertEquals(android.content.Intent.ACTION_GET_CONTENT, shadowOf(activity).nextStartedActivityForResult.intent.action)
        for (item in listOf(R.id.create_new_playlist, R.id.sleep_timer, R.id.manage_library_tabs)) {
            assertTrue(menu.performIdentifierAction(item, 0))
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            dialog.dismiss()
        }
        search.binding.topToolbarSearchIcon.performClick()
        assertTrue("The library has a direct back action", activity.isFinishing)
    }

    @Test fun allPastelPalettesSurviveSystemNightThemeAndCompactLayout() {
        for (night in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("ru-rRU-w320dp-h640dp-port-$night-mdpi")
            RuntimeEnvironment.setFontScale(1.3f)
            // Window/display metrics must be fixed before attaching this Activity's real ViewRoot.
            val activity = library(width = 320, height = 640)
            val menu = activity.findViewById<MySearchMenu>(R.id.main_menu)
            val tabs = activity.findViewById<TabLayout>(R.id.main_tabs_holder)
            for (theme in SpatialStyle.themes) {
            SpatialStyle.select(context, theme.id)
            call(activity, "applyLibraryAppearance")
            val root = layout(activity, 320, 640)
            if (theme.id == "sky") snapshot(root, "library-320-$night")
            assertEquals(theme.background, (root.background as ColorDrawable).color)
            assertEquals(theme.ink, menu.binding.topToolbarSearch.currentTextColor)
            assertEquals(theme.secondary, menu.binding.topToolbarSearch.currentHintTextColor)
            assertFalse(root.isForceDarkAllowed)
            assertTrue(ColorUtils.calculateContrast(menu.binding.topToolbarSearch.currentHintTextColor, theme.panel) >= 4.5)
            for (action in listOf(R.id.sort, R.id.equalizer)) assertIconIsReadable(menu.requireToolbar().menu.findItem(action).icon!!, theme.panel)
            assertIconIsReadable(menu.requireToolbar().overflowIcon!!, theme.panel)
            val pager = activity.findViewById<ViewPager>(R.id.view_pager)
            assertTrue("Sections remain above the bottom tabs", pager.bottom <= tabs.top)
            val tabPosition = IntArray(2).also(tabs::getLocationInWindow)
            val rootPosition = IntArray(2).also(root::getLocationInWindow)
            assertTrue("Tabs fit on screen: y=${tabPosition[1]}, height=${tabs.height}, rootY=${rootPosition[1]}, root=${root.height}",
                tabPosition[1] + tabs.height <= rootPosition[1] + 640 && tabs.height >= 48)
            val selected = BottomTablayoutItemBinding.bind(tabs.getTabAt(0)!!.customView!!)
            assertEquals(theme.accent, selected.tabItemLabel.currentTextColor)
            }
        }
    }

    @Test fun paletteReachesCommonsRowsAndDialogsWithoutChangingLibraryContentPreferences() {
        val originalSorting = context.config.playlistSorting
        context.config.showTabs = TAB_PLAYLISTS or TAB_TRACKS
        for (theme in SpatialStyle.themes) {
            SpatialStyle.select(context, theme.id)
            LibraryStyle.synchronize(context)
            assertEquals(theme.ink, context.config.textColor)
            assertEquals(theme.background, context.config.backgroundColor)
            assertEquals(theme.accent, context.config.primaryColor)
            assertFalse(context.config.isSystemThemeEnabled)
            assertEquals(TAB_PLAYLISTS or TAB_TRACKS, context.config.showTabs)
            assertEquals(originalSorting, context.config.playlistSorting)
        }
    }
}
