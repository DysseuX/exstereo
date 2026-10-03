/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.musicplayer.R
import org.fossify.musicplayer.spatial.SpatialPreferences
import org.fossify.musicplayer.spatial.SpatialSettings
import org.fossify.musicplayer.views.SpatialSeekBar
import org.fossify.musicplayer.views.SpatialStyle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** App Views with demo data, rendered by Robolectric rather than a physical device. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w393dp-h851dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PublicationScreenshotsTest {
    @After fun reset() {
        SpatialPreferences.update(SpatialSettings())
        SpatialStyle.select(RuntimeEnvironment.getApplication(), "sky")
    }

    private fun field(activity: Activity, name: String): Any = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)!!

    private fun <T : Activity> activity(type: Class<T>): T {
        SpatialStyle.select(RuntimeEnvironment.getApplication(), "sky")
        val controller = Robolectric.buildActivity(type)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        type.getDeclaredMethod("buildInterface").apply { isAccessible = true }.invoke(activity)
        controller.visible()
        layout(activity)
        return activity
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    private fun layout(activity: Activity): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE).build())
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(851, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 393, 851)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return root
    }

    private fun scroll(root: View, target: View) {
        val parent = descendants(root).filterIsInstance<ScrollView>().first()
        val position = IntArray(2).also { target.getLocationInWindow(it) }
        val origin = IntArray(2).also { parent.getLocationInWindow(it) }
        parent.scrollTo(0, parent.scrollY + position[1] - origin[1] - 8)
    }

    private fun control(root: View, description: String) = descendants(root).first { it.contentDescription == description }
    private fun caption(root: View, text: String) = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == text }

    private fun save(root: View, name: String) {
        descendants(root).forEach { it.jumpDrawablesToCurrentState() }
        val visibleText = descendants(root).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text }
        assertFalse("Old app name is visible", Regex("spatial[ -]*(player|pleer|music)", RegexOption.IGNORE_CASE).containsMatchIn(visibleText))
        val english = root.resources.configuration.locales[0].language == "en"
        if (english) assertFalse("Russian text in an English preview", Regex("[А-Яа-яЁё]").containsMatchIn(visibleText))
        val suffix = if (english) "-en" else ""
        // Render the Android view tree at 3x scale.
        val bitmap = Bitmap.createBitmap(root.width * 3, root.height * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(3f, 3f) }
        root.draw(canvas)
        File("build/reports/publication-previews/$name$suffix.png").apply {
            parentFile?.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    @Test fun mainPlayerWithDemonstrationQueue() {
        SpatialPreferences.update(SpatialSettings(mode = 1))
        val player = activity(SpatialPlayerActivity::class.java)
        val english = player.resources.configuration.locales[0].language == "en"
        (field(player, "emptyPlayer") as View).visibility = View.GONE
        (field(player, "artistView") as TextView).text = if (english) "exstereo · demonstration" else "exstereo · демонстрация"
        (field(player, "titleView") as TextView).text = if (english) "Evening Route" else "Вечерний маршрут"
        (field(player, "positionView") as TextView).text = "1:24"
        (field(player, "durationView") as TextView).text = "4:08"
        (field(player, "seekBar") as SpatialSeekBar).apply { isEnabled = true; max = 248; progress = 84 }
        val rowClass = Class.forName("org.fossify.musicplayer.activities.SpatialPlayerActivity\$Row")
        val constructor = rowClass.declaredConstructors.first { it.parameterCount == 7 }.apply { isAccessible = true }
        val names = if (english) listOf("Evening Route", "Light in the Windows", "After the Rain", "Along the Coast", "Open Horizon",
            "Warm Wind", "Night City", "Slow Current", "Reflections", "At Dawn", "Distant Shore", "The Way Back")
        else listOf("Вечерний маршрут", "Свет в окнах", "После дождя", "Вдоль побережья", "Открытый горизонт",
            "Тёплый ветер", "Ночной город", "Медленное течение", "Отражения", "На рассвете", "Дальний берег", "Обратный путь")
        val action: () -> Unit = {}
        val rows = names.mapIndexed { i, name -> constructor.newInstance(name, if (english) "Demo" else "Демо", i == 0, null, false, "4:08", action) }
        val adapter = field(player, "playerQueueAdapter")
        adapter.javaClass.getDeclaredMethod("submit", List::class.java).apply { isAccessible = true }.invoke(adapter, rows)
        save(layout(player), "01-main-player")
        // The forum's topic wizard accepts a 120–200 px application icon.
        val icon = requireNotNull(player.getDrawable(R.mipmap.exstereo_launcher))
        val iconImage = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 180, 180)
        icon.draw(Canvas(iconImage))
        File("build/reports/publication-previews/exstereo-icon.png").outputStream().use {
            iconImage.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        iconImage.recycle()
    }

    @Test fun stereoExpansionAndSurroundControls() {
        SpatialPreferences.update(SpatialSettings(mode = 1, wideningAlgorithm = 6, mirrorSpeakers = true))
        val activity = activity(SpatialAudioActivity::class.java)
        val root = layout(activity)
        assertTrue(activity.findViewById<android.widget.RadioButton>(101).isChecked)
        save(root, "02-stereo-expansion")
        scroll(root, (control(root, activity.getString(R.string.audio_widening_algorithm)).parent as View))
        save(root, "03-multiband-stereo")
        val selector = control(root, activity.getString(R.string.audio_widening_algorithm)) as Spinner
        selector.setSelection(10)
        layout(activity)
        scroll(root, selector.parent as View)
        save(root, "04-micropitch")
        activity.findViewById<View>(102).performClick()
        layout(activity)
        descendants(root).filterIsInstance<ScrollView>().first().scrollTo(0, 0)
        assertEquals(2, SpatialPreferences.current.mode)
        assertTrue(activity.findViewById<android.widget.RadioButton>(102).isChecked)
        assertFalse(activity.findViewById<android.widget.RadioButton>(101).isChecked)
        save(root, "05-surround-engines")
        scroll(root, caption(root, activity.getString(R.string.audio_virtual_speakers)))
        save(root, "06-surround-speakers")
        control(root, activity.getString(R.string.audio_hrtf_open)).performClick()
        layout(activity)
        scroll(root, control(root, activity.getString(R.string.audio_hrtf_close)))
        save(root, "07-hrtf")
        control(root, activity.getString(R.string.audio_room_open)).performClick()
        layout(activity)
        scroll(root, control(root, activity.getString(R.string.audio_room_close)))
        save(root, "08-room")
    }

    @Test @Config(qualifiers = "en-rUS-w393dp-h851dp-port-notnight-mdpi")
    fun englishMainPlayerWithDemonstrationQueue() = mainPlayerWithDemonstrationQueue()

    @Test @Config(qualifiers = "en-rUS-w393dp-h851dp-port-notnight-mdpi")
    fun englishStereoExpansionAndSurroundControls() = stereoExpansionAndSurroundControls()
}
