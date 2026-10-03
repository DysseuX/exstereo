/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.musicplayer.R

/** Selection and clearing affect queue entries only; callers retain the queue snapshot. */
internal fun showQueueRemovalDialog(
    context: Context,
    labels: List<String>,
    byFolder: Boolean,
    onRemove: (List<Int>) -> Unit,
    onClear: () -> Unit,
    onSwitch: () -> Unit
): AlertDialog {
    val themed = ContextThemeWrapper(context, R.style.SpatialQueueDialogTheme)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val chosen = BooleanArray(labels.size)
    lateinit var dialog: AlertDialog
    val selectAll = CheckBox(themed).apply {
        text = context.getString(R.string.player_select_all)
        setTextColor(SpatialStyle.ink)
        buttonTintList = ColorStateList.valueOf(SpatialStyle.accent)
        minHeight = dp(48)
    }
    val header = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(8))
        addView(TextView(themed).apply {
            text = context.getString(R.string.queue_remove)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(SpatialStyle.ink)
        })
        addView(TextView(themed).apply {
            text = context.getString(R.string.player_queue_keep_files)
            textSize = 14f
            setTextColor(SpatialStyle.secondary)
            setPadding(0, dp(6), 0, dp(4))
        })
        addView(selectAll)
        addView(Button(themed).apply {
            text = context.getString(R.string.player_queue_clear)
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(SpatialStyle.ink)
            backgroundTintList = ColorStateList.valueOf(SpatialStyle.selected)
            setOnClickListener { dialog.dismiss(); onClear() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    var updatingCheckbox = false
    dialog = AlertDialog.Builder(themed)
        .setCustomTitle(header)
        .setMultiChoiceItems(labels.toTypedArray(), chosen) { _, index, checked ->
            chosen[index] = checked
            updatingCheckbox = true
            selectAll.isChecked = chosen.all { it }
            updatingCheckbox = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = chosen.any { it }
        }
        .setPositiveButton(R.string.player_remove) { _, _ -> onRemove(chosen.indices.filter { chosen[it] }) }
        .setNegativeButton(R.string.player_cancel, null)
        .setNeutralButton(if (byFolder) R.string.player_group_files else R.string.player_group_folders) { _, _ -> onSwitch() }
        .create()
    selectAll.setOnCheckedChangeListener { _, checked ->
        if (!updatingCheckbox) {
            chosen.indices.forEach { index ->
                chosen[index] = checked
                dialog.listView.setItemChecked(index, checked)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked && chosen.isNotEmpty()
        }
    }
    dialog.setOnShowListener {
        dialog.window?.setBackgroundDrawable(SpatialStyle.rounded(context, SpatialStyle.card, 20))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val buttonColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(SpatialStyle.accent, SpatialStyle.secondary)
        )
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
            dialog.getButton(it).setTextColor(buttonColors)
        }
    }
    dialog.show()
    return dialog
}
