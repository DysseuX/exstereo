/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.content.Intent
import android.content.Context
import android.os.Bundle
import org.fossify.commons.activities.BaseSplashActivity
import org.fossify.musicplayer.diagnostics.StartupDiagnostics
import org.fossify.musicplayer.helpers.AppLanguage

class SplashActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val target = if (StartupDiagnostics.hasReport()) StartupReportActivity::class.java else PlayerSplashActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}

class PlayerSplashActivity : BaseSplashActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun initActivity() {
        startActivity(Intent(this, SpatialPlayerActivity::class.java))
        finish()
    }
}
