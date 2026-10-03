/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.InputStream
import kotlin.math.max

object ImageImporter {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_EDGE = 1600
    fun import(context: Context, source: Uri, side: String): File {
        val incoming = File(context.cacheDir, "incoming-photo.tmp")
        val prepared = File(context.filesDir, "$side-new.png")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                incoming.outputStream().use { output ->
                    val buffer = ByteArray(32768); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; requireUi(total <= MAX_BYTES,R.string.error_photo_large)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw LocalizedFailure(R.string.error_photo_open)
            decode(incoming).let { bitmap ->
                try { prepared.outputStream().use { requireUi(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it),R.string.error_photo_save) } }
                finally { bitmap.recycle() }
            }
            val store = InputStore(context)
            requireUi(prepared.renameTo(store.photo(side)),R.string.error_photo_save)
            store.clearCalibration(side)
            return store.photo(side)
        } finally { incoming.delete(); prepared.delete() }
    }

    internal fun decode(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        requireUi(options.outWidth > 0 && options.outHeight > 0,R.string.error_photo_format)
        requireUi(options.outWidth.toLong() * options.outHeight <= 200_000_000L,R.string.error_photo_resolution)
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: throw LocalizedFailure(R.string.error_photo_decode)
        if (max(bitmap.width, bitmap.height) > MAX_EDGE) {
            val factor = MAX_EDGE.toDouble() / max(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(bitmap, max(1,(bitmap.width * factor).toInt()), max(1,(bitmap.height * factor).toInt()), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }
}
