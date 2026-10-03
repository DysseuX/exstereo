/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.room.Room
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.musicplayer.LocalizedTestApplication
import org.fossify.musicplayer.R
import org.fossify.musicplayer.databases.SongsDatabase
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.extensions.toMediaItem
import org.fossify.musicplayer.helpers.ALL_TABS_MASK
import org.fossify.musicplayer.helpers.AppLanguage
import org.fossify.musicplayer.helpers.FLAG_MANUAL_CACHE
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.library.MediaItemProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33], application = LocalizedTestApplication::class, qualifiers = "en")
class MediaLibraryLanguageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: SongsDatabase

    @Before fun prepareDatabase() {
        context.getSharedPrefs().edit().clear().commit()
        context.config.showTabs = ALL_TABS_MASK
        database = Room.inMemoryDatabaseBuilder(context, SongsDatabase::class.java).allowMainThreadQueries().build()
        SongsDatabase::class.java.getDeclaredField("db").apply { isAccessible = true }.set(null, database)
    }

    @After fun closeDatabase() {
        database.close()
        SongsDatabase.destroyInstance()
    }

    @Test fun existingBrowserTreeChangesLanguageWithoutChangingTracksOrResumePosition() {
        val provider = MediaItemProvider(context)
        val track = Track(0, -9101, "User title remains unchanged", "", "content://test.documents/document/Example.flac",
            0, "", "", "", 0, null, null, "content://test.documents/document/audio", 0, 0, 0, 0, 0, 0, FLAG_MANUAL_CACHE)
        val item = track.toMediaItem()
        // Populate one real cached child without starting a MediaStore scan.
        MediaItemProvider::class.java.getDeclaredMethod("addNodeAndChildren", String::class.java,
            MediaItem::class.java, List::class.java).apply { isAccessible = true }
            .invoke(provider, "__TRACKS__", item, null)
        provider.saveRecentItemsWithStartPosition(listOf(item), item, 12000)!!.get(5, TimeUnit.SECONDS)
        val savedQueue = database.QueueItemsDao().getAll()
        val originalRoot = provider.getRootItem()
        val originalChildren = provider.getChildren(originalRoot.mediaId)!!
        val originalTracks = provider.getDefaultQueue()!!
        assertEquals(6, originalChildren.size)
        assertEquals("Tracks", provider["__TRACKS__"]!!.mediaMetadata.title.toString())

        for ((qualifiers, language, trackTitle) in listOf(
            Triple("ru-rRU", "ru", "Композиции"), Triple("de-rDE", "en", "Tracks")
        )) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            AppLanguage.applyTo(context)
            assertEquals(language, context.resources.configuration.locales[0].language)
            val root = provider.getRootItem()
            assertEquals(originalRoot.mediaId, root.mediaId)
            assertEquals(context.getString(org.fossify.commons.R.string.root), root.mediaMetadata.title.toString())
            val children = provider.getChildren(root.mediaId)!!
            assertEquals(originalChildren.map { it.mediaId }, children.map { it.mediaId })
            val expectedTitles = listOf(R.string.playlists, R.string.folders, R.string.artists,
                R.string.albums, R.string.tracks, R.string.genres).map { context.getString(it) }
            assertEquals(expectedTitles, children.map { it.mediaMetadata.title.toString() })
            originalChildren.zip(children).forEach { (before, after) ->
                val expected = before.buildUpon().setMediaMetadata(
                    before.mediaMetadata.buildUpon().setTitle(after.mediaMetadata.title).build()).build()
                assertEquals(expected, after)
                assertEquals(after, provider[after.mediaId])
            }
            assertEquals(trackTitle, provider["__TRACKS__"]!!.mediaMetadata.title.toString())
            assertEquals(originalTracks, provider.getDefaultQueue())
            assertSame(item, provider.getDefaultQueue()!!.single())
            assertSame(item, provider[item.mediaId])
            assertEquals(savedQueue, database.QueueItemsDao().getAll())
            val resumed = provider.getRecentItemsWithStartPosition()
            assertEquals(listOf(item), resumed.mediaItems)
            assertEquals(0, resumed.startIndex)
            assertEquals(12000L, resumed.startPositionMs)
        }
    }
}
