/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only */

package org.fossify.musicplayer.activities

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import org.fossify.musicplayer.diagnostics.StartupDiagnostics
import org.fossify.musicplayer.models.Track
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.TreeMap

@RunWith(AndroidJUnit4::class)
class FolderNavigationTest {
    @Test fun sortedFoldersSupportNoSelectionAndBackNavigation() {
        assumeFalse("Preserve the existing startup report", StartupDiagnostics.hasReport())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SpatialPlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as SpatialPlayerActivity
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                fun field(name: String) = SpatialPlayerActivity::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }
                val savedFields = listOf("tracksByFolder", "activeFolder", "scanning")
                    .associate { name -> field(name).let { it to it.get(activity) } }
                val pager = field("pager").get(activity) as ViewPager
                val originalPage = pager.currentItem
                try {
                    val folders = TreeMap<String, List<Track>>(
                        compareBy<String> { File(it).name.lowercase(Locale.ROOT) }.thenBy { it }
                    ).apply {
                        put("/regression/Alpha", emptyList())
                        put("/regression/Beta", emptyList())
                    }
                    field("tracksByFolder").set(activity, folders)
                    field("scanning").setBoolean(activity, false)
                    val selected = field("activeFolder")
                    val show = SpatialPlayerActivity::class.java.getDeclaredMethod("showFolders")
                        .apply { isAccessible = true }
                    val adapter = field("folderAdapter").get(activity) as RecyclerView.Adapter<*>
                    fun assertFolderOpen(open: Boolean) {
                        val expected = if (open) View.VISIBLE else View.GONE
                        for (name in listOf("folderBack", "playFolder")) {
                            assertEquals(name, expected, (field(name).get(activity) as View).visibility)
                        }
                    }

                    // A nonempty TreeMap invokes its File-based comparator even for get(null).
                    selected.set(activity, null)
                    show.invoke(activity)
                    assertNull(selected.get(activity))
                    assertFolderOpen(false)
                    assertEquals(2, adapter.itemCount)

                    selected.set(activity, "/regression/Alpha")
                    show.invoke(activity)
                    assertFolderOpen(true)
                    assertEquals("Alpha", (field("folderTitle").get(activity) as TextView).text.toString())
                    assertEquals(0, adapter.itemCount)
                    pager.setCurrentItem(2, false)
                    val back = SpatialPlayerActivity::class.java.getDeclaredMethod("onBackPressedCompat")
                        .apply { isAccessible = true }
                    assertEquals(true, back.invoke(activity))
                    assertNull(selected.get(activity))
                    assertEquals(2, pager.currentItem)
                    assertFolderOpen(false)
                    assertEquals(2, adapter.itemCount)

                    selected.set(activity, "/regression/Removed")
                    show.invoke(activity)
                    assertNull(selected.get(activity))
                    assertFolderOpen(false)
                    assertEquals(2, adapter.itemCount)
                } finally {
                    savedFields.forEach { (field, value) -> field.set(activity, value) }
                    pager.setCurrentItem(originalPage, false)
                }
            } catch (error: Throwable) {
                failure = if (error is InvocationTargetException) error.targetException else error
            } finally {
                // Keep assertion failures off Android's uncaught main-thread crash handler.
                try { activity.finish() } catch (error: Throwable) { if (failure == null) failure = error }
            }
        }
        failure?.let { throw it }
    }
}
