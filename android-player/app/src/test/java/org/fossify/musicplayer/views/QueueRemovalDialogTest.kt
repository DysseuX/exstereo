/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w320dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueRemovalDialogTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    private class Result {
        var removed: List<Int>? = null
        var clears = 0
        var switches = 0
    }

    private fun open(result: Result, folders: Boolean = false): AlertDialog {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SpatialStyle.select(activity, "mint")
        val labels = if (folders) listOf("Album A (2)", "Album B (1)") else listOf("1. First.flac", "2. Second.mp3", "3. Third.dts")
        val dialog = showQueueRemovalDialog(activity, labels, folders,
            { result.removed = it }, { result.clears++ }, { result.switches++ })
        shadowOf(Looper.getMainLooper()).idle()
        return dialog
    }

    private fun all(dialog: AlertDialog) = descendants(dialog.window!!.decorView).filterIsInstance<CheckBox>()
        .single { it.text == "Выбрать всё" }

    private fun snapshot(dialog: AlertDialog, name: String) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        val root = dialog.window!!.decorView
        assertTrue(root.width in 1..320)
        assertTrue(root.height in 1..640)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/queue-removal/$name.png").apply {
            parentFile?.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    @Test fun selectAllThenUncheckOneRemovesOnlyTheRemainingSelection() {
        val result = Result()
        val dialog = open(result)
        val remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertFalse(remove.isEnabled)
        all(dialog).performClick()
        assertTrue(remove.isEnabled)
        repeat(3) { assertTrue(dialog.listView.isItemChecked(it)) }
        snapshot(dialog, "files-all")
        dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
        assertFalse(all(dialog).isChecked)
        assertFalse(dialog.listView.isItemChecked(1))
        remove.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0, 2), result.removed)
        assertEquals(0, result.clears)
    }

    @Test fun selectAllCanBeClearedAndSelectingEachRowChecksAll() {
        val result = Result()
        val dialog = open(result, folders = true)
        all(dialog).performClick()
        all(dialog).performClick()
        repeat(2) { assertFalse(dialog.listView.isItemChecked(it)) }
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        repeat(2) { dialog.listView.performItemClick(dialog.listView.getChildAt(it), it, it.toLong()) }
        assertTrue(all(dialog).isChecked)
        snapshot(dialog, "folders-all")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0, 1), result.removed)
    }

    @Test fun clearQueueDoesNotRequireSelectionAndCancelOrSwitchNeverRemove() {
        val result = Result()
        val clearDialog = open(result)
        descendants(clearDialog.window!!.decorView).filterIsInstance<Button>()
            .single { it.text == "Очистить очередь" }.performClick()
        assertFalse(clearDialog.isShowing)
        assertEquals(1, result.clears)
        assertNull(result.removed)
        val cancelDialog = open(result)
        all(cancelDialog).performClick()
        cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val switchDialog = open(result)
        switchDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, result.switches)
        assertNull(result.removed)
        assertEquals(1, result.clears)
    }
}
