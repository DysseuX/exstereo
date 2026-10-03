/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */

package org.dysseux.hrtf

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=HrtfApplication::class,qualifiers="ru-rRU-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUiTest {
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup) repeat(view.childCount) { yieldAll(children(view.getChildAt(it))) }
    }
    private fun root(activity: android.app.Activity): View {
        val view=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        view.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(851,View.MeasureSpec.EXACTLY))
        view.layout(0,0,393,851)
        return view
    }
    private fun snapshot(view: View,name: String) {
        val english=view.resources.configuration.locales[0].language=="en"
        val suffix=if(english) "-en" else ""
        if(english) {
            val text=children(view).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertFalse("Russian text in an English preview",Regex("[А-Яа-яЁё]").containsMatchIn(text))
        }
        val bitmap=Bitmap.createBitmap(view.width*2,view.height*2,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap).apply { scale(2f,2f) })
        File("build/reports/hrtf-ui/$name$suffix.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        bitmap.recycle()
    }
    @Test fun mainHasBothPhotoInputsAndOpensOnlyTheSelectedSide() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get(); val view=root(activity)
        val labels=children(view).filterIsInstance<TextView>().toList()
        assertTrue(labels.any { it.text==activity.getString(R.string.left_step) })
        assertTrue(labels.any { it.text==activity.getString(R.string.right_step) })
        assertFalse(labels.first { it.text==activity.getString(R.string.save_sofa) }.isEnabled)
        labels.filter { it.text==activity.getString(R.string.choose_photo) }[1].performClick()
        val intent=shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
        assertEquals("image/*",intent.type)
        snapshot(view,"main")
        controller.pause().stop().destroy()
    }
    @Test fun captureProviderRejectsArbitraryPaths() {
        val provider=Robolectric.buildContentProvider(CaptureProvider::class.java).create().get()
        for(path in listOf("../private","left/extra","models","")) {
            try { provider.openFile(Uri.parse("content://test/$path"),"r"); fail("Unsafe camera path accepted") }
            catch(expected: IllegalArgumentException) { }
        }
    }
    @Test fun draggingPhotoKeepsExistingMarksAndMapsNewMarksToOriginalPixels() {
        val marker=PhotoMarkView(RuntimeEnvironment.getApplication())
        val bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
        marker.photo=bitmap
        marker.layout(0,0,400,400)
        marker.points[0]=doubleArrayOf(100.0,120.0)
        marker.selectedPoint=1
        fun touch(down: Long,time: Long,action: Int,x: Float,y: Float) {
            val event=MotionEvent.obtain(down,time,action,x,y,0)
            marker.onTouchEvent(event); event.recycle()
        }
        touch(10,10,MotionEvent.ACTION_DOWN,150f,150f)
        touch(10,50,MotionEvent.ACTION_MOVE,200f,170f)
        touch(10,80,MotionEvent.ACTION_UP,200f,170f)
        assertNull(marker.points[1])
        touch(700,700,MotionEvent.ACTION_DOWN,250f,220f)
        touch(700,750,MotionEvent.ACTION_UP,250f,220f)
        assertArrayEquals(doubleArrayOf(100.0,120.0),marker.points[0],0.01)
        assertArrayEquals(doubleArrayOf(200.0,200.0),marker.points[1],0.01)
        bitmap.recycle()
    }
    @Test fun photoMarksAndCalibrationSurviveRecreation() {
        val app=RuntimeEnvironment.getApplication()
        val store=InputStore(app)
        val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.LTGRAY)
        store.photo("left").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val intent=Intent(app,PhotoActivity::class.java).putExtra("side","left")
        val controller=Robolectric.buildActivity(PhotoActivity::class.java,intent).setup()
        val marker=children(root(controller.get())).filterIsInstance<PhotoMarkView>().single()
        marker.points=arrayOf(doubleArrayOf(200.0,210.0),doubleArrayOf(40.0,20.0),doubleArrayOf(240.0,20.0))
        val state=Bundle(); controller.saveInstanceState(state).pause().stop().destroy()
        val restored=Robolectric.buildActivity(PhotoActivity::class.java,intent).create(state).start().resume().visible()
        val root=root(restored.get())
        val again=children(root).filterIsInstance<PhotoMarkView>().single()
        assertArrayEquals(doubleArrayOf(200.0,210.0),again.points[0],0.0)
        assertArrayEquals(doubleArrayOf(240.0,20.0),again.points[2],0.0)
        snapshot(root,"photo-calibration")
        restored.pause().stop().destroy()
        store.clear()
    }
    @Test fun photoDecoderBoundsMemoryAndStripsOrientationBeforeMarking() {
        val app=RuntimeEnvironment.getApplication()
        val photo=File(app.cacheDir,"large.jpg")
        val source=Bitmap.createBitmap(4000,2000,Bitmap.Config.ARGB_8888)
        photo.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG,80,it) }; source.recycle()
        val exif=android.media.ExifInterface(photo.absolutePath)
        exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6"); exif.saveAttributes()
        val image=ImageImporter.decode(photo)
        assertEquals(800,image.width); assertEquals(1600,image.height)
        image.recycle(); photo.delete()
    }
    @Test fun deletingInputsAlsoRemovesInterruptedImportCopies() {
        val app=RuntimeEnvironment.getApplication()
        val files=listOf(File(app.cacheDir,"incoming-photo.tmp"),File(app.filesDir,"left-new.png"),File(app.filesDir,"right-new.png"),File(app.filesDir,"capture-left.jpg"))
        files.forEach { it.writeText("private photo") }
        InputStore(app).clear()
        files.forEach { assertFalse(it.exists()) }
    }

    @Test @Config(qualifiers="en-rUS-w393dp-h851dp-port-mdpi")
    fun englishMainScreenPreview() = mainHasBothPhotoInputsAndOpensOnlyTheSelectedSide()

    @Test @Config(qualifiers="en-rUS-w393dp-h851dp-port-mdpi")
    fun englishPhotoCalibrationPreview() = photoMarksAndCalibrationSurviveRecreation()
}
