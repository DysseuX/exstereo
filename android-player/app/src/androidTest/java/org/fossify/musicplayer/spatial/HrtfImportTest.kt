/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only */

package org.fossify.musicplayer.spatial

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Runs real Steam Audio validation, isolated from the listener's profile files/preferences. */
@RunWith(AndroidJUnit4::class)
class HrtfImportTest {
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var preferencePrefix: String
    private val preferenceNames = mutableSetOf<String>()

    @Before fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        preferencePrefix = "hrtf-test-${UUID.randomUUID()}-"
        directory = File(base.cacheDir, preferencePrefix).apply { mkdirs() }
        context = isolatedContext()
    }

    private fun isolatedContext(): Context = object : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = preferencePrefix + name
            preferenceNames.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }

    @After fun tearDown() {
        directory.deleteRecursively()
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
    }

    private fun expectRejected(operation: () -> Unit) {
        try {
            operation()
            fail("Invalid import was accepted")
        } catch (_: IOException) { }
    }

    @Test fun validatedImportPersistsAndRejectedReplacementPreservesIt() {
        val originalSettings = SpatialPreferences.current
        val source = base.assets.open("hrtf/mit_kemar_normal_pinna.sofa").use { it.readBytes() }
        val imported = HrtfProfiles.importStream(context, "../../Мои уши.sofa", ByteArrayInputStream(source))
        val digest = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        assertEquals("user_$digest", imported.id)
        assertEquals("Мои уши", imported.title)
        val importedFile = File(HrtfProfiles.path(context, imported.id))
        assertEquals(File(directory, "hrtf-imports-v1").canonicalFile, importedFile.parentFile!!.canonicalFile)
        assertArrayEquals(source, importedFile.readBytes())

        // A second context reads only persisted metadata and the private copy.
        val reopened = isolatedContext()
        assertTrue(HrtfProfiles.entries(reopened).contains(imported))
        assertEquals(importedFile.absolutePath, HrtfProfiles.path(reopened, imported.id))
        val duplicate = HrtfProfiles.importStream(reopened, "Другой заголовок.sofa", ByteArrayInputStream(source))
        assertEquals(imported, duplicate)

        expectRejected { HrtfProfiles.importStream(context, "Мои уши.sofa", ByteArrayInputStream("not SOFA".toByteArray())) }
        // Correct HDF5 signature but an incomplete document exercises the real loader's rejection.
        expectRejected { HrtfProfiles.importStream(context, "truncated.sofa", ByteArrayInputStream(source.copyOf(64))) }
        assertEquals(HrtfProfiles.entries.size + 1, HrtfProfiles.entries(context).size)
        assertArrayEquals(source, importedFile.readBytes())
        assertEquals(listOf(importedFile.name), importedFile.parentFile!!.listFiles()!!.map { it.name })
        assertEquals("Import must not change playback settings before the UI accepts it", originalSettings, SpatialPreferences.current)
    }

    @Test fun streamingLimitAndEmptyFilesLeaveNoPublishedProfileOrTemporaryFile() {
        expectRejected {
            HrtfProfiles.importStream(context, "too-large.sofa", ByteArrayInputStream(ByteArray(65)), maximumBytes = 64)
        }
        expectRejected { HrtfProfiles.importStream(context, "empty.sofa", ByteArrayInputStream(byteArrayOf())) }
        assertEquals(HrtfProfiles.entries, HrtfProfiles.entries(context))
        assertTrue(File(directory, "hrtf-imports-v1").listFiles()!!.isEmpty())
    }
}
