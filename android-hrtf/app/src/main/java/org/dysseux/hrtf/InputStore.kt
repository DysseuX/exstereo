/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class EarInput(val side: String, val file: File, val calibration: PhotoProcessor.Calibration)

class InputStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("inputs", Context.MODE_PRIVATE)
    fun photo(side: String): File {
        require(side == "left" || side == "right")
        return File(context.filesDir, "$side.png")
    }
    fun draft(side: String): JSONObject = runCatching { JSONObject(prefs.getString(side, "{}")!!) }.getOrDefault(JSONObject())
    fun saveDraft(side: String, value: JSONObject) {
        prefs.edit().putString(side, value.toString()).commit()
    }
    fun clearCalibration(side: String) { prefs.edit().remove(side).commit() }
    fun ear(side: String): EarInput? = runCatching {
        if (!photo(side).isFile) return null
        val value = draft(side)
        val points = value.getJSONArray("points")
        require(points.length() == 3)
        fun p(index: Int, axis: Int) = points.getJSONArray(index).getDouble(axis)
        EarInput(side, photo(side), PhotoProcessor.Calibration(p(0,0),p(0,1),p(1,0),p(1,1),p(2,0),p(2,1),
            value.getDouble("referenceMm"), value.optBoolean("mirror", side == "right"), value.optDouble("threshold", .15)))
    }.getOrNull()
    fun dimension(name: String) = prefs.getString(name, "") ?: ""
    fun dimension(name: String, value: String) { prefs.edit().putString(name, value).apply() }
    fun clear() {
        for (side in listOf("left", "right")) { photo(side).delete(); File(context.filesDir, "capture-$side.jpg").delete() }
        prefs.edit().clear().commit()
        cleanupIncompleteImports()
    }
    fun cleanupIncompleteImports() {
        File(context.cacheDir,"incoming-photo.tmp").delete()
        for(side in listOf("left","right")) File(context.filesDir,"$side-new.png").delete()
    }
    companion object {
        fun bitmap(file: File): Bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw LocalizedFailure(R.string.error_photo_read)
        fun pointArray(points: Array<DoubleArray?>) = JSONArray().apply {
            points.forEach { point -> put(point?.let { JSONArray().put(it[0]).put(it[1]) } ?: JSONObject.NULL) }
        }
        fun points(value: JSONObject) = Array<DoubleArray?>(3) { index ->
            value.optJSONArray("points")?.optJSONArray(index)?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
        }
    }
}
