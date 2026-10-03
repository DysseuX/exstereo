/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.musicplayer.helpers.AppLanguage
import org.fossify.musicplayer.helpers.SimpleMediaController
import org.fossify.musicplayer.diagnostics.StartupDiagnostics

class App : Application() {
    private var controllerStarted = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        StartupDiagnostics.install(this)
        if (!StartupDiagnostics.hasReport()) startPlaybackController()
    }

    fun startPlaybackController() {
        if (controllerStarted) return
        controllerStarted = true
        StartupDiagnostics.mark("application preferences and media controller")
        initController()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyTo(this)
    }

    private fun initController() {
        SimpleMediaController.getInstance(applicationContext).createControllerAsync()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    SimpleMediaController.destroyInstance()
                }
            }
        )
    }
}
