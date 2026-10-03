/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.helpers

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import org.fossify.commons.extensions.baseConfig
import java.util.Locale

object AppLanguage {
    private var application: Context? = null

    fun initialize(context: Context) {
        application = context.applicationContext ?: context
        clearManualOverride(context)
        applyTo(context)
    }

    internal fun select(locales: LocaleList): Locale =
        if (!locales.isEmpty && locales[0].language == "ru") Locale.forLanguageTag("ru") else Locale.ENGLISH

    fun locale(context: Context): Locale {
        val system = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null
        return select(system?.takeUnless { it.isEmpty } ?: Resources.getSystem().configuration.locales)
    }

    fun wrap(context: Context): Context {
        if (application == null) application = context.applicationContext ?: context
        // Fossify's old toggle must not replace the language selected from Android.
        if (context.baseConfig.useEnglish) context.baseConfig.useEnglish = false
        val config = Configuration(context.resources.configuration)
        val selected = locale(context)
        config.setLocales(LocaleList(selected))
        config.setLayoutDirection(selected)
        return context.createConfigurationContext(config)
    }

    fun clearManualOverride(context: Context) {
        if (context.baseConfig.useEnglish) context.baseConfig.useEnglish = false
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.let { manager ->
                if (!manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.getEmptyLocaleList()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun applyTo(context: Context) {
        val config = Configuration(context.resources.configuration)
        val selected = locale(context)
        config.setLocales(LocaleList(selected))
        config.setLayoutDirection(selected)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun string(@StringRes id: Int, vararg args: Any): String {
        val resources = wrap(checkNotNull(application) { "Application language is not initialized" }).resources
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }
}
