/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Runs the deterministic encoder and PRTF decoder graphs. */
class NeuralPredictor(private val context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    val manifest = JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
    private val size = manifest.getInt("embedding_size")
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1); setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addConfigEntry("session.inter_op.allow_spinning", "0")
    }
    private val encoder: OrtSession
    private val decoder: OrtSession
    @Volatile private var activeRun: OrtSession.RunOptions? = null
    init {
        var first: OrtSession? = null
        try {
            first = environment.createSession(model("encoder").absolutePath, options)
            encoder = first
            decoder = environment.createSession(model("decoder").absolutePath, options)
        } catch (error: Throwable) { first?.close(); options.close(); throw error }
    }
    private fun model(key: String): File {
        val item = manifest.getJSONObject(key)
        val name = item.getString("filename")
        require(name.matches(Regex("[a-z_]+\\.onnx")))
        val target = File(context.filesDir,"models/$name").apply { parentFile!!.mkdirs() }
        val expected = item.getString("sha256")
        if (!target.isFile || sha(target) != expected) {
            val part = File(target.parentFile,"$name.part")
            try {
                context.assets.open("models/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                requireUi(sha(part) == expected,R.string.error_model_corrupt,name)
                requireUi(part.renameTo(target),R.string.error_model_prepare)
            } finally { part.delete() }
        }
        return target
    }
    fun predict(contours: Bitmap, positions: Array<DoubleArray>, right: Boolean,
                cancelled: () -> Boolean, progress: (Int,Int) -> Unit): Array<FloatArray> {
        require(contours.width == 256 && contours.height == 256)
        fun checkCancelled() { if (cancelled()) throw CancellationException() }
        checkCancelled()
        val pixels = IntArray(256*256); contours.getPixels(pixels,0,256,0,0,256,256)
        val input = FloatArray(pixels.size) { (pixels[it] and 255)/255f }
        val embedding = OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,1,256,256)).use { tensor ->
            run(encoder,mapOf("contour" to tensor),cancelled).use { output ->
                (output[0].value as Array<*>)[0] as FloatArray
            }
        }
        require(embedding.size == size)
        val results = Array(positions.size) { FloatArray(257) }
        for (start in positions.indices step 64) {
            checkCancelled()
            val count = minOf(64,positions.size-start)
            val features = FloatArray(count*(size+2))
            repeat(count) { index ->
                embedding.copyInto(features,index*(size+2))
                val position = positions[start+index]
                var azimuth = if (right) -position[0] else position[0]
                azimuth = ((azimuth % 360)+360)%360
                features[index*(size+2)+size] = position[1].toFloat()
                features[index*(size+2)+size+1] = if (azimuth>90 && azimuth<270) -180f else 0f
            }
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(features),longArrayOf(count.toLong(),(size+2).toLong())).use { tensor ->
                run(decoder,mapOf("features" to tensor),cancelled).use { output ->
                    val values = output[0].value as Array<*>
                    require(values.size == count)
                    repeat(count) { index ->
                        val row = values[index] as FloatArray
                        requireUi(row.size==257 && row.all { it.isFinite() },R.string.error_model_output)
                        results[start+index] = row.copyOf()
                    }
                }
            }
            progress(start+count,positions.size)
        }
        return results
    }
    private fun run(session: OrtSession, inputs: Map<String,OnnxTensor>, cancelled: () -> Boolean): OrtSession.Result {
        OrtSession.RunOptions().use { run ->
            activeRun = run
            try {
                if (cancelled()) throw CancellationException()
                return session.run(inputs,run)
            } finally { activeRun = null }
        }
    }
    fun cancel() { runCatching { activeRun?.setTerminate(true) } }
    override fun close() { decoder.close(); encoder.close(); options.close() }
    companion object {
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
