/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One worker shared across Activity recreation; partial outputs never become results. */
object ProfileJob {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelFlag = AtomicBoolean(false)
    @Volatile var running = false; private set
    @Volatile var cancellable = false; private set
    @Volatile var message = UiText(R.string.job_ready); private set
    @Volatile var photoToEdit: String? = null
    @Volatile private var predictor: NeuralPredictor? = null
    private fun begin(status: Int, canCancel: Boolean) {
        requireUi(!running,R.string.job_wait)
        cancelFlag.set(false); cancellable=canCancel; message=UiText(status); running=true
    }
    @Synchronized fun importPhoto(context: Context, uri: Uri, side: String) {
        begin(R.string.job_preparing_photo,false)
        val app=context.applicationContext
        worker.execute {
            try { ImageImporter.import(app,uri,side); photoToEdit=side; message=UiText(R.string.photo_needs_points) }
            catch(error: Exception) { message=UiText(R.string.job_import_failed,UiText.error(error)) }
            finally { File(app.filesDir,"capture-$side.jpg").delete(); running=false; cancellable=false }
        }
    }
    @Synchronized fun build(context: Context, left: EarInput, right: EarInput, width: Double, depth: Double) {
        requireUi(width.isFinite() && width in 8.0..30.0 && depth.isFinite() && depth in 8.0..35.0,R.string.head_range)
        begin(R.string.job_left_contours,true)
        val app=context.applicationContext
        worker.execute {
            val root=File(app.filesDir,"profiles").apply { mkdirs() }
            val id=UUID.randomUUID().toString()
            val stage=File(root,".building-$id").apply { mkdirs() }
            val complete=File(root,"profile-$id")
            val bitmaps=mutableListOf<Bitmap>()
            try {
                fun cancelled()=cancelFlag.get()
                fun checkCancel() { if(cancelled()) throw CancellationException() }
                fun prepare(ear: EarInput): Bitmap {
                    checkCancel()
                    val image=InputStore.bitmap(ear.file)
                    return try { PhotoProcessor.prepare(image,ear.calibration,::cancelled).also { contour ->
                        bitmaps += contour
                        File(stage,"${ear.side}-contours.png").outputStream().use { contour.compress(Bitmap.CompressFormat.PNG,100,it) }
                    } } finally { image.recycle() }
                }
                val l=prepare(left); message=UiText(R.string.job_right_contours); val r=prepare(right)
                checkCancel(); message=UiText(R.string.job_loading_models)
                val reference=AcousticProcessor.load(app.assets)
                NeuralPredictor(app).use { model ->
                    predictor=model
                    val lp=model.predict(l,reference.positions,false,::cancelled) { done,total -> message=UiText(R.string.job_left_directions,done,total) }
                    val rp=model.predict(r,reference.positions,true,::cancelled) { done,total -> message=UiText(R.string.job_right_directions,done,total) }
                    checkCancel(); message=UiText(R.string.job_filters)
                    val ir=AcousticProcessor.synthesize(reference,lp,rp,width,depth,::cancelled) { done,total -> message=UiText(R.string.job_filter_progress,done,total) }
                    checkCancel(); message=UiText(R.string.job_writing_sofa)
                    val sofa=SofaWriter.write(app.assets,File(stage,"personal.sofa"),ir,width,::cancelled)
                    val report=JSONObject().put("application","HRTF ${AppStyle.version(app)}")
                        .put("method","Experimental photo-derived ITSADIVE / VIKING estimate; not acoustically measured")
                        .put("created_utc",java.time.Instant.now().toString())
                        .put("head_width_cm",width).put("head_depth_cm",depth)
                        .put("left_photo_sha256",NeuralPredictor.sha(left.file)).put("right_photo_sha256",NeuralPredictor.sha(right.file))
                        .put("left_calibration",left.calibration.toString()).put("right_calibration",right.calibration.toString())
                        .put("sample_rate",reference.sampleRate).put("directions",reference.positions.size)
                        .put("samples",ir[0][0].size).put("sha256",NeuralPredictor.sha(sofa))
                        .put("models",model.manifest)
                    File(stage,"report.json").writeText(report.toString(2))
                    checkCancel()
                    requireUi(stage.renameTo(complete),R.string.error_finish_profile)
                    app.getSharedPreferences("results",Context.MODE_PRIVATE).edit().putString("latest",complete.name).commit()
                    message=UiText(R.string.job_complete)
                }
            } catch(error: Exception) {
                message=if(cancelFlag.get() || error is CancellationException) UiText(R.string.job_cancelled)
                    else UiText(R.string.job_failed,UiText.error(error))
            } finally {
                predictor=null; bitmaps.forEach { it.recycle() }
                stage.deleteRecursively(); cancellable=false; running=false
            }
        }
    }
    fun cancel() { if(cancellable) { cancelFlag.set(true); predictor?.cancel(); message=UiText(R.string.job_stopping) } }
    fun latest(context: Context): File? {
        val name=context.getSharedPreferences("results",Context.MODE_PRIVATE).getString("latest",null) ?: return null
        if(!name.matches(Regex("profile-[0-9a-f-]{36}"))) return null
        return File(context.filesDir,"profiles/$name/personal.sofa").takeIf { it.isFile }
    }
    @Synchronized fun export(context: Context, target: Uri, source: File) {
        begin(R.string.job_saving,false)
        val app=context.applicationContext
        worker.execute {
            try {
                app.contentResolver.openOutputStream(target,"wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: throw LocalizedFailure(R.string.error_save_destination)
                message=UiText(R.string.job_saved)
            } catch(error: Exception) { message=UiText(R.string.job_save_failed,UiText.error(error)) }
            finally { running=false; cancellable=false }
        }
    }
    fun clear(context: Context) {
        check(!running)
        InputStore(context).clear()
        File(context.filesDir,"profiles").deleteRecursively()
        context.getSharedPreferences("results",Context.MODE_PRIVATE).edit().clear().commit()
        photoToEdit=null; message=UiText(R.string.job_cleared)
    }
}
