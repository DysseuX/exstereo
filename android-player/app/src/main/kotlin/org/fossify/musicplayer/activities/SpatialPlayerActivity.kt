/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.content.Intent
import android.app.AlertDialog
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.musicplayer.R
import org.fossify.musicplayer.extensions.audioHelper
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.extensions.currentMediaItems
import org.fossify.musicplayer.extensions.getTrackCoverArt
import org.fossify.musicplayer.extensions.isReallyPlaying
import org.fossify.musicplayer.extensions.loadGlideResource
import org.fossify.musicplayer.extensions.maybeRestartOnPrevious
import org.fossify.musicplayer.extensions.mediaScanner
import org.fossify.musicplayer.extensions.sendCommand
import org.fossify.musicplayer.extensions.toTrack
import org.fossify.musicplayer.extensions.togglePlayback
import org.fossify.musicplayer.helpers.getPermissionToRequest
import org.fossify.musicplayer.helpers.QueueDocuments
import org.fossify.musicplayer.helpers.QueueEdits
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.CustomCommands
import org.fossify.musicplayer.playback.player.RawSeekProgress
import org.fossify.musicplayer.views.DelayedMarqueeTextView
import org.fossify.musicplayer.views.QueueScrollbar
import org.fossify.musicplayer.extensions.setRepeatMode
import org.fossify.musicplayer.helpers.PlaybackSetting
import org.fossify.musicplayer.views.SpatialSeekBar
import org.fossify.musicplayer.views.SpatialPlayButton
import org.fossify.musicplayer.views.SpatialStyle
import org.fossify.musicplayer.views.showQueueRemovalDialog
import org.fossify.musicplayer.views.SpatialArtworkView
import org.fossify.musicplayer.spatial.SpatialPreferences
import org.fossify.musicplayer.diagnostics.StartupDiagnostics
import java.io.File
import java.util.Locale

/** Player UI backed by Fossify's controller, queue and playback service. */
class SpatialPlayerActivity : SimpleControllerActivity() {
    private lateinit var pager: ViewPager
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var artwork: ImageView
    private lateinit var playButton: SpatialPlayButton
    private lateinit var modeBadge: TextView
    private lateinit var soundModeButton: LinearLayout
    private lateinit var soundModeIcon: ImageView
    private lateinit var soundModeLabel: TextView
    private var renderedSoundMode = -1
    private lateinit var seekBar: SpatialSeekBar
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var emptyPlayer: LinearLayout
    private lateinit var queueStatus: TextView
    private lateinit var folderStatus: TextView
    private lateinit var folderTitle: TextView
    private lateinit var folderBack: Button
    private lateinit var playFolder: Button
    private lateinit var permissionButton: Button
    private lateinit var queueAdapter: RowsAdapter
    private lateinit var folderAdapter: RowsAdapter
    private lateinit var playerQueueAdapter: CompactQueueAdapter
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private var renderedQueueMargin = -1
    private val pageButtons = mutableListOf<LinearLayout>()
    private val pageIcons = mutableListOf<ImageView>()
    private val pageLabels = mutableListOf<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var trackingSeek = false
    private var foreground = false
    private var permissionGranted = false
    private var scanning = false
    private var scanGeneration = 0
    private var coverKey: String? = null
    private var queueKey: String? = null
    private var activeFolder: String? = null
    private var tracksByFolder = emptyMap<String, List<Track>>()
    private var restoringPage = 1
    private var addingDocuments = false
    private var renderedTheme = ""

    private val addAudioFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importQueueDocuments(uris, folder = false)
    }
    private val addAudioFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importQueueDocuments(listOf(uri), folder = true)
    }

    // Android's MediaStore does not index every software-decoded audio format.
    private val openAudioFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            retainDocumentAccess(listOf(uri))
            ensureBackgroundThread {
                val result = runCatching { QueueDocuments.readFile(contentResolver, uri) }
                runOnUiThread {
                    if (!isDestroyed) {
                        result.onSuccess { playTracks(listOf(it), 0) }
                            .onFailure { toast(it.message ?: getString(R.string.player_file_open_error)) }
                    }
                }
            }
        }
    }

    private val accent get() = SpatialStyle.accent
    private val textColor get() = SpatialStyle.ink
    private val backgroundColor get() = SpatialStyle.background
    private val panelColor get() = SpatialStyle.panel

    private val progressTick = object : Runnable {
        override fun run() {
            if (foreground) {
                refreshPlayer()
                handler.postDelayed(this, 300)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (StartupDiagnostics.hasReport()) {
            startActivity(Intent(this, StartupReportActivity::class.java))
            finish()
            return
        }
        StartupDiagnostics.mark("player interface creation")
        volumeControlStream = AudioManager.STREAM_MUSIC
        restoringPage = savedInstanceState?.getInt("spatial_page", 1) ?: 1
        activeFolder = savedInstanceState?.getString("spatial_folder")
        SpatialPreferences.initialize(this)
        buildInterface()
        StartupDiagnostics.mark("player interface created; requesting music permission")
        requestMusicPermission()
    }

    override fun onResume() {
        super.onResume()
        if (!::pager.isInitialized) return
        SpatialStyle.initialize(this)
        if (renderedTheme != SpatialStyle.current.id || renderedQueueMargin != config.playerQueueMarginPx) {
            restoringPage = pager.currentItem
            buildInterface()
            showFolders()
        }
        foreground = true
        applyPlayerSystemBars()
        playButton.setMotionActive(pager.currentItem == 1)
        updateMarqueeMotion()
        refreshPlayer(updateQueue = true)
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
        if (permissionGranted) loadFolders()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::pager.isInitialized) applyPlayerSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun applyPlayerSystemBars() {
        // Dialogs can reset system-bar colors; restore dark text on the pastel surface.
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onPause() {
        foreground = false
        if (::playButton.isInitialized) playButton.setMotionActive(false)
        updateMarqueeMotion()
        handler.removeCallbacks(progressTick)
        super.onPause()
    }

    override fun onDestroy() {
        scanGeneration++
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::pager.isInitialized) outState.putInt("spatial_page", pager.currentItem)
        outState.putString("spatial_folder", activeFolder)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressedCompat(): Boolean {
        if (!::pager.isInitialized) return false
        if (pager.currentItem == 2 && activeFolder != null) {
            activeFolder = null
            showFolders()
            return true
        }
        if (pager.currentItem != 1) {
            pager.setCurrentItem(1, true)
            return true
        }
        return false
    }

    private fun buildInterface() {
        SpatialStyle.initialize(this)
        renderedTheme = SpatialStyle.current.id
        renderedQueueMargin = config.playerQueueMarginPx
        pageButtons.clear(); pageIcons.clear(); pageLabels.clear()
        coverKey = null; queueKey = null
        val root = column().apply {
            setBackgroundColor(backgroundColor)
            if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.exstereo_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(4) })
        top.addView(label(getString(R.string.spatial_player_title), 20, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        fun headerSlot() = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(4) }
        top.addView(iconButton(R.drawable.ic_spatial_info, R.string.exstereo_about_title, 44) {
            showExstereoAbout()
        }, headerSlot())
        soundModeIcon = ImageView(this).apply {
            setColorFilter(textColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        soundModeLabel = label("", 11, true).apply {
            gravity = Gravity.CENTER; setSingleLine(); includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        soundModeButton = column().apply {
            gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            background = SpatialStyle.ripple(this@SpatialPlayerActivity, SpatialStyle.selected, 22)
            addView(soundModeIcon, LinearLayout.LayoutParams(dp(21), dp(21)))
            addView(soundModeLabel, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { openSound() }
        }
        renderedSoundMode = -1
        refreshSoundMode()
        top.addView(soundModeButton, headerSlot())
        top.addView(iconButton(R.drawable.ic_spatial_settings, R.string.queue_player_menu, 44) {}.apply {
            setOnClickListener { showPlayerMenu(this) }
        }, headerSlot())
        root.addView(top)
        pager = ViewPager(this).apply {
            id = View.generateViewId()
            offscreenPageLimit = 2
        }
        val pages = listOf(createQueuePage(), createPlayerPage(), createFoldersPage())
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(view: View, item: Any) = view === item
            override fun instantiateItem(container: ViewGroup, position: Int): Any = pages[position].also { container.addView(it) }
            override fun destroyItem(container: ViewGroup, position: Int, item: Any) { container.removeView(item as View) }
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateTabs(position)
                playButton.setMotionActive(foreground && position == 1)
                updateMarqueeMotion()
            }
        })
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            background = rounded(panelColor, 28)
        }
        val icons = listOf(R.drawable.ic_spatial_queue, R.drawable.ic_spatial_headphones, R.drawable.ic_spatial_folder)
        listOf(R.string.spatial_player_tab_queue, R.string.spatial_player_tab_player, R.string.spatial_player_tab_folders).forEachIndexed { index, res ->
            val icon = ImageView(this).apply {
                setImageResource(icons[index]); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val caption = label(getString(res), 12, true).apply { gravity = Gravity.CENTER }
            val tab = column().apply {
                gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                contentDescription = getString(res)
                setOnClickListener { pager.setCurrentItem(index, true) }
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            pageButtons += tab; pageIcons += icon; pageLabels += caption
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        pager.currentItem = restoringPage.coerceIn(0, 2)
        updateTabs(pager.currentItem)
        setContentView(root)
        setupEdgeToEdge(padTopSystem = listOf(top), padBottomSystem = listOf(root))
        applyPlayerSystemBars()
    }

    private fun createPlayerPage(): View {
        val content = column().apply { clipChildren = false; clipToPadding = false }
        val queueFrame = FrameLayout(this).apply {
            background = rounded(SpatialStyle.card, 24)
            clipToOutline = true
        }
        playerQueueAdapter = CompactQueueAdapter()
        val playerQueue = RecyclerView(this).apply {
            contentDescription = getString(R.string.player_queue_tracks)
            layoutManager = LinearLayoutManager(this@SpatialPlayerActivity)
            adapter = playerQueueAdapter
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(10), 0, dp(6), 0)
        }
        queueFrame.addView(playerQueue, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = dp(32) })
        queueFrame.addView(QueueScrollbar(this, playerQueue), FrameLayout.LayoutParams(dp(44), -1, Gravity.END).apply {
            setMargins(0, dp(9), 0, dp(18))
        })
        content.addView(queueFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            // This preference is explicitly in physical pixels, not dp.
            setMargins(config.playerQueueMarginPx, dp(9), config.playerQueueMarginPx, 0)
        })
        val metadata = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        artwork = SpatialArtworkView(this, 12f).apply {
            contentDescription = getString(R.string.spatial_player_artwork)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(panelColor, 12); clipToOutline = true
            setImageResource(R.drawable.ic_spatial_cover)
        }
        artistView = DelayedMarqueeTextView(this).apply {
            textSize = 18f; minHeight = dp(25); setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        titleView = DelayedMarqueeTextView(this).apply {
            text = getString(R.string.spatial_player_empty_title)
            textSize = 16f; minHeight = dp(23); setTextColor(SpatialStyle.secondary)
        }
        val metadataText = column().apply {
            addView(artistView, LinearLayout.LayoutParams(-1, -2))
            addView(titleView, LinearLayout.LayoutParams(-1, -2))
        }
        metadata.addView(artwork, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        metadata.addView(metadataText, LinearLayout.LayoutParams(0, -2, 1f))
        // Match the square cover to the two font-scaled lines.
        metadataText.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val size = (bottom - top).coerceAtLeast(dp(48))
            if (artwork.layoutParams.height != size) {
                artwork.layoutParams = (artwork.layoutParams as LinearLayout.LayoutParams).apply {
                    width = size; height = size
                }
            }
        }
        content.addView(metadata, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13); bottomMargin = dp(6) })
        modeBadge = label("", 12).apply { visibility = View.GONE }
        content.addView(modeBadge)
        seekBar = SpatialSeekBar(this, accent, SpatialStyle.line).apply {
            contentDescription = getString(R.string.spatial_player_seek)
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) positionView.text = formatTime(value.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) { trackingSeek = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    withPlayer { seekTo(bar.progress.toLong()) }
                    trackingSeek = false
                }
            })
        }
        // Keep time labels outside the slider's touch area.
        val seekSection = column()
        seekSection.addView(seekBar, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(18), 0, dp(18), 0) })
        val times = LinearLayout(this).apply { setPadding(dp(18), 0, dp(13), 0) }
        positionView = label("0:00", 12).apply { setTextColor(SpatialStyle.secondary) }
        durationView = label("0:00", 12).apply { gravity = Gravity.END; setTextColor(SpatialStyle.secondary) }
        times.addView(positionView, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(durationView, LinearLayout.LayoutParams(0, -2, 1f))
        times.minimumHeight = dp(20)
        seekSection.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })
        content.addView(seekSection, LinearLayout.LayoutParams(-1, -2))
        val transport = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
        }
        fun transportGap(weight: Float) = transport.addView(View(this), LinearLayout.LayoutParams(0, 0, weight))
        fun transportIcon(icon: Int, description: Int, action: () -> Unit): ImageButton =
            iconButton(icon, description, 44, action).also {
                it.translationY = -dp(1).toFloat()
                transport.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
        shuffleButton = transportIcon(R.drawable.ic_shuffle_vector, R.string.enable_shuffle, {
            withPlayer { shuffleModeEnabled = !shuffleModeEnabled }
        })
        transportGap(18f)
        transportIcon(R.drawable.ic_previous_vector, R.string.spatial_player_previous, {
            withPlayer { if (!maybeRestartOnPrevious()) seekToPreviousMediaItem() }
        })
        transportGap(26f)
        playButton = SpatialPlayButton(this, compact = true).apply {
            contentDescription = getString(R.string.spatial_player_play)
            setOnClickListener {
                withPlayer { if (currentMediaItem == null) pager.setCurrentItem(2, true) else togglePlayback() }
            }
        }
        transport.addView(playButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        transportGap(26f)
        transportIcon(R.drawable.ic_next_vector, R.string.spatial_player_next, { withPlayer { seekToNextMediaItem() } })
        transportGap(18f)
        repeatButton = transportIcon(R.drawable.ic_repeat_playlist_vector, R.string.repeat_off, {
            withPlayer {
                val next = config.playbackSetting.nextPlaybackOption
                config.playbackSetting = next
                setRepeatMode(next)
                updateRepeatButton()
            }
        })
        content.addView(transport, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(10) })
        emptyPlayer = column().apply {
            addView(label(getString(R.string.spatial_player_empty_hint), 15).apply {
                gravity = Gravity.CENTER
                setTextColor(SpatialStyle.secondary)
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(button(R.string.spatial_player_open_folders) { pager.setCurrentItem(2, true) })
        }
        content.addView(emptyPlayer)
        return content
    }

    private fun updateMarqueeMotion() {
        if (!::titleView.isInitialized || !::artistView.isInitialized) return
        val active = foreground && ::pager.isInitialized && pager.currentItem == 1
        (titleView as? DelayedMarqueeTextView)?.setMotionActive(active)
        (artistView as? DelayedMarqueeTextView)?.setMotionActive(active)
    }

    private fun updateRepeatButton() {
        val setting = config.playbackSetting
        repeatButton.setImageResource(setting.iconRes)
        repeatButton.contentDescription = getString(setting.descriptionStringRes) + "; " + getString(setting.contentDescriptionStringRes)
        repeatButton.setColorFilter(if (setting == PlaybackSetting.REPEAT_OFF) textColor else accent)
    }

    private fun openSound() { startActivity(Intent(this, SpatialAudioActivity::class.java)) }

    private fun refreshSoundMode() {
        if (!::soundModeButton.isInitialized) return
        val mode = SpatialPreferences.current.mode
        if (mode == renderedSoundMode) return
        renderedSoundMode = mode
        val (caption, description, icon) = when (mode) {
            1 -> Triple(getString(R.string.player_mode_wide_short), getString(R.string.player_mode_wide), R.drawable.ic_spatial_wide)
            2 -> Triple("7.1", getString(R.string.player_mode_surround), R.drawable.ic_spatial_surround)
            else -> Triple("2.0", getString(R.string.player_mode_stereo), R.drawable.ic_spatial_headphones)
        }
        soundModeLabel.text = caption
        soundModeIcon.setImageResource(icon)
        soundModeButton.contentDescription = getString(R.string.player_mode_description, description)
    }

    private fun createQueuePage(): View = column(dp(20)).apply {
        addView(LinearLayout(this@SpatialPlayerActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.spatial_player_tab_queue), 28, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(queueAction("+", R.string.queue_add) { showAddToQueue() })
            addView(queueAction("−", R.string.queue_remove) { showRemoveFromQueue() })
        })
        queueStatus = label(getString(R.string.spatial_player_no_queue), 14).apply {
            setPadding(0, dp(8), 0, dp(16)); setTextColor(SpatialStyle.secondary)
        }
        addView(queueStatus)
        queueAdapter = RowsAdapter()
        addView(list(queueAdapter), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(button(R.string.spatial_player_library) { startActivity(Intent(this@SpatialPlayerActivity, MainActivity::class.java)) })
    }

    private fun queueAction(symbol: String, description: Int, action: () -> Unit) = Button(this).apply {
        text = symbol; textSize = 25f; contentDescription = getString(description)
        setTextColor(accent); backgroundTintList = null
        background = SpatialStyle.ripple(this@SpatialPlayerActivity, panelColor, 18)
        minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) }
        setOnClickListener { action() }
    }

    private fun showPlayerMenu(anchor: View): PopupMenu {
        // Isolate popup colors from Fossify's custom text appearance.
        return PopupMenu(ContextThemeWrapper(this, R.style.SpatialPopupMenuTheme), anchor).apply {
            menu.add(0, 1, 0, R.string.player_equalizer_title)
            menu.add(0, 2, 1, R.string.player_settings_title)
            menu.add(0, 3, 2, R.string.player_diagnostics_menu)
            setOnMenuItemClickListener {
                val target = when (it.itemId) {
                    1 -> EqualizerActivity::class.java
                    2 -> SettingsActivity::class.java
                    else -> StartupReportActivity::class.java
                }
                startActivity(Intent(this@SpatialPlayerActivity, target)); true
            }
            show()
        }
    }

    private fun showAddToQueue() {
        if (addingDocuments) { toast(R.string.player_adding_wait); return }
        AlertDialog.Builder(this).setTitle(R.string.queue_add)
            .setItems(arrayOf(getString(R.string.player_pick_files), getString(R.string.player_pick_folder), getString(R.string.player_library_folders))) { _, choice ->
                when (choice) {
                    0 -> addAudioFiles.launch(arrayOf("*/*"))
                    1 -> addAudioFolder.launch(null)
                    else -> selectLibraryFolders()
                }
            }.setNegativeButton(R.string.player_cancel, null).show()
    }

    private fun selectLibraryFolders() {
        val folders = tracksByFolder.entries.toList()
        if (folders.isEmpty()) { toast(R.string.player_library_no_folders); return }
        val chosen = BooleanArray(folders.size)
        AlertDialog.Builder(this).setTitle(R.string.player_add_folders)
            .setMultiChoiceItems(folders.map { "${QueueDocuments.folderLabel(it.key)} (${it.value.size})" }.toTypedArray(), chosen) { _, index, checked -> chosen[index] = checked }
            .setPositiveButton(R.string.player_add) { _, _ -> appendQueueTracks(folders.filterIndexed { i, _ -> chosen[i] }.flatMap { it.value }) }
            .setNegativeButton(R.string.player_cancel, null).show()
    }

    private fun importQueueDocuments(uris: List<Uri>, folder: Boolean) {
        addingDocuments = true
        queueStatus.text = getString(R.string.player_adding_files)
        // Persist URI grants for queue restoration after restart.
        retainDocumentAccess(uris)
        ensureBackgroundThread {
            val result = runCatching {
                if (folder) QueueDocuments.readFolder(contentResolver, uris.single()) { isDestroyed }
                else uris.map { QueueDocuments.readFile(contentResolver, it) }
            }
            runOnUiThread {
                addingDocuments = false
                if (!isDestroyed) {
                    result.onSuccess { tracks ->
                        if (tracks.isEmpty()) toast(R.string.player_folder_no_audio)
                        else appendQueueTracks(tracks)
                    }.onFailure { toast(it.message ?: getString(R.string.player_files_read_error)) }
                    queueKey = null
                    refreshPlayer(updateQueue = true)
                }
            }
        }
    }

    private fun retainDocumentAccess(uris: List<Uri>) {
        val temporary = uris.count {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isFailure
        }
        if (temporary > 0) toast(R.string.player_temporary_file_access)
    }

    private fun appendQueueTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withPlayer {
            val added = QueueEdits.append(this, tracks)
            toast(getString(R.string.player_queue_added, added))
            queueKey = null
            refreshPlayer(updateQueue = true)
        }
    }

    private fun showRemoveFromQueue() {
        selectQueueRemoval(byFolder = false)
    }

    private fun selectQueueRemoval(byFolder: Boolean): Unit = withPlayer {
        val snapshot = currentMediaItems.toList()
        if (snapshot.isEmpty()) { toast(R.string.player_queue_empty); return@withPlayer }
        val groups = if (byFolder) snapshot.indices.groupBy { index -> snapshot[index].toTrack()?.let { QueueDocuments.folderKey(it) }.orEmpty() }
            .map { (key, indices) -> "${QueueDocuments.folderLabel(key)} (${indices.size})" to indices }
        else snapshot.mapIndexed { index, item -> "${index + 1}. ${item.mediaMetadata.title ?: getString(R.string.player_audio_file)}" to listOf(index) }
        fun remove(indices: List<Int>) = withPlayer {
            if (!QueueEdits.remove(this, snapshot, indices)) {
                toast(R.string.player_queue_changed)
            } else {
                queueKey = null
                refreshPlayer(updateQueue = true)
            }
        }
        showQueueRemovalDialog(
            context = this@SpatialPlayerActivity,
            labels = groups.map { it.first },
            byFolder = byFolder,
            onRemove = { selected -> remove(selected.flatMap { groups[it].second }) },
            onClear = { remove(snapshot.indices.toList()) },
            onSwitch = { selectQueueRemoval(!byFolder) }
        )
    }

    private fun createFoldersPage(): View = column(dp(20)).apply {
        folderTitle = label(getString(R.string.spatial_player_tab_folders), 28, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        addView(folderTitle)
        folderBack = button(R.string.spatial_player_back_folders) { activeFolder = null; showFolders() }.apply { visibility = View.GONE }
        addView(folderBack)
        folderStatus = label(getString(R.string.spatial_player_scanning), 14).apply {
            setPadding(0, dp(8), 0, dp(16)); setTextColor(SpatialStyle.secondary)
        }
        addView(folderStatus)
        permissionButton = button(R.string.spatial_player_permission) { requestMusicPermission() }.apply { visibility = View.GONE }
        addView(permissionButton)
        playFolder = button(R.string.spatial_player_play_folder) {
            activeFolder?.let { tracksByFolder[it] }?.let { playTracks(it, 0) }
        }.apply { visibility = View.GONE }
        addView(playFolder)
        folderAdapter = RowsAdapter()
        addView(list(folderAdapter), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(button(R.string.spatial_open_file) { openAudioFile.launch(arrayOf("*/*")) })
        addView(button(R.string.spatial_player_rescan) {
            if (permissionGranted) scanMusic() else requestMusicPermission()
        })
    }

    private fun requestMusicPermission() {
        handlePermission(getPermissionToRequest()) { granted ->
            if (!isDestroyed) {
                permissionGranted = granted
                StartupDiagnostics.mark("music permission granted=$granted")
                permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
                if (granted) scanMusic() else {
                    folderStatus.setText(R.string.spatial_player_permission_denied)
                    folderAdapter.submit(emptyList())
                }
            }
        }
    }

    private fun scanMusic() {
        StartupDiagnostics.mark("music scan started")
        scanning = true
        showFolders()
        loadFolders()
        val generation = ++scanGeneration
        mediaScanner.scan { complete ->
            runOnUiThread {
                if (!isDestroyed && generation == scanGeneration) {
                    scanning = !complete
                    if (complete) StartupDiagnostics.mark("music scan completed; preparing queue")
                    loadFolders()
                    if (complete) withPlayer {
                        if (currentMediaItem == null) maybePreparePlayer()
                        else sendCommand(CustomCommands.RELOAD_CONTENT)
                    }
                }
            }
        }
        // The scanner also handles old/corrupt media entries internally; refresh its terminal state
        // if it finishes without invoking its callback after a provider error.
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isDestroyed || generation != scanGeneration || !scanning) return
                if (mediaScanner.isScanning()) handler.postDelayed(this, 1000)
                else { scanning = false; loadFolders() }
            }
        }, 1000)
    }

    private fun loadFolders() {
        ensureBackgroundThread {
            val result = runCatching {
                audioHelper.getAllTracks()
                    .distinctBy { it.path.ifEmpty { it.mediaStoreId.toString() } }
                    .filter { it.path.isNotEmpty() && (it.path.startsWith("content://") || File(it.path).parent.orEmpty() !in config.excludedFolders) }
                    .groupBy { QueueDocuments.folderKey(it) }
                    .toSortedMap(compareBy<String> { QueueDocuments.folderLabel(it).lowercase(Locale.ROOT) }.thenBy { it })
            }
            runOnUiThread {
                if (!isDestroyed) {
                    result.onSuccess { tracksByFolder = it; showFolders() }
                        .onFailure { folderStatus.setText(R.string.spatial_player_load_error) }
                }
            }
        }
    }

    private fun showFolders() {
        if (!::folderAdapter.isInitialized) return
        val selected = activeFolder
        // null means the folder overview; a TreeMap comparator cannot look up that key.
        val tracks = selected?.let { tracksByFolder[it] }
        if (selected != null && tracks == null && !scanning) activeFolder = null
        val inFolder = activeFolder != null && tracks != null
        folderBack.visibility = if (inFolder) View.VISIBLE else View.GONE
        playFolder.visibility = if (inFolder) View.VISIBLE else View.GONE
        folderTitle.text = if (inFolder) QueueDocuments.folderLabel(selected!!).substringAfterLast('/') else getString(R.string.spatial_player_tab_folders)
        folderStatus.text = when {
            !permissionGranted -> getString(R.string.spatial_player_permission_denied)
            inFolder -> QueueDocuments.folderLabel(selected!!)
            tracksByFolder.isNotEmpty() -> getString(R.string.spatial_player_folder_hint)
            scanning -> getString(R.string.spatial_player_scanning)
            else -> getString(R.string.spatial_player_no_folders)
        }
        folderAdapter.submit(if (inFolder) {
            tracks!!.mapIndexed { index, track ->
                Row(track.title, track.artist.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                    false, track, duration = formatTime(track.duration * 1000L)) { playTracks(tracks, index) }
            }
        } else {
            tracksByFolder.map { (path, items) ->
                Row(QueueDocuments.folderLabel(path).substringAfterLast('/'), getString(R.string.spatial_player_tracks_count, items.size),
                    false, items.firstOrNull(), folder = true) {
                    activeFolder = path
                    showFolders()
                }
            }
        })
    }

    private fun playTracks(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        prepareAndPlay(tracks, startIndex = index, startActivity = false)
        pager.setCurrentItem(1, true)
    }

    override fun onPlayerPrepared(success: Boolean) { refreshPlayer(updateQueue = true) }

    override fun onEvents(player: Player, events: Player.Events) { refreshPlayer(updateQueue = true) }

    override fun onPlayerError(error: PlaybackException) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.player_playback_error)
            .setMessage(error.errorCodeName + "\n" + (error.cause?.message ?: error.message ?: ""))
            .setPositiveButton(R.string.player_report) { _, _ -> startActivity(Intent(this, StartupReportActivity::class.java)) }
            .setNegativeButton(R.string.exstereo_about_close, null)
            .show()
    }

    private fun refreshPlayer(updateQueue: Boolean = false) {
        if (!::seekBar.isInitialized || isDestroyed) return
        refreshSoundMode()
        withPlayer {
            if (isDestroyed) return@withPlayer
            val track = currentMediaItem?.toTrack()
            val title = track?.title ?: getString(R.string.spatial_player_empty_title)
            val artist = track?.artist?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty()
            // Progress is refreshed every 300 ms; unchanged metadata must not restart the delay.
            if (titleView.text.toString() != title) titleView.text = title
            if (artistView.text.toString() != artist) artistView.text = artist
            shuffleButton.setColorFilter(if (shuffleModeEnabled) accent else textColor)
            shuffleButton.isSelected = shuffleModeEnabled
            shuffleButton.contentDescription = getString(if (shuffleModeEnabled) R.string.disable_shuffle else R.string.enable_shuffle)
            updateRepeatButton()
            modeBadge.text = RawSeekProgress.label?.takeIf { playbackState == Player.STATE_BUFFERING } ?: when (SpatialPreferences.current.mode) {
                1 -> getString(R.string.player_mode_configure, getString(R.string.player_mode_wide))
                2 -> getString(R.string.player_mode_configure, getString(R.string.player_mode_surround))
                else -> getString(R.string.player_mode_configure, getString(R.string.player_mode_stereo))
            }
            emptyPlayer.visibility = if (track == null) View.VISIBLE else View.GONE
            playButton.setPlaying(isReallyPlaying)
            playButton.contentDescription = getString(if (isReallyPlaying) R.string.spatial_player_pause else R.string.spatial_player_play)
            val durationMs = duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((track?.duration ?: 0) * 1000L)
            seekBar.isEnabled = track != null && durationMs > 0 && isCurrentMediaItemSeekable
            seekBar.max = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            durationView.text = formatTime(durationMs)
            if (!trackingSeek) {
                seekBar.progress = currentPosition.coerceIn(0, durationMs.coerceAtLeast(0)).toInt()
                positionView.text = formatTime(currentPosition)
            }
            updateArtwork(track)
            if (updateQueue || queueKey == null) {
                val items = currentMediaItems
                val signature = "$currentMediaItemIndex|${items.joinToString { "${it.mediaId}:${it.mediaMetadata.title}:${it.mediaMetadata.artist}" }}"
                if (signature == queueKey) return@withPlayer
                queueKey = signature
                queueStatus.text = if (items.isEmpty()) getString(R.string.spatial_player_no_queue)
                    else getString(R.string.spatial_player_queue_count, items.size)
                val rows = items.mapIndexed { index, item ->
                    val queued = item.toTrack()
                    Row(queued?.title ?: item.mediaMetadata.title?.toString().orEmpty(),
                        queued?.artist?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(), index == currentMediaItemIndex,
                        queued, duration = queued?.let { formatTime(it.duration * 1000L) }.orEmpty()) {
                        withPlayer {
                            // Resolve the item again: the queue may have changed since this row was drawn.
                            val actualIndex = if (index < mediaItemCount && getMediaItemAt(index).mediaId == item.mediaId) index
                                else currentMediaItems.indexOfFirst { it.mediaId == item.mediaId }
                            if (actualIndex >= 0) { seekTo(actualIndex, 0); play() }
                        }
                        pager.setCurrentItem(1, true)
                    }
                }
                queueAdapter.submit(rows)
                playerQueueAdapter.submit(rows)
            }
        }
    }

    private fun updateArtwork(track: Track?) {
        val key = track?.let { "${it.path}:${it.coverArt}" }.orEmpty()
        if (key == coverKey) return
        coverKey = key
        artwork.setImageResource(R.drawable.ic_spatial_cover)
        if (track == null) return
        getTrackCoverArt(track) { cover ->
            if (!isDestroyed && coverKey == key) loadGlideResource(cover, RequestOptions().centerCrop(), Size(dp(320), dp(320)),
                onLoadFailed = {}, onResourceReady = { drawable ->
                    runOnUiThread { if (!isDestroyed && coverKey == key) {
                        artwork.setImageDrawable(drawable)
                    } }
                })
        }
    }

    private fun updateTabs(selected: Int) = pageButtons.forEachIndexed { index, button ->
        val color = if (index == selected) textColor else SpatialStyle.secondary
        pageIcons[index].setColorFilter(color)
        pageLabels[index].setTextColor(color)
        button.background = SpatialStyle.ripple(this, if (index == selected) SpatialStyle.selected else Color.TRANSPARENT, 22)
        button.isSelected = index == selected
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(textColor)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun button(resource: Int, action: () -> Unit) = Button(this).apply {
        setText(resource); isAllCaps = false; setTextColor(accent); textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumHeight = dp(48)
        backgroundTintList = null
        background = SpatialStyle.ripple(this@SpatialPlayerActivity, panelColor, 18)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { action() }
    }
    private fun iconButton(icon: Int, description: Int, size: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = getString(description)
        setColorFilter(textColor); setPadding(dp(12), dp(12), dp(12), dp(12))
        backgroundTintList = null
        background = SpatialStyle.ripple(this@SpatialPlayerActivity, panelColor, size / 2)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = SpatialStyle.rounded(this, color, radius)
    private fun list(rows: RowsAdapter) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@SpatialPlayerActivity)
        adapter = rows; clipToPadding = false; isVerticalScrollBarEnabled = false
    }
    private data class Row(val title: String, val detail: String, val active: Boolean,
        val track: Track? = null, val folder: Boolean = false, val duration: String = "", val action: () -> Unit)
    private inner class RowHolder(val box: LinearLayout, val image: ImageView, val title: TextView,
        val detail: TextView, val duration: TextView, val chevron: ImageView) : RecyclerView.ViewHolder(box) {
        var coverToken: Any? = null
    }
    private inner class CompactQueueAdapter : RecyclerView.Adapter<CompactQueueAdapter.Holder>() {
        private var rows = emptyList<Row>()
        inner class Holder(val box: LinearLayout, val artist: TextView, val song: TextView, val time: TextView) :
            RecyclerView.ViewHolder(box)
        fun submit(value: List<Row>) { rows = value; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val box = LinearLayout(this@SpatialPlayerActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(6), 0, dp(6), 0)
                layoutParams = RecyclerView.LayoutParams(-1, dp(44))
            }
            fun line(bold: Boolean) = label("", 14, bold).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            }
            val artist = line(true)
            val song = line(false)
            val time = line(false).apply { gravity = Gravity.END; minWidth = dp(44) }
            box.addView(artist, LinearLayout.LayoutParams(0, -1, .44f))
            box.addView(song, LinearLayout.LayoutParams(0, -1, .56f).apply { marginStart = dp(6); marginEnd = dp(8) })
            box.addView(time, LinearLayout.LayoutParams(-2, -1))
            return Holder(box, artist, song, time)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.artist.text = row.detail
            holder.song.text = row.title
            holder.time.text = row.duration.ifEmpty { "0:00" }
            // Measure duration before weighted metadata, including recycled rows.
            val timeWidth = maxOf(dp(44), kotlin.math.ceil(holder.time.paint.measureText(holder.time.text.toString())).toInt() + dp(2))
            if (holder.time.layoutParams.width != timeWidth) {
                holder.time.layoutParams = holder.time.layoutParams.apply { width = timeWidth }
            }
            holder.artist.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            holder.song.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            holder.time.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            holder.box.contentDescription = getString(if (row.active) R.string.player_queue_track_playing else R.string.player_queue_track,
                row.detail, row.title, row.duration)
            holder.box.isSelected = row.active
            holder.box.background = SpatialStyle.ripple(this@SpatialPlayerActivity,
                if (row.active) SpatialStyle.selected else SpatialStyle.card, 12)
            holder.box.setOnClickListener { row.action() }
        }
    }
    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private var rows = emptyList<Row>()
        fun submit(value: List<Row>) { rows = value; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val box = LinearLayout(this@SpatialPlayerActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(12), dp(10))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                minimumHeight = dp(80)
                isFocusable = true
            }
            val cover = SpatialArtworkView(this@SpatialPlayerActivity, 16f).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(panelColor, 16); clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val text = column().apply { setPadding(dp(12), 0, dp(6), 0) }
            val title = label("", 15, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val detail = label("", 12).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(SpatialStyle.secondary); setPadding(0, dp(4), 0, 0)
            }
            text.addView(title); text.addView(detail)
            val duration = label("", 11).apply { setTextColor(SpatialStyle.secondary); gravity = Gravity.END }
            val chevron = ImageView(this@SpatialPlayerActivity).apply {
                setImageResource(R.drawable.ic_spatial_chevron)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            box.addView(cover, LinearLayout.LayoutParams(dp(56), dp(56)))
            box.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(duration)
            box.addView(chevron, LinearLayout.LayoutParams(dp(22), dp(22)))
            return RowHolder(box, cover, title, detail, duration, chevron)
        }
        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.title.setTextColor(if (row.active) accent else textColor)
            holder.detail.text = row.detail
            holder.duration.text = row.duration
            holder.duration.visibility = if (row.folder) View.GONE else View.VISIBLE
            holder.chevron.visibility = if (row.folder) View.VISIBLE else View.GONE
            holder.box.background = SpatialStyle.ripple(this@SpatialPlayerActivity,
                if (row.active) SpatialStyle.selected else SpatialStyle.card, 22)
            holder.box.setOnClickListener { row.action() }
            holder.box.isSelected = row.active
            holder.image.setImageResource(if (row.folder) R.drawable.ic_spatial_folder else R.drawable.ic_spatial_cover)
            holder.image.setPadding(if (row.folder) dp(15) else 0, if (row.folder) dp(15) else 0,
                if (row.folder) dp(15) else 0, if (row.folder) dp(15) else 0)
            val token = Any()
            holder.coverToken = token
            row.track?.let { track ->
                getTrackCoverArt(track) { cover ->
                    if (!isDestroyed && holder.coverToken === token) loadGlideResource(cover,
                        RequestOptions().centerCrop(), Size(dp(64), dp(64)), onLoadFailed = {}, onResourceReady = { drawable ->
                            runOnUiThread { if (!isDestroyed && holder.coverToken === token) {
                                holder.image.setPadding(0, 0, 0, 0)
                                holder.image.setImageDrawable(drawable)
                            } }
                        })
                }
            }
        }

        override fun onViewRecycled(holder: RowHolder) { holder.coverToken = null; super.onViewRecycled(holder) }
    }
}
