/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

class PhotoMarkView(context: Context) : View(context) {
    var photo: Bitmap? = null
        set(value) { field = value; zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    var points = arrayOfNulls<DoubleArray>(3)
    var selectedPoint = 0
    var guideVisible = true
        set(value) { field=value; invalidate() }
    var rightEar = false
        set(value) { field=value; invalidate() }
    var onPoint: (Int, DoubleArray) -> Unit = { _, _ -> }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val colors = intArrayOf(Color.rgb(255,194,57), Color.rgb(55,224,189), Color.rgb(106,193,255))
    private fun base() = photo?.let { min(width.toFloat()/it.width, height.toFloat()/it.height) } ?: 1f
    private fun left() = (width - (photo?.width ?: 0) * base() * zoom)/2f + panX
    private fun top() = (height - (photo?.height ?: 0) * base() * zoom)/2f + panY
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(.4f, 6f)
            val ratio = zoom / old
            panX = (panX + width/2f - detector.focusX) * ratio - width/2f + detector.focusX
            panY = (panY + height/2f - detector.focusY) * ratio - height/2f + detector.focusY
            invalidate(); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaler.isInProgress) { panX -= dx; panY -= dy; invalidate() }
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val image = photo ?: return false
            val x = (e.x-left())/(base()*zoom); val y = (e.y-top())/(base()*zoom)
            if (x >= 0 && y >= 0 && x < image.width && y < image.height) {
                val point = doubleArrayOf(x.toDouble(), y.toDouble())
                points[selectedPoint] = point
                onPoint(selectedPoint, point); performClick(); invalidate()
            }
            return true
        }
    })
    init { contentDescription = context.getString(R.string.photo_mark_description) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(29,42,50))
        val bitmap = photo ?: return
        val scale = base()*zoom
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, RectF(left(),top(),left()+bitmap.width*scale,top()+bitmap.height*scale),paint)
        if(guideVisible) drawGuide(canvas)
        points.forEachIndexed { index, point ->
            if (point != null) {
                val x = left()+point[0].toFloat()*scale; val y = top()+point[1].toFloat()*scale
                paint.color = Color.BLACK; canvas.drawCircle(x,y,AppStyle.dp(context,9).toFloat(),paint)
                paint.color = colors[index]; canvas.drawCircle(x,y,AppStyle.dp(context,7).toFloat(),paint)
                paint.color = Color.BLACK; paint.textSize = AppStyle.dp(context,12).toFloat(); paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("${index+1}",x,y+AppStyle.dp(context,4),paint)
            }
        }
    }
    private fun drawGuide(canvas: Canvas) {
        // Canvas guide; excluded from model input.
        val h=height*.82f; val w=h*.56f
        val save=canvas.save()
        canvas.translate(width/2f,height/2f)
        if(rightEar) canvas.scale(-1f,1f)
        canvas.translate(-w/2f,-h/2f)
        canvas.scale(w,h)
        val outline=Path().apply {
            moveTo(.25f,.65f)
            cubicTo(.08f,.50f,.10f,.19f,.26f,.08f)
            cubicTo(.44f,-.04f,.88f,.00f,.92f,.24f)
            cubicTo(1.03f,.48f,.77f,.68f,.65f,.86f)
            cubicTo(.53f,1.07f,.21f,.99f,.25f,.76f)
        }
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f/w
        paint.color=Color.argb(195,255,255,255)
        paint.pathEffect=DashPathEffect(floatArrayOf(9f/w,6f/w),0f)
        canvas.drawPath(outline,paint)
        paint.pathEffect=null
        val inside=Path().apply {
            moveTo(.36f,.56f)
            cubicTo(.64f,.58f,.77f,.39f,.72f,.21f)
            cubicTo(.68f,.10f,.40f,.09f,.33f,.22f)
            moveTo(.36f,.43f)
            cubicTo(.57f,.35f,.68f,.48f,.47f,.64f)
        }
        paint.color=Color.argb(130,255,255,255); canvas.drawPath(inside,paint)
        paint.style=Paint.Style.FILL
        canvas.restoreToCount(save)
        paint.color=Color.WHITE; paint.typeface=Typeface.DEFAULT; paint.textSize=AppStyle.dp(context,12).toFloat(); paint.textAlign=Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.silhouette_caption),AppStyle.dp(context,10).toFloat(),height-AppStyle.dp(context,12).toFloat(),paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
        scaler.onTouchEvent(event); gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
