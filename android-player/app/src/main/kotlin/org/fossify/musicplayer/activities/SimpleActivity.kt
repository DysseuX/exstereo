/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.REPOSITORY_NAME
import org.fossify.musicplayer.helpers.AppLanguage
import org.fossify.musicplayer.views.SpatialStyle

open class SimpleActivity : BaseSimpleActivity() {
    private var appliedLanguage = "en"

    override fun attachBaseContext(newBase: Context) {
        val localized = AppLanguage.wrap(newBase)
        appliedLanguage = localized.resources.configuration.locales[0].language
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SpatialStyle.initialize(this)
        super.onCreate(savedInstanceState)
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

    override fun getAppIconIDs() = arrayListOf(R.mipmap.exstereo_launcher)

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
