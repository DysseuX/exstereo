/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fossify.commons.extensions.baseConfig
import org.fossify.musicplayer.LocalizedTestApplication
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage
import org.fossify.musicplayer.spatial.SpatialSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33], application = LocalizedTestApplication::class, qualifiers = "en-w393dp-h851dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AppLanguageTest {
    interface RecreationProbe { val recreationRequests: Int }

    class LocalizedResumeProbe : LocalizedActivity(), RecreationProbe {
        override var recreationRequests = 0
        override fun recreate() { recreationRequests++ }
    }

    class LibraryResumeProbe : SimpleActivity(), RecreationProbe {
        override var recreationRequests = 0
        override fun recreate() { recreationRequests++ }
    }

    private val context get() = RuntimeEnvironment.getApplication()

    private fun texts(view: View): Sequence<String> = sequence {
        if (view is TextView) yield(view.text.toString())
        view.contentDescription?.let { yield(it.toString()) }
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(texts(view.getChildAt(it))) }
    }

    private fun screen(type: Class<out Activity>): Pair<Activity, String> {
        val controller = Robolectric.buildActivity(type)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        type.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        return activity to texts(activity.findViewById(android.R.id.content)).joinToString("\n")
    }

    private fun englishScreens() {
        for (type in listOf(SpatialPlayerActivity::class.java, SpatialAudioActivity::class.java)) {
            val (activity, text) = screen(type)
            assertEquals("en", activity.resources.configuration.locales[0].language)
            assertEquals(View.LAYOUT_DIRECTION_LTR, activity.resources.configuration.layoutDirection)
            assertFalse("Russian text on ${type.simpleName}: $text", Regex("[А-Яа-яЁё]").containsMatchIn(text))
            assertTrue(text.isNotBlank())
        }
        assertEquals(11, SpatialSettings.wideningAlgorithmNames.size)
        assertEquals(3, SpatialSettings.upmixerNames.size)
        assertFalse(Regex("[А-Яа-яЁё]").containsMatchIn(SpatialSettings.wideningAlgorithmNames.joinToString()))
    }

    @Test fun englishDeviceUsesEnglishOnBothScreens() = englishScreens()

    @Test @Config(qualifiers = "de-rDE-w393dp-h851dp-port-mdpi")
    fun unsupportedSystemLanguageFallsBackToEnglish() = englishScreens()

    @Test @Config(qualifiers = "ar-rEG-w393dp-h851dp-port-mdpi")
    fun rtlSystemLanguageAlsoUsesEnglishAndLeftToRightLayout() = englishScreens()

    @Test @Config(qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
    fun russianDeviceUsesRussianOnBothScreens() {
        val (player, playerText) = screen(SpatialPlayerActivity::class.java)
        val (audio, audioText) = screen(SpatialAudioActivity::class.java)
        assertEquals("ru", player.resources.configuration.locales[0].language)
        assertEquals("ru", audio.resources.configuration.locales[0].language)
        assertTrue(playerText.contains("Плеер"))
        assertTrue(audioText.contains("Звук в наушниках"))
        assertTrue(audioText.contains("Режим прослушивания"))
    }

    @Test fun secondaryRussianDoesNotSelectRussian() {
        assertEquals("en", AppLanguage.select(LocaleList.forLanguageTags("en-US,ru-RU")).language)
        assertEquals("en", AppLanguage.select(LocaleList.forLanguageTags("de-DE,ru-RU")).language)
        assertEquals("ru", AppLanguage.select(LocaleList.forLanguageTags("ru-KZ,en-US")).language)
        assertEquals("en", AppLanguage.select(LocaleList.getEmptyLocaleList()).language)
        val config = Configuration(Resources.getSystem().configuration)
        config.setLocales(LocaleList.forLanguageTags("en-US,ru-RU"))
        @Suppress("DEPRECATION")
        Resources.getSystem().updateConfiguration(config, Resources.getSystem().displayMetrics)
        englishScreens()
    }

    @Test @Config(qualifiers = "ru-rRU-w393dp-h851dp-port-mdpi")
    fun oldEnglishPreferenceDoesNotOverrideRussianSystem() {
        context.baseConfig.useEnglish = true
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
        }
        AppLanguage.initialize(context)
        assertFalse(context.baseConfig.useEnglish)
        assertEquals("ru", AppLanguage.wrap(context).resources.configuration.locales[0].language)
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(context.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
        }
    }

    @Test fun newScreenAndStaticLabelsFollowSystemLanguageChanges() {
        englishScreens()
        RuntimeEnvironment.setQualifiers("ru-rRU-w393dp-h851dp-port-mdpi")
        AppLanguage.applyTo(context)
        assertTrue(screen(SpatialAudioActivity::class.java).second.contains("Звук в наушниках"))
        RuntimeEnvironment.setQualifiers("fr-rFR-w393dp-h851dp-port-mdpi")
        AppLanguage.applyTo(context)
        englishScreens()
    }

    private fun <T> verifyAutomaticRecreation(type: Class<T>) where T : Activity, T : RecreationProbe {
        fun systemLanguages(tags: String) {
            val system = Resources.getSystem()
            val configuration = Configuration(system.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }
            @Suppress("DEPRECATION")
            system.updateConfiguration(configuration,system.displayMetrics)
        }
        systemLanguages("en-US")
        AppLanguage.applyTo(context)
        val controller = Robolectric.buildActivity(type)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        try {
            assertEquals(0,activity.recreationRequests)
            controller.pause()
            systemLanguages("de-DE,ru-RU")
            controller.resume()
            assertEquals("English fallback must not restart ${type.simpleName}",0,activity.recreationRequests)

            controller.pause()
            systemLanguages("ru-KZ,en-US")
            // Updating Resources does not translate views that were already bound in English.
            val updated = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("ru-KZ"))
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(updated,activity.resources.displayMetrics)
            assertEquals("ru",activity.resources.configuration.locales[0].language)
            controller.resume()
            assertEquals("${type.simpleName}.onResume must request recreation itself",1,activity.recreationRequests)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun alreadyOpenPlayerActivityRequestsRecreationOnResumeAfterSystemChange() {
        verifyAutomaticRecreation(LocalizedResumeProbe::class.java)
    }

    @Test fun alreadyOpenLibraryActivityRequestsRecreationOnResumeAfterSystemChange() {
        verifyAutomaticRecreation(LibraryResumeProbe::class.java)
    }
}
