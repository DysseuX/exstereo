/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import androidx.media3.common.Player

/**
 * Rebuild the audio chain even while paused. A seek to the current position can
 * be ignored by Media3. stop() retains the playlist and position; prepare()
 * recreates the renderer and flushes processors with the new HRTF settings.
 */
fun Player.refreshSpatialHrtf(): Boolean {
    if (currentMediaItem == null) return true
    if (!isCommandAvailable(Player.COMMAND_STOP) ||
        !isCommandAvailable(Player.COMMAND_PREPARE) ||
        !isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return false
    val resume = playWhenReady
    stop()
    playWhenReady = resume
    prepare()
    return true
}
