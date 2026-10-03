/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */

package org.dysseux.hrtf

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import java.util.Locale

object SystemLanguage {
    fun locale(context: Context): Locale {
        val system = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
                ?: Resources.getSystem().configuration.locales
        } else Resources.getSystem().configuration.locales
        return if (!system.isEmpty && system[0].language == "ru") Locale.forLanguageTag("ru") else Locale.ENGLISH
    }

    fun context(base: Context): Context {
        val locale = locale(base)
        val configuration = Configuration(base.resources.configuration).apply {
            // One locale prevents Android from selecting Russian from a secondary system language.
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    fun clearApplicationOverride(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.let {
                if (!it.applicationLocales.isEmpty) it.applicationLocales = LocaleList.getEmptyLocaleList()
            }
        }
    }
}

class HrtfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemLanguage.clearApplicationOverride(this)
    }
}

abstract class SystemLanguageActivity : Activity() {
    private var appliedLanguage = "en"
    override fun attachBaseContext(newBase: Context) {
        val localized = SystemLanguage.context(newBase)
        appliedLanguage = localized.resources.configuration.locales[0].language
        super.attachBaseContext(localized)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.screen_title)
    }

    override fun onResume() {
        super.onResume()
        refreshLanguage()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    private fun refreshLanguage() {
        if (SystemLanguage.locale(this).language != appliedLanguage && !isFinishing) recreate()
    }
}
