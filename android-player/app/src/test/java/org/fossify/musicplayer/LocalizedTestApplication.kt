/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer

import android.app.Application
import org.fossify.musicplayer.helpers.AppLanguage

class LocalizedTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
    }
}
