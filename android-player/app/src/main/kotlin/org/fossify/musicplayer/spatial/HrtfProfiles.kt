/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

object HrtfProfiles {
    data class Profile(val id: String, val title: String, val file: String = "")
    val entries get() = listOf(
        Profile("default", AppLanguage.string(R.string.audio_profile_default)),
        Profile("kemar", AppLanguage.string(R.string.audio_profile_kemar), "mit_kemar_normal_pinna.sofa"),
        Profile("sadie_h3", AppLanguage.string(R.string.audio_profile_h3), "sadie_h3.sofa"),
        Profile("sadie_h10", AppLanguage.string(R.string.audio_profile_h10), "sadie_h10.sofa")
    )

    const val MAX_IMPORT_BYTES = 128L * 1024 * 1024
    private const val IMPORT_DIRECTORY = "hrtf-imports-v1"
    private const val IMPORT_PREFERENCES = "hrtf_imports_v1"
    private val importedId = Regex("user_[0-9a-f]{64}")
    private val importLock = Any()

    /** Small metadata only. SOFA parsing and copying must run on a worker thread. */
    fun entries(context: Context): List<Profile> {
        val directory = File(context.filesDir, IMPORT_DIRECTORY)
        val imported = context.getSharedPreferences(IMPORT_PREFERENCES, Context.MODE_PRIVATE).all
            .mapNotNull { (id, title) ->
                if (importedId.matches(id) && title is String && File(directory, "$id.sofa").isFile)
                    Profile(id, title, "$id.sofa") else null
            }.sortedBy { it.title.lowercase() }
        return entries + imported
    }

    fun importDocument(context: Context, uri: Uri): Profile {
        var title = AppLanguage.string(R.string.audio_my_profile)
        var declaredSize: Long? = null
        // Providers may omit metadata; the streaming limit below is authoritative.
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) title = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredSize = cursor.getLong(sizeColumn)
                }
            }
        } catch (_: Exception) { /* Some document providers do not support queries. */ }
        if ((declaredSize ?: 0) > MAX_IMPORT_BYTES) throw IOException(AppLanguage.string(R.string.audio_sofa_too_large))
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(AppLanguage.string(R.string.audio_sofa_open_failed))
        return input.use { importStream(context, title, it) }
    }

    /** Publication is transactional: rejected imports cannot replace an existing file or selection. */
    internal fun importStream(context: Context, name: String, input: InputStream, maximumBytes: Long = MAX_IMPORT_BYTES): Profile = synchronized(importLock) {
        val directory = File(context.filesDir, IMPORT_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException(AppLanguage.string(R.string.audio_profile_directory_failed))
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > maximumBytes) throw IOException(AppLanguage.string(R.string.audio_sofa_size_limit))
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.fd.sync()
            }
            if (total == 0L) throw IOException(AppLanguage.string(R.string.audio_sofa_empty))
            val header = ByteArray(8)
            temporary.inputStream().use { it.read(header) }
            val hdf5 = byteArrayOf(0x89.toByte(), 0x48, 0x44, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)
            if (!header.contentEquals(hdf5)) throw IOException(AppLanguage.string(R.string.audio_sofa_invalid_container))
            // Steam Audio validates convention and geometry under the playback lifecycle lock.
            var handle = 0L
            try {
                handle = NativeSpatialEngine.create(48000, temporary.absolutePath)
                if (handle == 0L) throw IOException(AppLanguage.string(R.string.audio_sofa_load_failed))
            } catch (error: Exception) {
                throw IOException(AppLanguage.string(R.string.audio_sofa_incompatible), error)
            } finally {
                if (handle != 0L) NativeSpatialEngine.release(handle)
            }
            checkInterrupted()
            val id = "user_" + digest.digest().joinToString("") { "%02x".format(it) }
            val title = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("(?i)\\.sofa$"), "").filterNot { it.isISOControl() }.trim().take(100)
                .ifEmpty { AppLanguage.string(R.string.audio_my_profile) }
            synchronized(this) publish@ {
                val preferences = context.getSharedPreferences(IMPORT_PREFERENCES, Context.MODE_PRIVATE)
                val target = File(directory, "$id.sofa")
                val existingTitle = preferences.getString(id, null)
                if (target.isFile && existingTitle != null) return@publish Profile(id, existingTitle, target.name)
                // A content-derived name is never a provider-controlled path.
                if (!temporary.renameTo(target)) throw IOException(AppLanguage.string(R.string.audio_sofa_save_failed))
                if (!preferences.edit().putString(id, title).commit()) {
                    target.delete()
                    throw IOException(AppLanguage.string(R.string.audio_profile_description_save_failed))
                }
                Profile(id, title, target.name)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException(AppLanguage.string(R.string.audio_import_cancelled))
    }

    @Synchronized fun path(context: Context, id: String): String {
        if (importedId.matches(id)) {
            val imported = File(File(context.filesDir, IMPORT_DIRECTORY), "$id.sofa")
            if (!imported.isFile) throw IOException(AppLanguage.string(R.string.audio_sofa_missing))
            return imported.absolutePath
        }
        val profile = entries.firstOrNull { it.id == id } ?: entries.first()
        if (profile.file.isEmpty()) return ""
        val directory = File(context.filesDir, "hrtf-v1").apply { mkdirs() }
        val target = File(directory, profile.file)
        if (!target.isFile) {
            val temporary = File(directory, profile.file + ".tmp")
            try {
                context.assets.open("hrtf/${profile.file}").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(temporary.renameTo(target)) { AppLanguage.string(R.string.audio_hrtf_save_failed) }
            } finally {
                temporary.delete()
            }
        }
        return target.absolutePath
    }
}
