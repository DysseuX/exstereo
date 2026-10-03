/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */

package org.dysseux.hrtf

import android.app.Activity
import android.app.LocaleManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Locale
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,33],application=HrtfApplication::class,qualifiers="en-rUS-w393dp-h851dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SystemLanguageTest {
    class ResumeProbeActivity : SystemLanguageActivity() {
        var recreationRequests = 0
        override fun recreate() { recreationRequests++ }
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private val cyrillic = Regex("[А-Яа-яЁё]")

    @Before fun clearInputs() { ProfileJob.clear(app) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(views(view.getChildAt(it))) }
    }

    private fun texts(activity: Activity): List<String> = views(activity.findViewById(android.R.id.content)).flatMap { view ->
        sequence {
            if (view is TextView) { yield(view.text.toString()); view.hint?.let { yield(it.toString()) } }
            view.contentDescription?.let { yield(it.toString()) }
        }
    }.toList()

    private fun click(activity: Activity, text: String) {
        views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
    }

    private fun setSystemLanguages(tags: String) {
        val system = Resources.getSystem()
        val configuration = Configuration(system.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }
        @Suppress("DEPRECATION")
        system.updateConfiguration(configuration,system.displayMetrics)
    }

    private fun photoIntent(side: String = "left"): Intent {
        val bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        InputStore(app).photo(side).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        return Intent(app,PhotoActivity::class.java).putExtra("side",side)
    }

    private fun verifyScreens(russian: Boolean) {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = main.get()
            assertEquals(if (russian) "ru" else "en",activity.resources.configuration.locales[0].language)
            val text = texts(activity)
            assertTrue(text.contains(if (russian) "1 · Левое ухо" else "1 · Left ear"))
            assertTrue(text.contains(if (russian) "2 · Правое ухо" else "2 · Right ear"))
            assertTrue(text.contains(if (russian) "Ширина головы, см" else "Head width, cm"))
            if (!russian) assertFalse(text.joinToString().contains(cyrillic))
            assertEquals("HRTF",app.applicationInfo.loadLabel(app.packageManager).toString())
            click(activity,if (russian) "Создать HRTF" else "Create HRTF")
            val error = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(if (russian) "Подготовьте фотографию и разметку левого уха." else "Prepare the photo and marked points for the left ear.",shadowOf(error).message.toString())
            error.dismiss()
            click(activity,if (russian) "О приложении и лицензии" else "About and licences")
            val about = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(shadowOf(about).message.toString().contains("PolyForm Noncommercial 1.0.0"))
            if (!russian) assertFalse(shadowOf(about).message.toString().contains(cyrillic))
            about.dismiss()
        } finally { main.pause().stop().destroy() }
        for (side in listOf("left","right")) {
            val photo = Robolectric.buildActivity(PhotoActivity::class.java,photoIntent(side)).setup()
            try {
                val activity = photo.get()
                val text = texts(activity)
                assertEquals(if (russian) "ru" else "en",activity.resources.configuration.locales[0].language)
                assertTrue(text.contains(if (russian) "Три точки на фотографии" else "Three points on the photo"))
                assertTrue(text.contains(if (russian) "Порог контуров: 15%" else "Contour threshold: 15%"))
                assertTrue(text.any { it.startsWith(if (russian) "Фото уха. Выберите" else "Ear photo. Select") })
                if (!russian) assertFalse(text.joinToString().contains(cyrillic))
                click(activity,if (russian) "Сохранить разметку" else "Save marked points")
                val error = ShadowAlertDialog.getLatestAlertDialog()
                assertEquals(if (russian) "Отметьте все три точки на фотографии." else "Mark all three points on the photo.",shadowOf(error).message.toString())
                error.dismiss()
            } finally { photo.pause().stop().destroy() }
        }
    }

    @Test fun englishSystemUsesEnglishOnBothScreens() = verifyScreens(false)

    @Test @Config(qualifiers="ru-rRU-w393dp-h851dp-port-mdpi")
    fun russianSystemUsesRussianOnBothScreens() = verifyScreens(true)

    @Test @Config(qualifiers="de-rDE-w393dp-h851dp-port-mdpi")
    fun germanSystemUsesEnglishOnBothScreens() = verifyScreens(false)

    @Test fun secondaryRussianDoesNotSelectRussianOnEitherScreen() {
        setSystemLanguages("en-US,ru-RU")
        verifyScreens(false)
    }

    @Test fun onlyPrimaryLanguageMattersForAnyRegion() {
        setSystemLanguages("de-DE,ru-RU")
        assertEquals("en",SystemLanguage.locale(app).language)
        setSystemLanguages("ru-KZ,en-US")
        assertEquals("ru",SystemLanguage.locale(app).language)
        setSystemLanguages("")
        assertEquals("en",SystemLanguage.locale(app).language)
    }

    @Test fun defaultJavaLocaleAndOldAppOverrideDoNotSelectLanguage() {
        val previous = Locale.getDefault()
        try {
            setSystemLanguages("en-US,ru-RU")
            Locale.setDefault(Locale.forLanguageTag("ru"))
            if (Build.VERSION.SDK_INT >= 33) {
                app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("ru")
            }
            assertEquals("en",SystemLanguage.context(app).resources.configuration.locales[0].language)
            SystemLanguage.clearApplicationOverride(app)
            if (Build.VERSION.SDK_INT >= 33) assertTrue(app.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
            verifyScreens(false)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun previousStatusAndNestedErrorsFollowSystemLanguageChanges() {
        val completed = ProfileJob.message
        val progress = UiText(R.string.job_left_directions,12,729)
        val failure = runCatching { AcousticProcessor.validateHead(7.0,20.0) }.exceptionOrNull()!!
        val error = UiText(R.string.job_failed,UiText.error(failure))
        assertTrue(completed.render(app).startsWith("Photos, marked points"))
        assertEquals("Left ear: calculating directions 12/729",progress.render(app))
        assertTrue(error.render(app).startsWith("Could not create profile: Check head dimensions"))
        setSystemLanguages("ru-KZ,en-US")
        assertTrue(completed.render(app).startsWith("Фотографии, разметка"))
        assertEquals("Левое ухо: расчёт направлений 12/729",progress.render(app))
        assertTrue(error.render(app).startsWith("Не удалось создать профиль: Проверьте размеры головы"))
        setSystemLanguages("de-DE,ru-RU")
        assertFalse(error.render(app).contains(cyrillic))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun realPhotoImportFailureCanBeTranslatedAfterTheWorkerFinishes() {
        val invalid = File(app.cacheDir,"invalid-photo.txt").apply { writeText("not an image") }
        try {
            ProfileJob.importPhoto(app,Uri.fromFile(invalid),"left")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (ProfileJob.running && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse("Photo import did not finish",ProfileJob.running)
            assertEquals("Could not load photo: Photo format is not supported.",ProfileJob.message.render(app))
            setSystemLanguages("ru-RU")
            assertEquals("Не удалось загрузить фото: Формат фотографии не поддерживается.",ProfileJob.message.render(app))
            setSystemLanguages("de-DE,ru-RU")
            assertEquals("Could not load photo: Photo format is not supported.",ProfileJob.message.render(app))
        } finally { invalid.delete() }
    }

    @Test fun alreadyOpenActivityRequestsRecreationOnResumeAfterSystemLanguageChanges() {
        val controller = Robolectric.buildActivity(ResumeProbeActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertEquals(0,activity.recreationRequests)
            controller.pause()
            setSystemLanguages("de-DE,ru-RU")
            controller.resume()
            assertEquals("Two system languages that both select English need no recreation",0,activity.recreationRequests)

            controller.pause()
            setSystemLanguages("ru-KZ,en-US")
            // Android may update Resources before onResume. The visible UI was built in English.
            val updated = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("ru-KZ"))
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(updated,activity.resources.displayMetrics)
            assertEquals("ru",activity.resources.configuration.locales[0].language)
            controller.resume()
            assertEquals("onResume must recreate the UI even if Resources already contain Russian",1,activity.recreationRequests)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recreationAfterSystemChangeKeepsHeadDimensionsAndPhotoMarks() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        val width = views(main.get().findViewById(android.R.id.content)).filterIsInstance<EditText>().first()
        width.setText("18.5")
        setSystemLanguages("ru-RU")
        main.recreate()
        assertTrue(texts(main.get()).contains("1 · Левое ухо"))
        assertEquals("18.5",views(main.get().findViewById(android.R.id.content)).filterIsInstance<EditText>().first().text.toString())
        main.pause().stop().destroy()

        val photo = Robolectric.buildActivity(PhotoActivity::class.java,photoIntent()).setup()
        val marker = views(photo.get().findViewById(android.R.id.content)).filterIsInstance<PhotoMarkView>().single()
        marker.points[0] = doubleArrayOf(10.0,12.0)
        setSystemLanguages("en-US,ru-RU")
        photo.recreate()
        assertTrue(texts(photo.get()).contains("Three points on the photo"))
        assertArrayEquals(doubleArrayOf(10.0,12.0),views(photo.get().findViewById(android.R.id.content)).filterIsInstance<PhotoMarkView>().single().points[0],0.0)
        photo.pause().stop().destroy()
    }
}
