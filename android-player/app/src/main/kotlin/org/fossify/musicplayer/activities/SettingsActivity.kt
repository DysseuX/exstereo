/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.commons.helpers.IS_CUSTOMIZING_COLORS
import org.fossify.musicplayer.R
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.helpers.SHOW_FILENAME_ALWAYS
import org.fossify.musicplayer.helpers.SHOW_FILENAME_IF_UNAVAILABLE
import org.fossify.musicplayer.helpers.SHOW_FILENAME_NEVER
import org.fossify.musicplayer.views.SpatialStyle
import org.fossify.musicplayer.views.showSpatialChoiceDialog
import kotlin.math.roundToInt

/** App settings for the current player; launcher appearance and typeface are fixed. */
@SuppressLint("SetTextI18n")
class SettingsActivity : SimpleControllerActivity() {
    private var scroll: ScrollView? = null

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    override fun onResume() {
        super.onResume()
        SpatialStyle.initialize(this)
        buildInterface()
    }

    @Suppress("DEPRECATION")
    private fun buildInterface() {
        val previousScroll = scroll?.scrollY ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SpatialStyle.background)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "‹"; textSize = 28f; contentDescription = getString(R.string.player_nav_back)
            setTextColor(SpatialStyle.ink)
            background = SpatialStyle.ripple(this@SettingsActivity, SpatialStyle.selected, 20)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        header.addView(label(getString(R.string.player_settings_title), 22).apply {
            setPadding(dp(12), dp(8), 0, dp(8)); minHeight = dp(76)
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        section(body, getString(R.string.player_theme_title))
        body.addView(label(getString(R.string.player_theme_description), 14).apply {
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        SpatialStyle.themes.forEach { theme ->
            val chosen = theme.id == SpatialStyle.current.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = SpatialStyle.ripple(this@SettingsActivity, theme.panel, 20)
                isClickable = true; isFocusable = true
                contentDescription = getString(if (chosen) R.string.player_theme_selected else R.string.player_theme_option, theme.title)
                isSelected = chosen
                setOnClickListener {
                    SpatialStyle.select(this@SettingsActivity, theme.id)
                    buildInterface()
                }
            }
            row.addView(View(this).apply {
                background = SpatialStyle.rounded(this@SettingsActivity, theme.accent, 18)
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
            row.addView(label(theme.title, 17).apply { setTextColor(theme.ink) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(if (chosen) "✓" else "", 24).apply {
                setTextColor(theme.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(30), -2))
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        section(body, getString(R.string.player_tab_settings))
        val queueMargin = label(getString(R.string.player_queue_margin, config.playerQueueMarginPx), 16)
        body.addView(queueMargin)
        body.addView(SeekBar(this).apply {
            contentDescription = getString(R.string.player_queue_margin_description)
            max = 40; progress = config.playerQueueMarginPx
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    queueMargin.text = getString(R.string.player_queue_margin, value)
                    if (fromUser) config.playerQueueMarginPx = value
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))

        section(body, getString(R.string.player_general_settings))
        body.addView(label(getString(R.string.player_language_automatic), 14).apply {
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        action(body, getString(org.fossify.commons.R.string.manage_excluded_folders)) {
            startActivity(Intent(this, ExcludedFoldersActivity::class.java))
        }
        toggle(body, getString(R.string.swap_prev_next), config.swapPrevNext) { config.swapPrevNext = it }
        action(body, getString(R.string.show_filename), replaceTitleText(config.showFilename)) {
            val options = listOf(SHOW_FILENAME_NEVER, SHOW_FILENAME_IF_UNAVAILABLE, SHOW_FILENAME_ALWAYS)
            showSpatialChoiceDialog(this, getString(R.string.show_filename), options.map(::replaceTitleText), options.indexOf(config.showFilename)) { index ->
                config.showFilename = options[index]
                refreshQueueAndTracks()
                buildInterface()
            }
        }
        action(body, getString(org.fossify.commons.R.string.customize_widget_colors)) {
            startActivity(Intent(this, WidgetConfigureActivity::class.java).putExtra(IS_CUSTOMIZING_COLORS, true))
        }

        window.statusBarColor = SpatialStyle.background
        window.navigationBarColor = SpatialStyle.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        scroll?.post { scroll?.scrollTo(0, previousScroll) }
    }

    private fun replaceTitleText(value: Int) = getString(when (value) {
        SHOW_FILENAME_NEVER -> org.fossify.commons.R.string.never
        SHOW_FILENAME_IF_UNAVAILABLE -> R.string.title_is_not_available
        else -> org.fossify.commons.R.string.always
    })

    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat()
        setTextColor(if (size < 16) SpatialStyle.secondary else SpatialStyle.ink)
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(label(title, 18).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(18), dp(4), dp(12))
        })
    }

    private fun action(body: LinearLayout, title: String, value: String? = null, click: () -> Unit) {
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = SpatialStyle.ripple(this@SettingsActivity, SpatialStyle.card, 20)
            isClickable = true; isFocusable = true
            addView(label(title, 16))
            value?.let { addView(label(it, 14).apply { setPadding(0, dp(4), 0, 0) }) }
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    @Suppress("DEPRECATION")
    private fun toggle(body: LinearLayout, title: String, checked: Boolean, change: (Boolean) -> Unit) {
        body.addView(Switch(this).apply {
            text = title; textSize = 16f; setTextColor(SpatialStyle.ink)
            minHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = SpatialStyle.ripple(this@SettingsActivity, SpatialStyle.card, 20)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(SpatialStyle.accent, SpatialStyle.secondary))
            trackTintList = ColorStateList.valueOf(SpatialStyle.selected)
            isChecked = checked
            setOnCheckedChangeListener { _, next -> change(next) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

}
