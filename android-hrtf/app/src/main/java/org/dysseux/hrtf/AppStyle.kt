/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

object AppStyle {
    fun version(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    fun text(c: Context, resource: Int, size: Float = 16f, bold: Boolean = false) = text(c, c.getString(resource), size, bold)
    fun button(c: Context, resource: Int, action: () -> Unit) = button(c, c.getString(resource), action)
    val background = Color.rgb(235, 244, 248)
    val ink = Color.rgb(29, 53, 68)
    val secondary = Color.rgb(70, 96, 111)
    val accent = Color.rgb(50, 108, 139)
    val selected = Color.rgb(208, 229, 239)
    fun dp(c: Context, value: Int) = (c.resources.displayMetrics.density * value + .5f).toInt()
    fun shape(c: Context, color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
    }
    fun text(c: Context, value: String, size: Float = 16f, bold: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(c, 5), 0, dp(c, 5))
    }
    fun button(c: Context, caption: String, action: () -> Unit) = Button(c).apply {
        text = caption; textSize = 15f; isAllCaps = false; minHeight = dp(c, 48)
        setTextColor(ink); backgroundTintList = null; background = shape(c, selected, 14)
        setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 8) }
        setOnClickListener { action() }
    }
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = shape(c, Color.WHITE)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 14) }
    }
    fun page(activity: Activity, title: String): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(AppStyle.background)
            if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root.addView(text(activity, title, 25f, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 12))
        })
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 24))
        }
        root.addView(ScrollView(activity).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        activity.setContentView(root)
        root.requestApplyInsets()
        return body
    }
    fun numeric(c: Context, caption: String, value: String): EditText = EditText(c).apply {
        hint = caption; contentDescription = caption; setText(value); setTextColor(ink); setHintTextColor(secondary)
        textSize = 17f; minHeight = dp(c, 48); isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        backgroundTintList = ColorStateList.valueOf(accent)
    }
}
