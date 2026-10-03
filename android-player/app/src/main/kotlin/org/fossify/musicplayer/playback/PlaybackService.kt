/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.core.os.postDelayed
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.showErrorToast
import org.fossify.musicplayer.extensions.isReallyPlaying
import org.fossify.musicplayer.extensions.nextMediaItem
import org.fossify.musicplayer.extensions.runOnPlayerThread
import org.fossify.musicplayer.helpers.NotificationHelper
import org.fossify.musicplayer.helpers.AppLanguage
import org.fossify.musicplayer.helpers.getPermissionToRequest
import org.fossify.musicplayer.playback.library.MediaItemProvider
import org.fossify.musicplayer.playback.player.SimpleMusicPlayer
import org.fossify.musicplayer.playback.player.initializeSessionAndPlayer
import org.fossify.musicplayer.diagnostics.StartupDiagnostics

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), MediaSessionService.Listener {
    internal lateinit var player: SimpleMusicPlayer
    internal lateinit var playerListener: Player.Listener
    internal lateinit var mediaSession: MediaLibrarySession
    internal lateinit var mediaItemProvider: MediaItemProvider

    internal var currentRoot = ""
    private var libraryLanguage: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyTo(this)
        val language = resources.configuration.locales[0].language
        if (libraryLanguage != language) {
            libraryLanguage = language
            if (::mediaItemProvider.isInitialized && ::mediaSession.isInitialized) {
                val root = mediaItemProvider.getRootItem()
                val count = mediaItemProvider.getChildren(root.mediaId)?.size ?: 0
                mediaSession.notifyChildrenChanged(root.mediaId, count, null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.mark("playback service creating player")
        setListener(this)
        initializeSessionAndPlayer(handleAudioFocus = true, handleAudioBecomingNoisy = true)
        StartupDiagnostics.mark("playback service initializing media library")
        initializeLibrary()
        StartupDiagnostics.mark("playback service ready")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSession()
        clearListener()
        stopSleepTimer()
        SimpleEqualizer.release()
    }

    fun stopService() {
        withPlayer {
            pause()
            stop()
        }

        stopSelf()
    }

    private fun initializeLibrary() {
        mediaItemProvider = MediaItemProvider(this)
        libraryLanguage = resources.configuration.locales[0].language
        if (hasPermission(getPermissionToRequest())) {
            mediaItemProvider.reload()
        } else {
            showNoPermissionNotification()
        }
    }

    private fun releaseMediaSession() {
        mediaSession.release()
        withPlayer {
            removeListener(playerListener)
            release()
        }
    }

    internal fun withPlayer(callback: SimpleMusicPlayer.() -> Unit) {
        player.runOnPlayerThread { callback(this) }
    }

    private fun showNoPermissionNotification() {
        Handler(Looper.getMainLooper()).postDelayed(delayInMillis = 100L) {
            try {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.createInstance(this).createNoPermissionNotification()
                )
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * This method is only required to be implemented on Android 12 or above when an attempt is made
     * by a media controller to resume playback when the {@link MediaSessionService} is in the
     * background.
     */
    override fun onForegroundServiceStartNotAllowedException() {
        showErrorToast(getString(org.fossify.commons.R.string.unknown_error_occurred))
        // todo: show a notification instead.
    }

    companion object {
        // Initializing a media controller might take a noticeable amount of time thus we expose current playback info here to keep things as quick as possible.
        var isPlaying: Boolean = false
            private set
        var currentMediaItem: MediaItem? = null
            private set
        var nextMediaItem: MediaItem? = null
            private set

        fun updatePlaybackInfo(player: Player) {
            currentMediaItem = player.currentMediaItem
            nextMediaItem = player.nextMediaItem
            isPlaying = player.isReallyPlaying
        }
    }
}
