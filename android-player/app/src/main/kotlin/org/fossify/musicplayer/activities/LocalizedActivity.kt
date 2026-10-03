/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import org.fossify.musicplayer.helpers.AppLanguage

open class LocalizedActivity : Activity() {
    private var appliedLanguage = "en"

    override fun attachBaseContext(newBase: Context) {
        val localized = AppLanguage.wrap(newBase)
        appliedLanguage = localized.resources.configuration.locales[0].language
        super.attachBaseContext(localized)
    }

    override fun onResume() {
        super.onResume()
        AppLanguage.clearManualOverride(this)
        refreshLanguage()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    private fun refreshLanguage() {
        if (AppLanguage.locale(this).language != appliedLanguage && !isFinishing) recreate()
    }
}
