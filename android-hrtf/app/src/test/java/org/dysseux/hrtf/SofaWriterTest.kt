/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SofaWriterTest {
    private val assets get() = RuntimeEnvironment.getApplication().assets
    private fun manifest() = assets.open("reference_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    private fun template() = assets.open("personal_template.sofa").use { it.readBytes() }
    private fun impulses(m: Int) = Array(m) { Array(2) { FloatArray(2200).apply { this[15] = .5f } } }

    @Test fun outputChangesOnlyAllocatedDatasetsAndCarriesHeadGeometry() {
        val manifest = manifest(); val template = template()
        val output = File("build/reports/sofa-writer-test.sofa")
        val ir = impulses(manifest.getInt("directions"))
        ir[0][0][0] = .123f; ir.last()[1][2199] = -.125f
        SofaWriter.write(assets, output, ir, 22.0)
        val actual = output.readBytes()
        assertEquals(template.size, actual.size)
        val irStart = manifest.getInt("ir_offset"); val irEnd = irStart + manifest.getInt("ir_byte_size")
        val receivers = manifest.getInt("receiver_offset")
        for (i in actual.indices) if (i !in irStart until irEnd && i !in receivers until receivers + 48) {
            if (template[i] != actual[i]) fail("HDF5 metadata modified at byte $i")
        }
        val bytes = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(.123f.toDouble(), bytes.getDouble(irStart), 0.0)
        assertEquals(-.125, bytes.getDouble(irEnd - 8), 0.0)
        assertEquals(.11, bytes.getDouble(receivers + 8), 0.0)
        assertEquals(-.11, bytes.getDouble(receivers + 32), 0.0)
    }

    @Test fun cancellationTamperingAndBadValuesPreservePreviousOutput() {
        val manifest = manifest(); val template = template()
        val output = File(RuntimeEnvironment.getApplication().cacheDir, "previous.sofa").apply { writeText("previous") }
        val ir = impulses(manifest.getInt("directions"))
        assertThrows(CancellationException::class.java) { SofaWriter.writeTemplate(template, manifest, output, ir, 18.0) { true } }
        assertEquals("previous", output.readText())
        val tampered = template.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { SofaWriter.writeTemplate(tampered, manifest, output, ir, 18.0) }
        ir[0][0][0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) { SofaWriter.writeTemplate(template, manifest, output, ir, 18.0) }
        assertEquals("previous", output.readText())
    }

    @Test fun referenceBinaryHashShapeAndFiniteSamplesAreChecked() {
        val reference = AcousticProcessor.load(assets)
        assertEquals(729, reference.positions.size)
        assertEquals(48000, reference.sampleRate)
        assertTrue(reference.ir.all { it.size == 2 && it.all { ear -> ear.any { it != 0.0 } && ear.all(Double::isFinite) } })
    }
}
