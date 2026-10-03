/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.musicplayer.R
import org.fossify.musicplayer.spatial.*
import org.fossify.musicplayer.views.SpatialStyle
import java.util.concurrent.Executors
import kotlin.math.*

/** Spatial audio and widening settings. */
@SuppressLint("SetTextI18n")
class SpatialAudioActivity : SimpleControllerActivity() {
    private val ink get() = SpatialStyle.ink
    private val accent get() = SpatialStyle.accent
    private var renderedThemeId = ""
    private var selectedSpeaker = 0
    private var hrtfSelectionGeneration = 0
    private val hrtfWorker = Executors.newSingleThreadExecutor()
    private var configurationBusy = false
    private lateinit var rendererSelector: Spinner
    private lateinit var upmixerSelector: Spinner
    private lateinit var rendererDescription: TextView
    private lateinit var resetPositions: Button
    private val calibrationRefreshers = mutableListOf<() -> Unit>()
    private var hrtfChoices = emptyList<HrtfProfiles.Profile>()
    private lateinit var hrtfSelector: Spinner
    private lateinit var hrtfImportButton: Button
    private lateinit var hrtfImportStatus: TextView
    private val chooseSofa = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSofa(uri)
    }
    private lateinit var speakerSelector: Spinner
    private lateinit var scene: SpeakerSceneView
    private lateinit var angleText: TextView
    private lateinit var elevation: SeekBar
    private lateinit var azimuth: SeekBar
    private lateinit var distance: SeekBar
    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpatialPreferences.initialize(this)
        buildInterface()
    }

    private fun buildInterface() {
        renderedThemeId = SpatialStyle.current.id
        calibrationRefreshers.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SpatialStyle.background)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("‹").apply { textSize = 28f; contentDescription = getString(R.string.audio_back); setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        header.addView(label(getString(R.string.audio_title), 22).apply {
            setPadding(dp(12), dp(8), 0, dp(8)); minHeight = dp(76)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val modeCard = card().apply { addView(label(getString(R.string.audio_listening_mode), 18)) }
        val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(getString(R.string.audio_mode_stereo), getString(R.string.audio_mode_widen), getString(R.string.audio_mode_surround)).forEachIndexed { index, title ->
            modes.addView(RadioButton(this).apply {
                id = 100 + index; text = title; textSize = 17f; setTextColor(ink)
                minHeight = dp(52); isChecked = SpatialPreferences.current.mode == index
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, SpatialStyle.secondary))
                background = SpatialStyle.ripple(this@SpatialAudioActivity, Color.TRANSPARENT, 16)
                setPadding(dp(4), dp(2), dp(8), dp(2))
            })
        }
        modeCard.addView(modes)
        body.addView(modeCard)

        val widthPanel = buildWideningControls()
        body.addView(widthPanel)

        val stereoCard = card()
        val virtualStereo = CheckBox(this).apply {
            text = getString(R.string.audio_virtual_stereo)
            textSize = 17f; setTextColor(ink); minHeight = dp(56)
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = SpatialPreferences.current.virtualStereo
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        stereoCard.addView(virtualStereo)
        stereoCard.addView(label(getString(R.string.audio_virtual_stereo_description), 14))
        body.addView(stereoCard)
        body.addView(buildRoomControls())

        val spatialPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val engineCard = card()
        spatialPanel.addView(engineCard)
        engineCard.addView(label(getString(R.string.audio_renderer), 18))
        rendererSelector = selector().apply {
            contentDescription = getString(R.string.audio_renderer)
            adapter = textAdapter(SpatialSettings.rendererNames)
            setSelection(SpatialPreferences.current.renderer)
            onItemSelectedListener = selectionListener { position ->
                if (!configurationBusy && position != SpatialPreferences.current.renderer) {
                    val current = SpatialPreferences.current
                    val candidate = current.copy(renderer = position).withCompatibleHrtf()
                    val message = if (position == SpatialSettings.RENDERER_SOFALIZER && current.hrtf == "default")
                        getString(R.string.audio_sofalizer_kemar_selected)
                    else getString(R.string.audio_renderer_selected, SpatialSettings.rendererNames[position])
                    selectConfiguration(candidate, message)
                }
            }
        }
        engineCard.addView(rendererSelector, LinearLayout.LayoutParams(-1, -2))
        rendererDescription = label("", 14)
        engineCard.addView(rendererDescription)
        val upmixerCard = card()
        spatialPanel.addView(upmixerCard)
        upmixerCard.addView(label(getString(R.string.audio_upmix_title), 18))
        upmixerSelector = selector().apply {
            contentDescription = getString(R.string.audio_upmix_accessibility)
            adapter = textAdapter(SpatialSettings.upmixerNames)
            setSelection(SpatialPreferences.current.upmixer)
            onItemSelectedListener = selectionListener { position ->
                if (!configurationBusy && position != SpatialPreferences.current.upmixer)
                    selectConfiguration(SpatialPreferences.current.copy(upmixer = position),
                        getString(R.string.audio_upmixer_selected, SpatialSettings.upmixerNames[position]))
            }
        }
        upmixerCard.addView(upmixerSelector, LinearLayout.LayoutParams(-1, -2))
        upmixerCard.addView(label(getString(R.string.audio_upmix_description), 14))

        val speakerCard = card()
        spatialPanel.addView(speakerCard)
        speakerCard.addView(label(getString(R.string.audio_virtual_speakers), 18))
        speakerCard.addView(CheckBox(this).apply {
            text = getString(R.string.audio_mirror_pair)
            contentDescription = getString(R.string.audio_mirror_pair_accessibility)
            textSize = 17f; setTextColor(ink); minHeight = dp(56)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isChecked = SpatialPreferences.current.mirrorSpeakers
            setOnCheckedChangeListener { _, checked -> change { copy(mirrorSpeakers = checked) } }
        })
        speakerCard.addView(label(getString(R.string.audio_mirror_description), 14))
        scene = SpeakerSceneView(this) { index, angle, meters ->
            selectedSpeaker = index
            updatePosition(azimuthValue = angle, distanceValue = meters)
        }
        speakerCard.addView(scene, LinearLayout.LayoutParams(-1, dp(300)))
        speakerCard.addView(label(getString(R.string.audio_scene_description), 14))
        speakerSelector = selector().apply { contentDescription = getString(R.string.audio_speaker) }
        speakerCard.addView(speakerSelector, LinearLayout.LayoutParams(-1, -2))
        angleText = label("")
        speakerCard.addView(angleText)
        azimuth = slider(360, 150) { value -> updatePosition(azimuthValue = value - 180f) }
        elevation = slider(120, 60) { value -> updatePosition(elevationValue = value - 60f) }
        distance = slider(90, 10) { value -> updatePosition(distanceValue = .5f + value * .05f) }
        azimuth.contentDescription = getString(R.string.audio_azimuth_accessibility)
        elevation.contentDescription = getString(R.string.audio_elevation_accessibility)
        distance.contentDescription = getString(R.string.audio_distance_accessibility)
        speakerCard.addView(label(getString(R.string.audio_azimuth), 14)); speakerCard.addView(azimuth)
        speakerCard.addView(label(getString(R.string.audio_elevation), 14)); speakerCard.addView(elevation)
        speakerCard.addView(label(getString(R.string.audio_distance_range), 14)); speakerCard.addView(distance)
        speakerCard.addView(label(getString(R.string.audio_distance_description), 14))
        speakerSelector.onItemSelectedListener = selectionListener { position ->
            if (position in activeSpeakers().indices) { selectedSpeaker = position; refreshSpeaker() }
        }
        resetPositions = action(getString(R.string.audio_reset_positions)).apply {
            setOnClickListener {
                change {
                    if (mode == 2) copy(speakers = SpatialSettings.defaultSpeakers())
                    else copy(stereoSpeakers = SpatialSettings.defaultStereoSpeakers())
                }
                refreshSpeaker()
            }
        }
        speakerCard.addView(resetPositions)

        val hrtfCard = card()
        spatialPanel.addView(hrtfCard)
        val hrtfDetails = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val hrtfToggle = action(getString(R.string.audio_hrtf_collapsed)).apply {
            contentDescription = getString(R.string.audio_hrtf_open)
            setOnClickListener {
                val expanded = hrtfDetails.visibility != View.VISIBLE
                hrtfDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) getString(R.string.audio_hrtf_expanded) else getString(R.string.audio_hrtf_collapsed)
                contentDescription = if (expanded) getString(R.string.audio_hrtf_close) else getString(R.string.audio_hrtf_open)
            }
        }
        hrtfCard.addView(hrtfToggle)
        hrtfCard.addView(label(getString(R.string.audio_hrtf_summary), 14))
        hrtfCard.addView(hrtfDetails)
        hrtfDetails.addView(label(getString(R.string.audio_head_profile), 18))
        hrtfSelector = selector().apply { contentDescription = getString(R.string.audio_hrtf_profile) }
        refreshHrtfChoices()
        hrtfSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!hrtfSelector.isEnabled) return
                val profile = hrtfChoices.getOrNull(position) ?: return
                if (SpatialPreferences.current.hrtf == profile.id) return
                selectConfiguration(SpatialPreferences.current.copy(hrtf = profile.id),
                    getString(R.string.audio_profile_selected, profile.title))
            }
        }
        hrtfDetails.addView(hrtfSelector, LinearLayout.LayoutParams(-1, -2))
        hrtfImportButton = action(getString(R.string.audio_import_sofa)).apply {
            setOnClickListener {
                // Many file providers report .sofa as application/octet-stream.
                // The native loader validates the contents after selection.
                try { chooseSofa.launch(arrayOf("*/*")) }
                catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this@SpatialAudioActivity, getString(R.string.audio_no_file_picker), Toast.LENGTH_LONG).show()
                }
            }
        }
        hrtfDetails.addView(hrtfImportButton)
        hrtfImportStatus = label(getString(R.string.audio_import_instructions), 14)
        hrtfDetails.addView(hrtfImportStatus)
        hrtfDetails.addView(label(getString(R.string.audio_compare_profiles), 14))
        hrtfDetails.addView(label(getString(R.string.audio_manual_calibration), 18))
        hrtfDetails.addView(label(getString(R.string.audio_calibration_description), 14))
        calibrationControl(hrtfDetails, getString(R.string.audio_hrtf_strength), 100, { (hrtfBlend * 100).roundToInt() },
            { getString(R.string.audio_hrtf_strength_value, it) }) { copy(hrtfBlend = it / 100f) }
        hrtfDetails.addView(label(getString(R.string.audio_hrtf_strength_description), 14))
        calibrationControl(hrtfDetails, getString(R.string.audio_listener_yaw), 360, { listenerYaw.roundToInt() + 180 },
            { getString(R.string.audio_listener_yaw_value, it - 180) }) { copy(listenerYaw = it - 180f) }
        calibrationControl(hrtfDetails, getString(R.string.audio_listener_pitch), 120, { listenerPitch.roundToInt() + 60 },
            { getString(R.string.audio_listener_pitch_value, it - 60) }) { copy(listenerPitch = it - 60f) }
        calibrationControl(hrtfDetails, getString(R.string.audio_hrtf_balance), 120, { (hrtfBalanceDb * 10).roundToInt() + 60 },
            { getString(R.string.audio_hrtf_balance_value, signedDb((it - 60) / 10f)) }) { copy(hrtfBalanceDb = (it - 60) / 10f) }
        calibrationControl(hrtfDetails, getString(R.string.audio_hrtf_level), 180, { (hrtfGainDb * 10).roundToInt() + 120 },
            { getString(R.string.audio_hrtf_level_value, signedDb((it - 120) / 10f)) }) { copy(hrtfGainDb = (it - 120) / 10f) }
        hrtfDetails.addView(action(getString(R.string.audio_reset_calibration)).apply {
            setOnClickListener {
                change { copy(hrtfBlend = 1f, listenerYaw = 0f, listenerPitch = 0f, hrtfBalanceDb = 0f, hrtfGainDb = 0f) }
                calibrationRefreshers.forEach { it() }; scene.invalidate()
            }
        })
        val levelsCard = card()
        spatialPanel.addView(levelsCard)
        levelsCard.addView(label(getString(R.string.audio_channel_levels), 18))
        val surroundLabel = label(getString(R.string.audio_surround_value, SpatialPreferences.current.surroundDb.roundToInt()))
        levelsCard.addView(surroundLabel)
        levelsCard.addView(slider(30, SpatialPreferences.current.surroundDb.roundToInt() + 30) { value ->
            change { copy(surroundDb = value - 30f) }; surroundLabel.text = getString(R.string.audio_surround_value, value - 30)
        })
        val lfeLabel = label(getString(R.string.audio_lfe_value, SpatialPreferences.current.lfeDb.roundToInt()))
        levelsCard.addView(lfeLabel)
        levelsCard.addView(slider(30, SpatialPreferences.current.lfeDb.roundToInt() + 30) { value ->
            change { copy(lfeDb = value - 30f) }; lfeLabel.text = getString(R.string.audio_lfe_value, value - 30)
        })
        levelsCard.addView(label(getString(R.string.audio_lfe_description), 14))
        body.addView(spatialPanel)
        fun showMode() {
            val settings = SpatialPreferences.current
            val surround = settings.mode == 2
            widthPanel.visibility = if (settings.mode == 1) View.VISIBLE else View.GONE
            stereoCard.visibility = if (surround) View.GONE else View.VISIBLE
            spatialPanel.visibility = if (surround || settings.virtualStereo) View.VISIBLE else View.GONE
            upmixerCard.visibility = if (surround) View.VISIBLE else View.GONE
            levelsCard.visibility = if (surround) View.VISIBLE else View.GONE
            speakerSelector.adapter = textAdapter(activeSpeakerNames())
            selectedSpeaker = selectedSpeaker.coerceIn(activeSpeakers().indices)
            scene.contentDescription = if (surround)
                getString(R.string.audio_scene_seven_accessibility)
            else getString(R.string.audio_scene_two_accessibility)
            refreshSpeaker()
        }
        modes.setOnCheckedChangeListener { _, checked ->
            if (checked in 100..102) { change { copy(mode = checked - 100) }; selectedSpeaker = 0; showMode() }
        }
        virtualStereo.setOnCheckedChangeListener { _, checked -> change { copy(virtualStereo = checked) }; showMode() }
        refreshBackendControls(); showMode()
    }

    private fun buildWideningControls(): LinearLayout {
        val panel = card()
        panel.addView(label(getString(R.string.audio_mode_widen), 18))
        val algorithm = selector().apply {
            contentDescription = getString(R.string.audio_widening_algorithm)
            adapter = textAdapter(SpatialSettings.wideningAlgorithmNames)
            setSelection(SpatialPreferences.current.wideningAlgorithm)
        }
        panel.addView(algorithm, LinearLayout.LayoutParams(-1, -2))
        val description = label("", 14).apply { contentDescription = getString(R.string.audio_widening_description_accessibility) }
        panel.addView(description)

        val msControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val widthLabel = label("")
        val width = slider(200, (SpatialPreferences.current.width * 100).roundToInt()) { value ->
            change { copy(width = value / 100f) }; widthLabel.text = getString(R.string.audio_width_value, value)
        }.apply { contentDescription = getString(R.string.audio_width_accessibility) }
        widthLabel.text = getString(R.string.audio_width_value, width.progress)
        msControls.addView(widthLabel); msControls.addView(width)
        msControls.addView(label(getString(R.string.audio_width_description), 14))
        panel.addView(msControls)

        val mixControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val amountLabel = label("")
        val amount = slider(100, (SpatialPreferences.current.wideningAmount * 100).roundToInt()) { value ->
            change { copy(wideningAmount = value / 100f) }; amountLabel.text = getString(R.string.audio_wet_value, value)
        }.apply { contentDescription = getString(R.string.audio_wet_accessibility) }
        amountLabel.text = getString(R.string.audio_wet_value, amount.progress)
        mixControls.addView(amountLabel); mixControls.addView(amount)
        mixControls.addView(label(getString(R.string.audio_wet_description), 14))
        panel.addView(mixControls)

        val widenerControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun bandControl(title: String, initial: Float, update: SpatialSettings.(Float) -> SpatialSettings) {
            val valueLabel = label(getString(R.string.audio_label_percent, title, (initial * 100).roundToInt()))
            val bar = slider(100, (initial * 100).roundToInt()) { value ->
                change { update(value / 100f) }; valueLabel.text = getString(R.string.audio_label_percent, title, value)
            }.apply { contentDescription = title }
            widenerControls.addView(valueLabel); widenerControls.addView(bar)
        }
        bandControl(getString(R.string.audio_widen_below_crossover), SpatialPreferences.current.widenerLow) { copy(widenerLow = it) }
        bandControl(getString(R.string.audio_widen_above_crossover), SpatialPreferences.current.widenerHigh) { copy(widenerHigh = it) }
        val crossoverLabel = label(getString(R.string.audio_crossover_value, SpatialPreferences.current.widenerCrossover.roundToInt()))
        val crossover = slider(100, (100f * ln(SpatialPreferences.current.widenerCrossover / 200f) / ln(20f)).roundToInt()) { value ->
            val frequency = 200f * 20f.pow(value / 100f)
            change { copy(widenerCrossover = frequency) }; crossoverLabel.text = getString(R.string.audio_crossover_value, frequency.roundToInt())
        }.apply { contentDescription = getString(R.string.audio_crossover_accessibility) }
        widenerControls.addView(crossoverLabel); widenerControls.addView(crossover)
        widenerControls.addView(label(getString(R.string.audio_widener_band_description), 14))
        fun option(title: String, initial: Boolean, update: SpatialSettings.(Boolean) -> SpatialSettings) = Switch(this).apply {
            text = title; contentDescription = title; textSize = 16f; setTextColor(ink)
            minHeight = dp(56); setPadding(dp(2), dp(8), dp(2), dp(8)); switchPadding = dp(12)
            isChecked = initial
            thumbTintList = ColorStateList.valueOf(accent)
            trackTintList = ColorStateList.valueOf(SpatialStyle.selected)
            setOnCheckedChangeListener { _, checked -> change { update(checked) } }
        }
        widenerControls.addView(option(getString(R.string.audio_transient_protection), SpatialPreferences.current.transientProtection) { copy(transientProtection = it) })
        widenerControls.addView(label(getString(R.string.audio_transient_description), 14))
        widenerControls.addView(option(getString(R.string.audio_preserve_amplitude), SpatialPreferences.current.widenerAmplitudePreserving) {
            copy(widenerAmplitudePreserving = it)
        })
        widenerControls.addView(label(getString(R.string.audio_crossover_type_description), 14))
        panel.addView(widenerControls)

        val extraGroups = mutableMapOf<Int, LinearLayout>()
        fun group(id: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; extraGroups[id] = this; panel.addView(this)
        }
        val bands = group(6)
        liveSlider(bands, getString(R.string.audio_low_band_width), 0f, 2f, SpatialPreferences.current.bandLowWidth,
            steps = 200, format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(bandLowWidth = it) }
        liveSlider(bands, getString(R.string.audio_mid_band_width), 0f, 2f, SpatialPreferences.current.bandMidWidth,
            steps = 200, format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(bandMidWidth = it) }
        liveSlider(bands, getString(R.string.audio_high_band_width), 0f, 2f, SpatialPreferences.current.bandHighWidth,
            steps = 200, format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(bandHighWidth = it) }
        liveSlider(bands, getString(R.string.audio_low_crossover), 80f, 1000f, SpatialPreferences.current.bandLowCrossover,
            logarithmic = true, format = { getString(R.string.audio_hz_value, it.roundToInt()) }) { copy(bandLowCrossover = it) }
        liveSlider(bands, getString(R.string.audio_high_crossover), 1500f, 8000f, SpatialPreferences.current.bandHighCrossover,
            logarithmic = true, format = { getString(R.string.audio_hz_value, it.roundToInt()) }) { copy(bandHighCrossover = it) }
        bands.addView(option(getString(R.string.audio_correlation_guard), SpatialPreferences.current.correlationGuard) { copy(correlationGuard = it) })
        bands.addView(label(getString(R.string.audio_correlation_description), 14))
        val ambience = group(7)
        liveSlider(ambience, getString(R.string.audio_ambience_width), 0f, 3f, SpatialPreferences.current.paeAmbientWidth,
            steps = 300, format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(paeAmbientWidth = it) }
        liveSlider(ambience, getString(R.string.audio_ambience_level), -12f, 6f, SpatialPreferences.current.paeAmbienceGainDb,
            steps = 180, format = { getString(R.string.audio_db_value, signedDb(it)) }) { copy(paeAmbienceGainDb = it) }
        ambience.addView(label(getString(R.string.audio_ambience_description), 14))
        val synthesis = group(8)
        liveSlider(synthesis, getString(R.string.audio_side_level), 0f, 1f, SpatialPreferences.current.sideSynthesisAmount,
            format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(sideSynthesisAmount = it) }
        liveSlider(synthesis, getString(R.string.audio_side_highpass), 20f, 1000f, SpatialPreferences.current.sideHighpass,
            logarithmic = true, format = { getString(R.string.audio_hz_value, it.roundToInt()) }) { copy(sideHighpass = it) }
        synthesis.addView(label(getString(R.string.audio_side_description), 14))
        val shuffler = group(9)
        liveSlider(shuffler, getString(R.string.audio_shuffler_amount), -1f, 1f, SpatialPreferences.current.shuffleAmount,
            steps = 200, format = { getString(R.string.audio_signed_percent_value, (it * 100).roundToInt()) }) { copy(shuffleAmount = it) }
        liveSlider(shuffler, getString(R.string.audio_shuffler_frequency), 100f, 2000f, SpatialPreferences.current.shuffleFrequency,
            logarithmic = true, format = { getString(R.string.audio_hz_value, it.roundToInt()) }) { copy(shuffleFrequency = it) }
        val micropitch = group(10)
        liveSlider(micropitch, getString(R.string.audio_micropitch_detune), 0f, 20f, SpatialPreferences.current.microCents,
            steps = 200, format = { getString(R.string.audio_cents_value, decimal(it)) }) { copy(microCents = it) }
        liveSlider(micropitch, getString(R.string.audio_micropitch_delay), 5f, 30f, SpatialPreferences.current.microDelayMs,
            steps = 250, format = { getString(R.string.audio_ms_value, decimal(it)) }) { copy(microDelayMs = it) }
        micropitch.addView(label(getString(R.string.audio_micropitch_description), 14))

        fun refreshAlgorithm() {
            val selected = SpatialPreferences.current.wideningAlgorithm
            msControls.visibility = if (selected == 0) View.VISIBLE else View.GONE
            mixControls.visibility = if (selected == 0) View.GONE else View.VISIBLE
            widenerControls.visibility = if (selected in 4..5) View.VISIBLE else View.GONE
            extraGroups.forEach { (id, controls) -> controls.visibility = if (selected == id) View.VISIBLE else View.GONE }
            description.text = when (selected) {
                1 -> getString(R.string.audio_algorithm_extrastereo_description)
                2 -> getString(R.string.audio_algorithm_stereowiden_description)
                3 -> getString(R.string.audio_algorithm_haas_description)
                4 -> getString(R.string.audio_algorithm_allpass_description)
                5 -> getString(R.string.audio_algorithm_velvet_description)
                6 -> getString(R.string.audio_algorithm_multiband_description)
                7 -> getString(R.string.audio_algorithm_pae_description)
                8 -> getString(R.string.audio_algorithm_side_description)
                9 -> getString(R.string.audio_algorithm_shuffler_description)
                10 -> getString(R.string.audio_algorithm_micropitch_description)
                else -> getString(R.string.audio_algorithm_ms_description)
            }
        }
        algorithm.onItemSelectedListener = selectionListener { selected ->
            if (selected in SpatialSettings.wideningAlgorithmNames.indices) {
                if (selected != SpatialPreferences.current.wideningAlgorithm) change { copy(wideningAlgorithm = selected) }
                refreshAlgorithm()
            }
        }
        refreshAlgorithm()
        return panel
    }

    private fun buildRoomControls(): LinearLayout {
        val panel = card()
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        panel.addView(action(getString(R.string.audio_room_collapsed)).apply {
            contentDescription = getString(R.string.audio_room_open)
            setOnClickListener {
                val expanded = details.visibility != View.VISIBLE
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) getString(R.string.audio_room_expanded) else getString(R.string.audio_room_collapsed)
                contentDescription = if (expanded) getString(R.string.audio_room_close) else getString(R.string.audio_room_open)
            }
        })
        panel.addView(label(getString(R.string.audio_room_summary), 14))
        panel.addView(details)
        details.addView(Switch(this).apply {
            text = getString(R.string.audio_room_enable); contentDescription = text; textSize = 16f; setTextColor(ink)
            minHeight = dp(56); setPadding(dp(2), dp(8), dp(2), dp(8)); switchPadding = dp(12)
            thumbTintList = ColorStateList.valueOf(accent); trackTintList = ColorStateList.valueOf(SpatialStyle.selected)
            isChecked = SpatialPreferences.current.roomEnabled
            setOnCheckedChangeListener { _, checked -> change { copy(roomEnabled = checked) } }
        })
        liveSlider(details, getString(R.string.audio_room_mix), 0f, 1f, SpatialPreferences.current.roomMix,
            format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(roomMix = it) }
        liveSlider(details, getString(R.string.audio_room_minimum_size), 2f, 12f, SpatialPreferences.current.roomSize,
            steps = 100, format = { getString(R.string.audio_meters_value, decimal(it)) }) { copy(roomSize = it) }
        details.addView(label(getString(R.string.audio_room_walls_description), 14))
        liveSlider(details, getString(R.string.audio_room_damping), 0f, 1f, SpatialPreferences.current.roomDamping,
            format = { getString(R.string.audio_percent_value, (it * 100).roundToInt()) }) { copy(roomDamping = it) }
        liveSlider(details, getString(R.string.audio_room_decay), .1f, 1.5f, SpatialPreferences.current.roomDecay,
            steps = 140, format = { getString(R.string.audio_seconds_value, decimal(it, 2)) }) { copy(roomDecay = it) }
        details.addView(label(getString(R.string.audio_room_description), 14))
        return panel
    }

    private fun liveSlider(parent: LinearLayout, title: String, minimum: Float, maximum: Float, initial: Float,
                           steps: Int = 100, logarithmic: Boolean = false, format: (Float) -> String,
                           update: SpatialSettings.(Float) -> SpatialSettings) {
        val valueLabel = label(getString(R.string.audio_label_value, title, format(initial)))
        val position = if (logarithmic) ln(initial / minimum) / ln(maximum / minimum)
            else (initial - minimum) / (maximum - minimum)
        val bar = slider(steps, (position * steps).roundToInt()) { value ->
            val ratio = value.toFloat() / steps
            val next = if (logarithmic) minimum * (maximum / minimum).pow(ratio)
                else minimum + (maximum - minimum) * ratio
            change { update(next) }; valueLabel.text = getString(R.string.audio_label_value, title, format(next))
        }.apply { contentDescription = title }
        parent.addView(valueLabel); parent.addView(bar)
    }

    private fun decimal(value: Float, digits: Int = 1) =
        String.format(resources.configuration.locales[0], "%.${digits}f", value)

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        SpatialStyle.initialize(this)
        if (renderedThemeId != SpatialStyle.current.id) buildInterface()
        // Older Android versions use theme backgrounds rather than enforced edge-to-edge.
        window.statusBarColor = SpatialStyle.background
        window.navigationBarColor = SpatialStyle.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onDestroy() {
        ++hrtfSelectionGeneration
        hrtfWorker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshHrtfChoices() {
        val settings = SpatialPreferences.current
        hrtfChoices = when (settings.renderer) {
            SpatialSettings.RENDERER_OPENAL -> listOf(HrtfProfiles.Profile("openal_builtin", getString(R.string.audio_openal_profile)))
            SpatialSettings.RENDERER_SOFALIZER -> HrtfProfiles.entries(applicationContext).filter { it.id != "default" }
            else -> HrtfProfiles.entries(applicationContext)
        }
        hrtfSelector.adapter = textAdapter(hrtfChoices.map { it.title })
        hrtfSelector.setSelection(hrtfChoices.indexOfFirst { it.id == settings.hrtf }.coerceAtLeast(0))
    }

    private fun refreshBackendControls() {
        val settings = SpatialPreferences.current
        val openAl = settings.renderer == SpatialSettings.RENDERER_OPENAL
        rendererSelector.isEnabled = !configurationBusy
        upmixerSelector.isEnabled = !configurationBusy
        hrtfSelector.isEnabled = !configurationBusy && !openAl
        hrtfImportButton.isEnabled = !configurationBusy && !openAl
        // Validation replaces structural fields only; geometry and calibration stay live.
        listOf(rendererSelector, upmixerSelector, hrtfSelector, hrtfImportButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.5f
        }
        rendererDescription.text = when (settings.renderer) {
            SpatialSettings.RENDERER_OPENAL -> getString(R.string.audio_renderer_openal_description)
            SpatialSettings.RENDERER_SOFALIZER -> getString(R.string.audio_renderer_sofalizer_description)
            else -> getString(R.string.audio_renderer_steam_description)
        }
    }

    private fun selectionListener(selected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    }

    /** Validate the selected renderer before applying its configuration. */
    private fun validateConfiguration(settings: SpatialSettings) {
        var handle = 0L
        try {
            handle = NativeSpatialEngine.createForSettings(48000, applicationContext, settings)
            check(handle != 0L) { getString(R.string.audio_validation_failed) }
        } finally {
            if (handle != 0L) NativeSpatialEngine.release(handle)
        }
    }

    private fun selectConfiguration(candidate: SpatialSettings, successMessage: String) {
        if (configurationBusy) return
        val selected = candidate.withCompatibleHrtf()
        val generation = ++hrtfSelectionGeneration
        configurationBusy = true
        refreshBackendControls()
        hrtfImportStatus.text = getString(R.string.audio_validating_configuration, SpatialSettings.rendererNames[selected.renderer])
        hrtfWorker.execute {
            try {
                validateConfiguration(selected)
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != hrtfSelectionGeneration) return@runOnUiThread
                    // Geometry, calibration and listening mode may have changed
                    // during validation. Only publish the three configuration fields.
                    change { copy(renderer = selected.renderer, upmixer = selected.upmixer, hrtf = selected.hrtf) }
                    finishConfigurationChange(successMessage)
                    refreshPlaybackHrtf()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != hrtfSelectionGeneration) return@runOnUiThread
                    finishConfigurationChange(getString(R.string.audio_changes_not_applied,
                        error.message ?: getString(R.string.audio_profile_load_failed)))
                    Toast.makeText(this@SpatialAudioActivity, getString(R.string.audio_settings_apply_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun finishConfigurationChange(message: String) {
        // Keep listeners blocked while rebuilding adapters/selections.
        rendererSelector.setSelection(SpatialPreferences.current.renderer)
        upmixerSelector.setSelection(SpatialPreferences.current.upmixer)
        refreshHrtfChoices()
        hrtfImportStatus.text = message
        configurationBusy = false
        refreshBackendControls()
    }

    private fun importSofa(uri: Uri) {
        if (configurationBusy || SpatialPreferences.current.renderer == SpatialSettings.RENDERER_OPENAL) return
        val original = SpatialPreferences.current
        val generation = ++hrtfSelectionGeneration
        configurationBusy = true
        refreshBackendControls()
        hrtfImportStatus.text = getString(R.string.audio_copying_sofa)
        hrtfWorker.execute {
            try {
                val profile = HrtfProfiles.importDocument(applicationContext, uri)
                // importDocument validates Steam compatibility; the additional
                // selected-engine load catches sofalizer-specific constraints.
                validateConfiguration(original.copy(hrtf = profile.id))
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != hrtfSelectionGeneration) return@runOnUiThread
                    change { copy(hrtf = profile.id) }
                    finishConfigurationChange(getString(R.string.audio_profile_saved_selected, profile.title))
                    refreshPlaybackHrtf()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != hrtfSelectionGeneration) return@runOnUiThread
                    finishConfigurationChange(getString(R.string.audio_active_profile_unchanged,
                        error.message ?: getString(R.string.audio_sofa_import_failed)))
                    Toast.makeText(this@SpatialAudioActivity, getString(R.string.audio_profile_unchanged), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshPlaybackHrtf() = withPlayer {
        if (!refreshSpatialHrtf()) {
            Toast.makeText(this@SpatialAudioActivity, getString(R.string.audio_settings_deferred), Toast.LENGTH_LONG).show()
        }
    }

    private fun textAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(ink); textSize = 16f; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(4), dp(12), dp(4))
                setSingleLine(false); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                val arrow = context.getDrawable(android.R.drawable.arrow_down_float)?.mutate()?.apply { setTint(accent) }
                setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
                compoundDrawablePadding = dp(8)
            }
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(ink); textSize = 16f; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(SpatialStyle.card)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                minHeight = dp(56); setSingleLine(false); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            }
    }

    private fun label(value: String, size: Int = 16) = TextView(this).apply {
        text = value; textSize = size.toFloat()
        setTextColor(if (size < 16) SpatialStyle.secondary else ink)
        gravity = Gravity.CENTER_VERTICAL
        if (size >= 18) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = SpatialStyle.rounded(this@SpatialAudioActivity, SpatialStyle.card, 26)
        setPadding(dp(16), dp(10), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun selector() = Spinner(this, Spinner.MODE_DROPDOWN).apply {
        backgroundTintList = null
        background = SpatialStyle.rounded(this@SpatialAudioActivity, SpatialStyle.panel, 17)
        setPopupBackgroundDrawable(SpatialStyle.rounded(this@SpatialAudioActivity, SpatialStyle.card, 18))
        minimumHeight = dp(56)
    }

    private fun action(title: String) = Button(this).apply {
        text = title; textSize = 15f; isAllCaps = false
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(SpatialStyle.secondary, ink)))
        backgroundTintList = null
        background = SpatialStyle.ripple(this@SpatialAudioActivity, SpatialStyle.selected, 20)
        stateListAnimator = null
        minimumHeight = dp(52)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
    }

    private fun slider(maximum: Int, value: Int, changed: (Int) -> Unit) = SeekBar(this).apply {
        max = maximum; progress = value; minimumHeight = dp(48)
        // ProgressBar's themed maxHeight can override View.minimumHeight in
        // wrap-content measurement. Give the touch target an explicit height.
        layoutParams = LinearLayout.LayoutParams(-1, dp(48))
        setPadding(dp(10), 0, dp(10), 0)
        progressTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(SpatialStyle.line)
        thumbTintList = ColorStateList.valueOf(accent)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) changed(progress) }
            override fun onStartTrackingTouch(bar: SeekBar?) { parent.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(bar: SeekBar?) { parent.requestDisallowInterceptTouchEvent(false) }
        })
    }

    private fun change(update: SpatialSettings.() -> SpatialSettings) = SpatialPreferences.update(SpatialPreferences.current.update())
    private fun calibrationControl(
        parent: LinearLayout,
        description: String,
        maximum: Int,
        read: SpatialSettings.() -> Int,
        format: (Int) -> String,
        update: SpatialSettings.(Int) -> SpatialSettings
    ) {
        val text = label("")
        val bar = slider(maximum, SpatialPreferences.current.read()) { value ->
            change { update(value) }; text.text = format(value); scene.invalidate()
        }.apply { contentDescription = description }
        parent.addView(text); parent.addView(bar)
        val refresh = { val value = SpatialPreferences.current.read(); bar.progress = value; text.text = format(value) }
        calibrationRefreshers += refresh
        refresh()
    }

    private fun signedDb(value: Float) = String.format(resources.configuration.locales[0], "%+.1f", value)
    private fun activeSpeakers() = SpatialPreferences.current.editableSpeakers()
    private fun activeSpeakerNames() = if (SpatialPreferences.current.mode == 2)
        SpatialSettings.speakerNames else listOf(getString(R.string.audio_left_speaker), getString(R.string.audio_right_speaker))

    private fun updatePosition(azimuthValue: Float? = null, elevationValue: Float? = null, distanceValue: Float? = null) {
        val positions = activeSpeakers()
        if (selectedSpeaker !in positions.indices) return
        val old = positions[selectedSpeaker]
        val next = old.copy(azimuth = azimuthValue ?: old.azimuth,
            elevation = elevationValue ?: old.elevation, distanceMeters = distanceValue ?: old.distanceMeters)
        change { moveSpeaker(selectedSpeaker, next) }
        refreshSpeaker()
    }
    private fun refreshSpeaker() {
        selectedSpeaker = selectedSpeaker.coerceIn(activeSpeakers().indices)
        val position = activeSpeakers()[selectedSpeaker]
        if (speakerSelector.selectedItemPosition != selectedSpeaker) speakerSelector.setSelection(selectedSpeaker)
        val meters = String.format(resources.configuration.locales[0], "%.2f", position.distanceMeters)
        angleText.text = getString(R.string.audio_speaker_position, activeSpeakerNames()[selectedSpeaker],
            position.azimuth.roundToInt(), position.elevation.roundToInt(), meters)
        azimuth.progress = position.azimuth.roundToInt() + 180
        elevation.progress = position.elevation.roundToInt() + 60
        distance.progress = ((position.distanceMeters - .5f) / .05f).roundToInt()
        ViewCompat.setStateDescription(azimuth, getString(R.string.audio_degrees_accessibility, position.azimuth.roundToInt()))
        ViewCompat.setStateDescription(elevation, getString(R.string.audio_degrees_accessibility, position.elevation.roundToInt()))
        ViewCompat.setStateDescription(distance, getString(R.string.audio_meters_accessibility, meters))
        scene.selected = selectedSpeaker; scene.invalidate()
    }
}

private fun SpatialSettings.editableSpeakers() = if (mode == 2) speakers else stereoSpeakers

private class SpeakerSceneView(context: Context, val moved: (Int, Float, Float) -> Unit) : View(context) {
    var selected = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var dragging = false
    private val centerX get() = width / 2f
    private val centerY get() = height / 2f + 8 * density
    private val radius get() = max(0f, min(width.toFloat(), height - 16 * density) / 2f - 38 * density)
    init {
        background = SpatialStyle.rounded(context, SpatialStyle.panel, 24)
        clipToOutline = true
    }
    // Compress the distance scale to leave a touchable area around the head.
    // Sliders retain precise control even when projected speakers overlap.
    private fun distanceRadius(meters: Float) = radius * (.55f + .45f * sqrt(((meters - .5f) / 4.5f).coerceIn(0f, 1f)))
    private fun point(index: Int): Pair<Float, Float> {
        val position = SpatialPreferences.current.editableSpeakers()[index]
        val angle = Math.toRadians(position.azimuth.toDouble())
        val r = distanceRadius(position.distanceMeters)
        return Pair(centerX + sin(angle).toFloat() * r, centerY - cos(angle).toFloat() * r)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = SpatialStyle.line
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, paint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, paint)
        for (meters in listOf(1f, 3f, 5f)) canvas.drawCircle(centerX, centerY, distanceRadius(meters), paint)
        paint.style = Paint.Style.FILL
        paint.color = SpatialStyle.card
        canvas.drawCircle(centerX, centerY, 26 * density, paint)
        paint.color = SpatialStyle.secondary
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.save()
        canvas.rotate(SpatialPreferences.current.listenerYaw, centerX, centerY)
        canvas.drawCircle(centerX, centerY - 3 * density, 10 * density, paint)
        canvas.drawLine(centerX - 3 * density, centerY - 12 * density, centerX, centerY - 17 * density, paint)
        canvas.drawLine(centerX, centerY - 17 * density, centerX + 3 * density, centerY - 12 * density, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = 11 * density
        canvas.drawText(context.getString(R.string.audio_scene_you), centerX, centerY + 19 * density, paint)
        paint.textSize = 12 * density
        canvas.drawText(context.getString(R.string.audio_scene_front), centerX, 18 * density, paint)
        val positions = SpatialPreferences.current.editableSpeakers()
        val names = if (SpatialPreferences.current.mode == 2) SpatialSettings.speakerNames else SpatialSettings.stereoSpeakerNames
        val dotRadius = if (positions.size == 7) min(18 * density, radius * .17f) else 21 * density
        for (i in positions.indices.sortedBy { if (it == selected) 1 else 0 }) {
            val (x, y) = point(i)
            if (i == selected) {
                paint.color = Color.argb(40, 60, 120, 158)
                canvas.drawCircle(x, y, dotRadius + 5 * density, paint)
            }
            paint.color = if (i == selected) SpatialStyle.accent else SpatialStyle.card
            canvas.drawCircle(x, y, dotRadius, paint)
            paint.color = if (i == selected) SpatialStyle.card else SpatialStyle.ink
            paint.textSize = 13 * density
            canvas.drawText(names[i], x, y - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val positions = SpatialPreferences.current.editableSpeakers()
                val nearest = positions.indices.minByOrNull { val (x, y) = point(it); hypot(event.x - x, event.y - y) } ?: return false
                val (x, y) = point(nearest)
                if (hypot(event.x - x, event.y - y) > 36 * density) return false
                selected = nearest; dragging = true; parent.requestDisallowInterceptTouchEvent(true)
                moved(selected, positions[selected].azimuth, positions[selected].distanceMeters)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (radius <= 0f) return true
                val radial = ((hypot(event.x - centerX, event.y - centerY) / radius - .55f) / .45f).coerceIn(0f, 1f)
                moved(selected, Math.toDegrees(atan2((event.x - centerX).toDouble(), (centerY - event.y).toDouble())).toFloat(),
                    .5f + radial * radial * 4.5f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false; parent.requestDisallowInterceptTouchEvent(false); performClick(); return true
            }
        }
        return dragging
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
