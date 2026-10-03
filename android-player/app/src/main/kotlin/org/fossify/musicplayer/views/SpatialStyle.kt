/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.annotation.StringRes
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage

/** Saved pastel palettes for Spatial screens, independent of album art and library customization. */
object SpatialStyle {
    data class Theme(
        val id: String,
        @StringRes val titleRes: Int,
        val background: Int,
        val panel: Int,
        val card: Int,
        val ink: Int,
        val secondary: Int,
        val accent: Int,
        val selected: Int,
        val line: Int
    ) {
        val title: String get() = AppLanguage.string(titleRes)
    }

    private fun color(hex: String) = Color.parseColor(hex)

    val themes = listOf(
        Theme("sky", R.string.player_theme_sky, color("#EAF3F8"), color("#DCEAF2"), color("#F8FBFD"),
            color("#213B4B"), color("#486171"), color("#2F6384"), color("#C3DEEF"), color("#BAD3E1")),
        Theme("mint", R.string.player_theme_mint, color("#EDF5F0"), color("#DAEBE2"), color("#F8FCF9"),
            color("#29473A"), color("#4A6656"), color("#386C55"), color("#C4E2D2"), color("#B7D5C4")),
        Theme("lavender", R.string.player_theme_lavender, color("#F1EFF8"), color("#E6DFF1"), color("#FCFAFF"),
            color("#433650"), color("#62516E"), color("#71508B"), color("#DCCBEC"), color("#CBBAD9")),
        Theme("peach", R.string.player_theme_peach, color("#FCF1EA"), color("#F4E0D2"), color("#FFFBF7"),
            color("#513D32"), color("#715544"), color("#946348"), color("#EFCDB6"), color("#DEBFA9")),
        Theme("rose", R.string.player_theme_rose, color("#F9EFF3"), color("#F0DEE6"), color("#FFFAFC"),
            color("#523647"), color("#6F5061"), color("#96566F"), color("#E9C7D7"), color("#DAB2C5")),
        Theme("sand", R.string.player_theme_sand, color("#F7F4EA"), color("#ECE5D4"), color("#FEFCF5"),
            color("#4B4330"), color("#685E47"), color("#837044"), color("#E3D8B9"), color("#D2C6A7"))
    )

    var current: Theme = themes.first()
        private set

    val background get() = current.background
    val panel get() = current.panel
    val card get() = current.card
    val ink get() = current.ink
    val secondary get() = current.secondary
    val accent get() = current.accent
    val selected get() = current.selected
    val line get() = current.line

    fun initialize(context: Context): Theme {
        val id = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(THEME_ID, themes.first().id)
        current = themes.firstOrNull { it.id == id } ?: themes.first()
        return current
    }

    fun select(context: Context, id: String): Boolean {
        val theme = themes.firstOrNull { it.id == id } ?: return false
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(THEME_ID, theme.id).apply()
        current = theme
        return true
    }

    fun rounded(context: Context, color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * context.resources.displayMetrics.density
    }

    fun ripple(context: Context, color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(withAlpha(accent, 35)),
        rounded(context, color, radius), rounded(context, Color.WHITE, radius)
    )

    fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private const val PREFERENCES = "spatial_appearance"
    private const val THEME_ID = "theme_id"
}
