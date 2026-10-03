/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class PhotoActivity : SystemLanguageActivity() {
    private lateinit var store: InputStore
    private lateinit var side: String
    private lateinit var marker: PhotoMarkView
    private lateinit var length: EditText
    private lateinit var mirror: CheckBox
    private lateinit var threshold: SeekBar
    private lateinit var thresholdText: TextView
    private lateinit var pointText: TextView
    private lateinit var preview: ImageView
    private lateinit var previewButton: Button
    private var bitmap: Bitmap? = null
    private var contour: Bitmap? = null
    private var busy = false
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = InputStore(this)
        side = intent.getStringExtra("side") ?: "left"
        if (side !in listOf("left", "right") || !store.photo(side).isFile) { finish(); return }
        val draft = state?.getString("draft")?.let { JSONObject(it) } ?: store.draft(side)
        val body = AppStyle.page(this, getString(if (side == "left") R.string.left_ear else R.string.right_ear))
        body.addView(AppStyle.text(this, R.string.photo_position_help, 15f))
        val card = AppStyle.card(this)
        card.addView(AppStyle.text(this, R.string.three_points, 19f, true))
        pointText = AppStyle.text(this, "")
        card.addView(pointText)
        marker = PhotoMarkView(this).apply {
            points = InputStore.points(draft)
            rightEar = side == "right"
            guideVisible = draft.optBoolean("guide",true)
            onPoint = { index, _ -> selectedPoint = (index+1).coerceAtMost(2); updatePointText() }
        }
        bitmap = InputStore.bitmap(store.photo(side)); marker.photo = bitmap
        card.addView(marker, LinearLayout.LayoutParams(-1, AppStyle.dp(this,340)))
        card.addView(AppStyle.text(this, R.string.silhouette_help, 13f))
        card.addView(CheckBox(this).apply {
            setText(R.string.show_silhouette); setTextColor(AppStyle.ink); isChecked=marker.guideVisible
            setOnCheckedChangeListener { _,checked -> marker.guideVisible=checked }
        })
        val buttons = LinearLayout(this)
        listOf(R.string.point_canal_button, R.string.point_ruler_start_button, R.string.point_ruler_end_button).forEachIndexed { index, name ->
            buttons.addView(AppStyle.button(this, name) { marker.selectedPoint = index; updatePointText() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(2,4,2,4) })
        }
        card.addView(buttons)
        length = AppStyle.numeric(this, getString(R.string.ruler_length), draft.optString("referenceMm", "20"))
        card.addView(AppStyle.text(this, R.string.ruler_distance, 14f)); card.addView(length)
        mirror = CheckBox(this).apply {
            setText(R.string.mirror_photo); setTextColor(AppStyle.ink); textSize = 16f
            isChecked = draft.optBoolean("mirror", side == "right"); minHeight = AppStyle.dp(this@PhotoActivity,48)
        }
        card.addView(mirror)
        card.addView(AppStyle.text(this, R.string.mirror_help, 13f))
        thresholdText = AppStyle.text(this, "")
        card.addView(thresholdText)
        threshold = SeekBar(this).apply {
            max = 88; progress = ((draft.optDouble("threshold", .15)-.02)*100).toInt().coerceIn(0,88)
            contentDescription = getString(R.string.contour_threshold_description)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { updateThresholdText() }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        card.addView(threshold, LinearLayout.LayoutParams(-1,AppStyle.dp(this,48)))
        updateThresholdText(); updatePointText()
        preview = ImageView(this).apply { adjustViewBounds = true; contentDescription = getString(R.string.contour_preview_description) }
        previewButton = AppStyle.button(this, R.string.check_contours) { preparePreview(false) }
        card.addView(previewButton); card.addView(preview, LinearLayout.LayoutParams(-1,AppStyle.dp(this,256)))
        card.addView(AppStyle.text(this, R.string.contour_help, 14f))
        card.addView(AppStyle.button(this, R.string.save_points) { preparePreview(true) })
        body.addView(card)
        body.addView(AppStyle.button(this, R.string.back_without_saving) { finish() })
    }
    private fun updatePointText() {
        val names = listOf(R.string.point_canal, R.string.point_ruler_start, R.string.point_ruler_end)
        pointText.text = getString(R.string.point_status,marker.selectedPoint+1,getString(names[marker.selectedPoint]),marker.points.count { it != null })
    }
    private fun updateThresholdText() { if (::threshold.isInitialized) thresholdText.text = getString(R.string.threshold_value,threshold.progress+2) }
    private fun draft() = JSONObject().put("points", InputStore.pointArray(marker.points))
        .put("guide",marker.guideVisible)
        .put("referenceMm", length.text.toString().replace(',','.'))
        .put("mirror",mirror.isChecked).put("threshold",(threshold.progress+2)/100.0)
    private fun calibration(): PhotoProcessor.Calibration {
        requireUi(marker.points.all { it != null },R.string.all_points_required)
        val p = marker.points.map { it!! }
        val mm = length.text.toString().replace(',','.').toDoubleOrNull() ?: throw LocalizedFailure(R.string.ruler_length_required)
        return PhotoProcessor.Calibration(p[0][0],p[0][1],p[1][0],p[1][1],p[2][0],p[2][1],mm,mirror.isChecked,(threshold.progress+2)/100.0)
    }
    private fun preparePreview(save: Boolean) {
        if (busy) return
        val calibration = runCatching { calibration() }.getOrElse { errorDialog(it); return }
        val snapshot = draft()
        val source = bitmap ?: return
        busy = true; previewButton.isEnabled = false; previewButton.setText(R.string.preparing_contours)
        worker.execute {
            val result = runCatching { PhotoProcessor.prepare(source, calibration) { Thread.currentThread().isInterrupted } }
            runOnUiThread {
                busy = false
                if (isDestroyed || isFinishing) { result.getOrNull()?.recycle(); return@runOnUiThread }
                previewButton.isEnabled = true; previewButton.setText(R.string.check_contours)
                result.onSuccess {
                    preview.setImageBitmap(it); contour?.recycle(); contour = it
                    if (save) { store.saveDraft(side,snapshot); setResult(RESULT_OK); finish() }
                }.onFailure(::errorDialog)
            }
        }
    }
    private fun errorDialog(error: Throwable) { AlertDialog.Builder(this).setTitle(R.string.check_photo).setMessage(UiText.error(error).render(this)).setPositiveButton(R.string.ok, null).show() }
    override fun onSaveInstanceState(state: Bundle) { if (::marker.isInitialized) state.putString("draft",draft().toString()); super.onSaveInstanceState(state) }
    override fun onDestroy() {
        worker.shutdownNow()
        // Let GC reclaim the bitmap after any worker finishes native pixel access.
        if (!busy) bitmap?.recycle()
        contour?.recycle(); super.onDestroy()
    }
}
