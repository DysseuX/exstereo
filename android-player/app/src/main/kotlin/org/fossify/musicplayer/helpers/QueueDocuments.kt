/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.helpers

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.fossify.musicplayer.R
import org.fossify.musicplayer.models.Track
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Reads user-selected documents; never writes to or deletes their source files. */
internal object QueueDocuments {
    private val extensions = setOf("mp3", "flac", "opus", "ogg", "oga", "wav", "m4a", "mp4", "aac",
        "adts", "ac3", "eac3", "ec3", "dts", "dtshd", "dts-hd", "ape", "mka", "webm", "aif", "aiff")

    fun isAudio(name: String, mime: String?) = mime?.startsWith("audio/") == true ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions

    fun readFile(resolver: ContentResolver, uri: Uri): Track {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: AppLanguage.string(R.string.player_audio_file)
        require(isAudio(name, resolver.getType(uri))) { AppLanguage.string(R.string.player_unsupported_audio, name) }
        return track(uri, name)
    }

    fun readFolder(resolver: ContentResolver, tree: Uri, cancelled: () -> Boolean): List<Track> {
        val pending = ArrayDeque<String>()
        val visited = HashSet<String>()
        val tracks = ArrayList<Track>()
        pending.add(DocumentsContract.getTreeDocumentId(tree))
        while (pending.isNotEmpty() && !cancelled()) {
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor = resolver.query(children, projection, null, null, null)
                ?: error(AppLanguage.string(R.string.player_folder_read_error))
            cursor.use {
                while (it.moveToNext() && !cancelled()) {
                    val id = it.getString(0)
                    val name = it.getString(1) ?: AppLanguage.string(R.string.player_audio_file)
                    val mime = it.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id)
                    else if (isAudio(name, mime)) tracks.add(track(DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                        DocumentsContract.buildDocumentUri(tree.authority!!, parent).toString()))
                }
            }
        }
        return tracks.sortedWith(compareBy<Track> { folderKey(it) }.thenBy { it.title.lowercase(Locale.ROOT) })
    }

    private fun track(uri: Uri, name: String, folder: String = ""): Track {
        val path = uri.toString()
        // Negative, stable IDs cannot collide with positive MediaStore IDs.
        val id = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))).long or Long.MIN_VALUE
        return Track(0, id, name, MediaStore.UNKNOWN_STRING, path, 0, "", "", "", 0, null, null,
            folder, 0, 0, 0, 0, 0, 0, FLAG_MANUAL_CACHE)
    }

    fun folderKey(track: Track): String {
        if (!track.path.startsWith("content://")) return File(track.path).parent.orEmpty()
        if (track.folderName.startsWith("content://")) return track.folderName
        val uri = Uri.parse(track.path)
        val document = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (document != null && document.contains('/')) {
            val parent = document.substringBeforeLast('/')
            return DocumentsContract.buildDocumentUri(uri.authority!!, parent).toString()
        }
        return ""
    }

    fun folderLabel(key: String): String {
        if (key.isEmpty()) return AppLanguage.string(R.string.player_individual_files)
        if (!key.startsWith("content://")) return key
        return runCatching { DocumentsContract.getDocumentId(Uri.parse(key)) }.getOrDefault(key)
    }
}
