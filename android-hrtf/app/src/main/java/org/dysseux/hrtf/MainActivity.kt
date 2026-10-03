/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.io.File

class MainActivity : SystemLanguageActivity() {
    private lateinit var store: InputStore
    private lateinit var widthInput: EditText
    private lateinit var depthInput: EditText
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var cancel: Button
    private lateinit var export: Button
    private lateinit var report: Button
    private lateinit var progress: ProgressBar
    private val mutableControls=mutableListOf<View>()
    private val earLabels=mutableMapOf<String,TextView>()
    private val handler=Handler(Looper.getMainLooper())
    private var pendingSide="left"
    private var exportSource: File?=null
    private val refresh=object : Runnable {
        override fun run() { update(); handler.postDelayed(this,400) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store=InputStore(this)
        pendingSide=state?.getString("pendingSide") ?: "left"
        exportSource=state?.getString("exportSource")?.let { File(it) }
        val body=AppStyle.page(this,getString(R.string.screen_title))
        body.addView(AppStyle.text(this,R.string.main_heading,20f,true))
        body.addView(AppStyle.text(this,R.string.main_intro,15f))
        body.addView(AppStyle.text(this,R.string.experimental_notice,14f).apply { setTextColor(AppStyle.secondary) })
        for ((side,title) in listOf("left" to R.string.left_step,"right" to R.string.right_step)) {
            val card=AppStyle.card(this)
            card.addView(AppStyle.text(this,title,20f,true))
            val label=AppStyle.text(this,""); earLabels[side]=label; card.addView(label)
            val actions=LinearLayout(this)
            actions.addView(AppStyle.button(this,R.string.choose_photo) { choose(side) }.also { mutableControls+=it }, LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=8 })
            actions.addView(AppStyle.button(this,R.string.camera) { camera(side) }.also { mutableControls+=it }, LinearLayout.LayoutParams(0,-2,1f))
            card.addView(actions)
            card.addView(AppStyle.button(this,R.string.mark_photo) { edit(side) }.also { mutableControls+=it })
            body.addView(card)
        }
        val dimensions=AppStyle.card(this)
        dimensions.addView(AppStyle.text(this,R.string.dimensions_step,20f,true))
        dimensions.addView(AppStyle.text(this,R.string.dimensions_help,14f))
        widthInput=dimension(dimensions,getString(R.string.head_width),"width")
        depthInput=dimension(dimensions,getString(R.string.head_depth),"depth")
        body.addView(dimensions)
        val result=AppStyle.card(this)
        result.addView(AppStyle.text(this,R.string.profile_step,20f,true))
        status=AppStyle.text(this,ProfileJob.message.render(this)); status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        result.addView(status)
        progress=ProgressBar(this).apply { isIndeterminate=true; visibility=View.GONE }
        result.addView(progress,LinearLayout.LayoutParams(-1,AppStyle.dp(this,32)))
        start=AppStyle.button(this,R.string.create_hrtf) { buildProfile() }; result.addView(start)
        cancel=AppStyle.button(this,R.string.cancel_calculation) { ProfileJob.cancel() }; result.addView(cancel)
        export=AppStyle.button(this,R.string.save_sofa) { exportFile(false) }; result.addView(export)
        report=AppStyle.button(this,R.string.save_report) { exportFile(true) }; result.addView(report)
        result.addView(AppStyle.text(this,R.string.rebuild_help,13f))
        result.addView(AppStyle.text(this,R.string.player_import_help,14f))
        body.addView(result)
        body.addView(AppStyle.button(this,R.string.photo_guide) {
            dialog(R.string.photo_guide_title,getString(R.string.photo_guide_body))
        })
        body.addView(AppStyle.button(this,R.string.about_licenses) { about() })
        body.addView(AppStyle.button(this,R.string.delete_data) {
            AlertDialog.Builder(this).setTitle(R.string.delete_title)
                .setMessage(R.string.delete_body)
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete) { _,_ ->
                    if(!ProfileJob.running) { ProfileJob.clear(this); widthInput.setText(""); depthInput.setText(""); update() }
                }.show()
        }.also { mutableControls+=it })
        body.addView(AppStyle.text(this,getString(R.string.author_version,AppStyle.version(this)),13f).apply { setTextColor(AppStyle.secondary) })
        if(!ProfileJob.running) {
            store.cleanupIncompleteImports()
            File(filesDir,"profiles").listFiles()?.filter { it.name.startsWith(".building-") }?.forEach { it.deleteRecursively() }
        }
        update()
    }
    private fun dimension(parent: LinearLayout, title: String, key: String): EditText {
        parent.addView(AppStyle.text(this,title,14f))
        return AppStyle.numeric(this,title,store.dimension(key)).also { input ->
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int)=Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { store.dimension(key,s.toString()) }
                override fun afterTextChanged(s: Editable?)=Unit
            })
            mutableControls+=input; parent.addView(input)
        }
    }
    private fun update() {
        if(!::status.isInitialized) return
        val busy=ProfileJob.running
        if(busy) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text=ProfileJob.message.render(this)
        mutableControls.forEach { it.isEnabled=!busy; it.alpha=if(busy) .55f else 1f }
        start.isEnabled=!busy
        progress.visibility=if(busy) View.VISIBLE else View.GONE
        cancel.visibility=if(busy && ProfileJob.cancellable) View.VISIBLE else View.GONE
        val available=ProfileJob.latest(this)!=null
        export.isEnabled=available && !busy; report.isEnabled=available && !busy
        for ((side,label) in earLabels) {
            label.setText(if(store.ear(side)!=null) R.string.photo_ready else if(store.photo(side).isFile) R.string.photo_needs_points else R.string.photo_missing)
        }
        ProfileJob.photoToEdit?.let { side ->
            if(!busy) { ProfileJob.photoToEdit=null; edit(side) }
        }
    }
    private fun choose(side: String) {
        pendingSide=side
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="image/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },10) } catch(error: Exception) { dialog(R.string.choose_title, getString(R.string.no_file_picker)) }
    }
    private fun camera(side: String) {
        pendingSide=side
        File(filesDir,"capture-$side.jpg").delete()
        val uri=Uri.parse("content://$packageName.capture/$side")
        val intent=Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT,uri)
            clipData=ClipData.newRawUri(getString(R.string.ear_photo),uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(intent,11) }
        catch(error: Exception) { dialog(R.string.camera_unavailable,getString(R.string.camera_help)) }
    }
    private fun edit(side: String) {
        if(!store.photo(side).isFile) { dialog(R.string.photo_required_title,getString(R.string.photo_required)); return }
        startActivity(Intent(this,PhotoActivity::class.java).putExtra("side",side))
    }
    private fun buildProfile() {
        try {
            val left=store.ear("left") ?: throw LocalizedFailure(R.string.left_required)
            val right=store.ear("right") ?: throw LocalizedFailure(R.string.right_required)
            val width=widthInput.text.toString().replace(',','.').toDoubleOrNull() ?: throw LocalizedFailure(R.string.width_required)
            val depth=depthInput.text.toString().replace(',','.').toDoubleOrNull() ?: throw LocalizedFailure(R.string.depth_required)
            ProfileJob.build(this,left,right,width,depth); update()
        } catch(error: Exception) { dialog(R.string.check_inputs,UiText.error(error).render(this)) }
    }
    private fun exportFile(isReport: Boolean) {
        val sofa=ProfileJob.latest(this) ?: return
        exportSource=if(isReport) File(sofa.parentFile,"report.json") else sofa
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type=if(isReport) "application/json" else "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE,exportSource!!.name)
        },12) } catch(error: Exception) { dialog(R.string.save_title,getString(R.string.no_save_picker)) }
    }
    @Deprecated("Platform document and camera contracts")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data)
        if(request==11 && result!=RESULT_OK) File(filesDir,"capture-$pendingSide.jpg").delete()
        if(result!=RESULT_OK) return
        when(request) {
            10 -> data?.data?.let { ProfileJob.importPhoto(this,it,pendingSide) }
            11 -> ProfileJob.importPhoto(this,Uri.parse("content://$packageName.capture/$pendingSide"),pendingSide)
            12 -> data?.data?.let { target -> exportSource?.takeIf { it.isFile }?.let { ProfileJob.export(this,target,it) } }
        }
        update()
    }
    private fun about() {
        val text=getString(R.string.about_body,getString(R.string.screen_title),AppStyle.version(this))
        AlertDialog.Builder(this).setTitle(R.string.about_title).setMessage(text)
            .setPositiveButton(R.string.ok,null).setNeutralButton(R.string.licenses) { _,_ ->
                val names=assets.list("licenses")?.filter { it.endsWith(".txt") }?.sorted().orEmpty()
                AlertDialog.Builder(this).setTitle(R.string.component_licenses).setItems(names.toTypedArray()) { _,index ->
                    val contents=assets.open("licenses/${names[index]}").bufferedReader().use { it.readText() }
                    dialog(names[index],contents)
                }.setNegativeButton(R.string.back,null).show()
            }.show()
    }
    private fun dialog(title: Int, message: String) = dialog(getString(title),message)
    private fun dialog(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.ok,null).show() }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("pendingSide",pendingSide); exportSource?.let { state.putString("exportSource",it.absolutePath) }
        super.onSaveInstanceState(state)
    }
}
