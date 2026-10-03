/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** A bounded, scrollable chooser using the same saved palette as Spatial screens. */
fun showSpatialChoiceDialog(
    activity: Activity,
    title: String,
    choices: List<String>,
    checked: Int,
    select: (Int) -> Unit
): Dialog {
    SpatialStyle.initialize(activity)
    fun dp(value: Int) = (activity.resources.displayMetrics.density * value).roundToInt()
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = SpatialStyle.rounded(activity, SpatialStyle.card, 24)
    }
    body.addView(TextView(activity).apply {
        text = title; textSize = 18f; setTextColor(SpatialStyle.ink)
        setPadding(0, 0, 0, dp(12))
    })
    val group = RadioGroup(activity)
    choices.forEachIndexed { index, choice ->
        group.addView(RadioButton(activity).apply {
            id = index + 1; text = choice; textSize = 16f
            setTextColor(SpatialStyle.ink); minHeight = dp(52)
            buttonTintList = ColorStateList.valueOf(SpatialStyle.accent)
            isChecked = index == checked
        })
    }
    group.setOnCheckedChangeListener { _, id ->
        if (id in 1..choices.size) {
            dialog.dismiss()
            select(id - 1)
        }
    }
    val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.55f).roundToInt()
    body.addView(ScrollView(activity).apply { addView(group) },
        LinearLayout.LayoutParams(-1, (choices.size * dp(56)).coerceAtMost(maxHeight)))
    body.addView(Button(activity).apply {
        text = activity.getString(android.R.string.cancel)
        setTextColor(SpatialStyle.ink)
        background = SpatialStyle.ripple(activity, SpatialStyle.panel, 18)
        setOnClickListener { dialog.dismiss() }
    })
    dialog.setContentView(body)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(480)), -2)
    return dialog
}
