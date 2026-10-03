/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.fossify.musicplayer.extensions.toMediaItem
import org.fossify.musicplayer.helpers.QueueDocuments
import org.fossify.musicplayer.helpers.QueueEdits
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.lang.reflect.Proxy

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class)
class QueueEditingTest {
    class Documents : ContentProvider() {
        var writes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (projection?.contentEquals(arrayOf(OpenableColumns.DISPLAY_NAME)) == true) {
                return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("selected.dts")) }
            }
            return MatrixCursor(projection).apply {
                when (DocumentsContract.getDocumentId(uri)) {
                    "root" -> {
                        addRow(arrayOf("root/a", "Album", DocumentsContract.Document.MIME_TYPE_DIR))
                        addRow(arrayOf("root/song.flac", "song.flac", "audio/flac"))
                        addRow(arrayOf("root/notes.txt", "notes.txt", "text/plain"))
                    }
                    "root/a" -> {
                        addRow(arrayOf("root/a/demo.dts", "demo.dts", "application/octet-stream"))
                        addRow(arrayOf("root/a/cover.jpg", "cover.jpg", "image/jpeg"))
                    }
                }
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("Read-only provider") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes++; error("Read-only provider") }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes++; error("Never delete music") }
    }

    private class Queue(initial: List<MediaItem> = emptyList()) {
        val items = initial.toMutableList()
        val removed = ArrayList<Int>()
        var prepared = false
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getPlaybackState" -> Player.STATE_IDLE
                "addMediaItems" -> { @Suppress("UNCHECKED_CAST") items.addAll(args!![0] as List<MediaItem>); null }
                "removeMediaItem" -> { val index = args!![0] as Int; removed.add(index); items.removeAt(index); null }
                "prepare" -> { prepared = true; null }
                else -> error("Unexpected player mutation/query: ${method.name}")
            }
        } as Player
    }

    private fun source(): Pair<Documents, Uri> {
        val provider = Documents()
        ShadowContentResolver.registerProviderInternal("queue.test", provider)
        return provider to DocumentsContract.buildTreeDocumentUri("queue.test", "root")
    }

    @Test fun recursiveFolderReadsOnlyAudioAndKeepsIndividualParentGroups() {
        val (provider, tree) = source()
        val tracks = QueueDocuments.readFolder(RuntimeEnvironment.getApplication().contentResolver, tree) { false }
        assertEquals(setOf("song.flac", "demo.dts"), tracks.map { it.title }.toSet())
        assertEquals(2, tracks.map { QueueDocuments.folderKey(it) }.toSet().size)
        assertTrue(tracks.all { it.mediaStoreId < 0 && it.path.startsWith("content://") })
        assertEquals(0, provider.writes)
    }

    @Test fun appendingDeduplicatesWithoutRestartingOrPlayingAndRemovalUsesDescendingIndices() {
        val (_, tree) = source()
        val tracks = QueueDocuments.readFolder(RuntimeEnvironment.getApplication().contentResolver, tree) { false }
        val queue = Queue(listOf(tracks.first().toMediaItem()))
        assertEquals(1, QueueEdits.append(queue.player, tracks + tracks))
        assertTrue(queue.prepared)
        assertEquals(2, queue.items.size)
        val snapshot = queue.items.toList()
        assertTrue(QueueEdits.remove(queue.player, snapshot, listOf(0, 1)))
        assertEquals(listOf(1, 0), queue.removed)
        assertTrue(queue.items.isEmpty())
    }

    @Test fun staleSelectionCannotRemoveDifferentQueueAndCancelDoesNotReadFolder() {
        val (_, tree) = source()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        assertTrue(QueueDocuments.readFolder(resolver, tree) { true }.isEmpty())
        val track = QueueDocuments.readFile(resolver, DocumentsContract.buildDocumentUri("queue.test", "root/selected.dts"))
        val queue = Queue(listOf(track.toMediaItem()))
        val snapshot = queue.items.toList()
        queue.items.add(track.toMediaItem())
        assertFalse(QueueEdits.remove(queue.player, snapshot, listOf(0)))
        assertTrue(queue.removed.isEmpty())
        assertEquals(2, queue.items.size)
    }
}
