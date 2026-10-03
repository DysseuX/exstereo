/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fossify.musicplayer.BuildConfig
import org.fossify.musicplayer.R
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Exercises the actual dialogs without starting playback or any native audio engine. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExstereoAboutTest {
    @Test fun aboutOpensTheOwnersGithubAndReadableOfflineLicenses() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup().visible()
        try {
            activity.showExstereoAbout()
            val about = latestDialog()
            val summary = about.findViewById<TextView>(android.R.id.message).text.toString()
            assertTrue(summary.contains(BuildConfig.VERSION_NAME))
            assertTrue(summary.contains("Fossify Music Player"))
            assertFalse(summary.contains("Разработчик:"))
            assertFalse(summary.contains("Компания:"))
            snapshot(about, "about")

            assertTrue(about.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
            shadowOf(Looper.getMainLooper()).idle()
            val intent = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "GitHub button must launch a browser intent"
            }
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://github.com/DysseuX", intent.dataString)

            activity.showExstereoAbout()
            latestDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val licenses = latestDialog()
            val list = licenses.listView
            assertTrue("Offline licences must be listed", list.adapter.count > 0)
            snapshot(licenses, "licenses")

            val catalog = activity.assets.open("licenses/catalog.json").bufferedReader().use { JSONArray(it.readText()) }
            assertEquals("Every packaged licence must be reachable from the list", catalog.length(), list.adapter.count)
            for (i in 0 until catalog.length()) {
                val files = catalog.getJSONObject(i).getJSONArray("files")
                assertTrue("Each component needs its licence text", files.length() > 0)
                for (j in 0 until files.length()) {
                    val path = "licenses/${files.getString(j)}"
                    activity.assets.open(path).bufferedReader().use {
                        assertTrue("Empty packaged licence: $path", it.readText().isNotBlank())
                    }
                }
            }

            val fossifyIndex = (0 until list.adapter.count).first {
                list.adapter.getItem(it).toString().contains("Fossify Music Player")
            }
            list.performItemClick(list.adapter.getView(fossifyIndex, null, list), fossifyIndex, list.adapter.getItemId(fossifyIndex))
            shadowOf(Looper.getMainLooper()).idle()
            val detail = latestDialog()
            assertNotSame("Selecting a component must open the licence reader", licenses, detail)
            val textViews = descendants(detail.window!!.decorView).filterIsInstance<TextView>().toList()
            val licence = textViews.firstOrNull { it.text.contains("GNU GENERAL PUBLIC LICENSE") }
            assertNotNull("The original GPL text must be displayed", licence)
            assertTrue("Licence text can be selected and copied", licence!!.isTextSelectable)
            detail.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(list.adapter.count, latestDialog().listView.adapter.count)
            latestDialog().dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun latestDialog(): AlertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog()) {
        "Expected an actual Android AlertDialog"
    }.also {
        assertTrue(it.isShowing)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun snapshot(dialog: AlertDialog, name: String) {
        val content = dialog.window!!.decorView
        content.measure(
            View.MeasureSpec.makeMeasureSpec(369, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.AT_MOST)
        )
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(image))
        val output = File("build/reports/exstereo-about/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
