/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.app.Application
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeuralPipelineTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val fixtures=File("../test-data/neural")
    @Test fun actualJavaRuntimeMatchesOriginalPyTorchPredictionsAndHonorsCancel() {
        val manifest=JSONObject(File(fixtures,"manifest.json").readText())
        val cases=manifest.getJSONArray("cases")
        NeuralPredictor(app).use { model ->
            repeat(cases.length()) { index ->
                val case=cases.getJSONObject(index)
                val bitmap=BitmapFactory.decodeFile(File(fixtures,case.getString("contour_png")).absolutePath)!!
                try {
                    val branches=case.getJSONArray("decoder_cases")
                    repeat(branches.length()) { branch ->
                        val spec=branches.getJSONObject(branch)
                        val p=spec.getJSONArray("positions")
                        val positions=Array(p.length()) { i -> DoubleArray(3) { axis -> p.getJSONArray(i).getDouble(axis) } }
                        val result=model.predict(bitmap,positions,spec.getBoolean("right"),{false}) { _,_ -> }
                        val expected=spec.getJSONObject("expected")
                        val file=File(fixtures,expected.getString("filename"))
                        assertEquals(expected.getString("sha256"),NeuralPredictor.sha(file))
                        val values=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        result.forEach { row -> row.forEach { actual -> assertEquals(values.get().toDouble(),actual.toDouble(),.0005) } }
                        assertEquals(0,values.remaining())
                    }
                    assertThrows(CancellationException::class.java) {
                        model.predict(bitmap,arrayOf(doubleArrayOf(0.0,0.0,1.0)),false,{true}) { _,_ -> }
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
    @Test fun completePhonePipelineProducesFiniteCompatibleSofaFromBothPhotoContours() {
        val reference=AcousticProcessor.load(app.assets)
        val source=javaClass.getResourceAsStream("/parity/photo_input.png")!!.use { BitmapFactory.decodeStream(it) }!!
        val calibration=PhotoProcessor.Calibration(300.0,300.0,40.0,20.0,240.0,20.0,40.0)
        val left=PhotoProcessor.prepare(source,calibration)
        val right=PhotoProcessor.prepare(source,calibration.copy(mirror=true))
        source.recycle()
        try {
            NeuralPredictor(app).use { model ->
                val lp=model.predict(left,reference.positions,false,{false}) { _,_ -> }
                val rp=model.predict(right,reference.positions,true,{false}) { _,_ -> }
                val ir=AcousticProcessor.synthesize(reference,lp,rp,18.0,20.0)
                assertEquals(729,ir.size)
                assertTrue(ir.all { it.size==2 && it.all { ear -> ear.size==2200 && ear.all(Float::isFinite) } })
                val output=File("build/reports/android-hrtf-pipeline/personal.sofa")
                SofaWriter.write(app.assets,output,ir,18.0)
                assertTrue(output.length()>20_000_000L)
                File(output.parentFile,"verification.json").writeText(JSONObject().put("method","Synthetic test only")
                    .put("sha256",NeuralPredictor.sha(output)).put("sample_rate",reference.sampleRate)
                    .put("directions",ir.size).put("samples",2200).toString(2))
                File(output.parentFile,"left-contours.png").outputStream().use { left.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                File(output.parentFile,"right-contours.png").outputStream().use { right.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally { left.recycle(); right.recycle() }
    }
}
