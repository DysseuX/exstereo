/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.helpers

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.fossify.musicplayer.extensions.currentMediaItems
import org.fossify.musicplayer.extensions.toTrack
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.models.toMediaItems

internal object QueueEdits {
    fun append(player: Player, tracks: List<Track>): Int {
        val queuedPaths = player.currentMediaItems.mapNotNull { it.toTrack()?.path }.toHashSet()
        val additions = tracks.filter { queuedPaths.add(it.path) }.toMediaItems()
        if (additions.isNotEmpty()) {
            player.addMediaItems(additions)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
        return additions.size
    }

    /** Refuse stale dialogs; descending indices preserve duplicate occurrences and the surviving item. */
    fun remove(player: Player, snapshot: List<MediaItem>, selected: List<Int>): Boolean {
        if (player.currentMediaItems != snapshot) return false
        require(selected.all { it in snapshot.indices })
        selected.distinct().sortedDescending().forEach { player.removeMediaItem(it) }
        return true
    }
}
