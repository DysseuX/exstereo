/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.content.Context
import org.fossify.musicplayer.extensions.config

/** Applies the saved player palette to Commons rows and dialogs. */
object LibraryStyle {
    fun synchronize(context: Context) {
        val theme = SpatialStyle.initialize(context)
        context.config.apply {
            isSystemThemeEnabled = false
            isGlobalThemeEnabled = false
            textColor = theme.ink
            backgroundColor = theme.background
            primaryColor = theme.accent
            accentColor = theme.accent
        }
    }
}
