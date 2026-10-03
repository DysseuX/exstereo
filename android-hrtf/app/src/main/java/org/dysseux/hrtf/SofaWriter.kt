/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.res.AssetManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import org.json.JSONObject

/** Patches only raw, fully allocated contiguous datasets in a verified NetCDF4 SOFA template. */
object SofaWriter {
    fun write(assets: AssetManager, output: File, ir: Array<Array<FloatArray>>, headWidthCm: Double,
              cancelled: () -> Boolean = { false }): File {
        val manifest = assets.open("reference_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val template = assets.open("personal_template.sofa").use { it.readBytes() }
        return writeTemplate(template, manifest, output, ir, headWidthCm, cancelled)
    }

    internal fun writeTemplate(template: ByteArray, manifest: JSONObject, output: File,
                               ir: Array<Array<FloatArray>>, headWidthCm: Double,
                               cancelled: () -> Boolean = { false }): File {
        AcousticProcessor.validateHead(headWidthCm, 20.0)
        val m = manifest.getInt("directions"); val n = manifest.getInt("output_samples")
        require(m in 1..2000 && n == AcousticProcessor.OUTPUT_SAMPLES)
        requireUi(ir.size == m && ir.all { ears -> ears.size == 2 && ears.all { it.size == n && it.all(Float::isFinite) } },R.string.error_hrtf_values)
        require(template.size.toLong() == manifest.getLong("template_size") && template.size <= 100_000_000)
        requireUi(AcousticProcessor.sha256(template) == manifest.getString("template_sha256"),R.string.error_sofa_template)
        require(template.take(8).toByteArray().contentEquals(byteArrayOf(-119, 72, 68, 70, 13, 10, 26, 10)))
        require(manifest.getString("dataset_dtype") == "<f8" && manifest.getString("dataset_layout") == "contiguous")
        val irOffset = manifest.getLong("ir_offset"); val receiverOffset = manifest.getLong("receiver_offset")
        val irSize = m.toLong() * 2 * n * 8; val receiverSize = 6L * 8
        require(manifest.getLong("ir_byte_size") == irSize && manifest.getLong("receiver_byte_size") == receiverSize)
        require(irOffset >= 8 && receiverOffset >= 8 && irOffset <= template.size - irSize && receiverOffset <= template.size - receiverSize)
        require(irOffset + irSize <= receiverOffset || receiverOffset + receiverSize <= irOffset)
        val parent = requireNotNull(output.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile(".personal-", ".sofa.tmp", parent)
        try {
            checkCancelled(cancelled)
            temporary.outputStream().use { it.write(template) }
            RandomAccessFile(temporary, "rw").use { target ->
                target.seek(irOffset)
                val buffer = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
                for (direction in ir) {
                    checkCancelled(cancelled)
                    for (ear in direction) {
                        buffer.clear()
                        for (value in ear) buffer.putDouble(value.toDouble())
                        target.write(buffer.array())
                    }
                }
                target.seek(receiverOffset)
                val receivers = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
                for (value in doubleArrayOf(0.0, headWidthCm / 200, 0.0, 0.0, -headWidthCm / 200, 0.0)) receivers.putDouble(value)
                target.write(receivers.array())
                target.fd.sync()
            }
            checkCancelled(cancelled)
            // Atomic replacement preserves the previous result on cancellation or error.
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return output
        } finally {
            temporary.delete()
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException() }
}
