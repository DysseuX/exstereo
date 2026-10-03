/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback

import android.app.Application
import android.content.Context
import androidx.room.Room
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.musicplayer.databases.SongsDatabase
import org.fossify.musicplayer.extensions.audioHelper
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.extensions.toMediaItem
import org.fossify.musicplayer.helpers.FLAG_MANUAL_CACHE
import org.fossify.musicplayer.helpers.SimpleMediaScanner
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.library.MediaItemProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class)
class MediaQueuePersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: SongsDatabase

    @Before fun prepareDatabase() {
        context.getSharedPrefs().edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, SongsDatabase::class.java).allowMainThreadQueries().build()
        SongsDatabase::class.java.getDeclaredField("db").apply { isAccessible = true }.set(null, database)
    }

    @After fun closeDatabase() {
        database.close()
        SongsDatabase.destroyInstance()
    }

    private fun document() = Track(0, -9001, "Example.flac", "", "content://test.documents/tree/audio/document/audio%2FExample.flac",
        0, "", "", "", 0, null, null, "content://test.documents/document/audio", 0, 0, 0, 0, 0, 0, FLAG_MANUAL_CACHE)

    @Test fun safMetadataAndNegativeIdSurviveReloadAndMediaStoreCleanup() {
        val track = document()
        val item = track.toMediaItem()
        val provider = MediaItemProvider(context)
        provider.saveRecentItemsWithStartPosition(listOf(item), item, 12000)!!.get(5, TimeUnit.SECONDS)
        assertEquals(track.path, context.audioHelper.getTrack(track.mediaStoreId)!!.path)

        val scanner = SimpleMediaScanner(context.applicationContext as Application)
        SimpleMediaScanner::class.java.getDeclaredMethod("cleanupDatabase").apply { isAccessible = true }.invoke(scanner)
        assertEquals(track.path, context.audioHelper.getTrack(track.mediaStoreId)!!.path)
        val restored = MediaItemProvider(context).getRecentItemsWithStartPosition()
        assertEquals(listOf(track.mediaStoreId.toString()), restored.mediaItems.map { it.mediaId })
        assertEquals(track.path, restored.mediaItems.single().localConfiguration!!.uri.toString())
        assertEquals(12000L, restored.startPositionMs)
    }

    @Test fun clearingQueuePersistsEmptyWithoutDeletingDocumentMetadataOrResurrectingLibrary() {
        val track = document()
        val item = track.toMediaItem()
        val provider = MediaItemProvider(context)
        provider.saveRecentItemsWithStartPosition(listOf(item), item, 0)!!.get(5, TimeUnit.SECONDS)
        provider.saveRecentItemsWithStartPosition(emptyList(), null, 0)!!.get(5, TimeUnit.SECONDS)
        assertTrue(database.QueueItemsDao().getAll().isEmpty())
        assertNotNull(context.audioHelper.getTrack(track.mediaStoreId))
        assertTrue(context.config.wasQueueInitialized)

        val done = CountDownLatch(1)
        var restored: List<Track>? = null
        context.audioHelper.getQueuedTracksLazily { tracks, _, _ -> restored = tracks; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<Track>(), restored)
        assertTrue(database.QueueItemsDao().getAll().isEmpty())
    }

    @Test fun initialEmptyPlayerCannotOverwritePreviousQueueBeforeRestoration() {
        val item = document().toMediaItem()
        MediaItemProvider(context).saveRecentItemsWithStartPosition(listOf(item), item, 9000)!!.get(5, TimeUnit.SECONDS)
        val freshProvider = MediaItemProvider(context)
        assertNull(freshProvider.saveRecentItemsWithStartPosition(emptyList(), null, 0))
        val restored = freshProvider.getRecentItemsWithStartPosition()
        assertEquals(1, restored.mediaItems.size)
        assertEquals(item.mediaId, restored.mediaItems.single().mediaId)
        assertEquals(9000L, restored.startPositionMs)
    }
}
