/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.views

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.musicplayer.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class, qualifiers = "ru-rRU-w360dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerLayoutBehaviorTest {
    private fun host(): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        return controller.setup().visible().get()
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun offset(view: DelayedMarqueeTextView) = DelayedMarqueeTextView::class.java
        .getDeclaredField("offset").apply { isAccessible = true }.getFloat(view)

    @Test fun longTextWaitsThreeSecondsMovesSlowlyAndRestartsForNewTrack() {
        val activity = host()
        val text = DelayedMarqueeTextView(activity).apply {
            textSize = 18f
            text = "Очень длинное имя исполнителя и название альбома"
            setMotionActive(true)
        }
        val frame = FrameLayout(activity).apply { addView(text, FrameLayout.LayoutParams(120, 48)) }
        activity.setContentView(frame)
        layout(frame, 360, 720)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(2999))
        assertEquals(0f, offset(text), .001f)
        looper.idleFor(Duration.ofMillis(1070))
        assertTrue("18 dp/s: offset=${offset(text)}, width=${text.width}, line=${text.layout?.getLineWidth(0)}", offset(text) in 17f..21f)
        text.text = "Другое очень длинное имя исполнителя"
        text.layoutParams = FrameLayout.LayoutParams(120, 48)
        layout(frame, 360, 720)
        looper.idleFor(Duration.ofMillis(2999))
        assertEquals(0f, offset(text), .001f)
        looper.idleFor(Duration.ofMillis(100))
        assertTrue(offset(text) > 0f)
        text.setMotionActive(false)
        looper.idleFor(Duration.ofSeconds(5))
        assertEquals(0f, offset(text), .001f)
        activity.finish()
    }

    @Test fun shortTextNeverMovesAndDetachmentCancelsScrolling() {
        val activity = host()
        val text = DelayedMarqueeTextView(activity).apply {
            text = "Песня"; setMotionActive(true)
        }
        val frame = FrameLayout(activity).apply { addView(text, FrameLayout.LayoutParams(200, 48)) }
        activity.setContentView(frame)
        layout(frame, 360, 720)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(0f, offset(text), .001f)
        text.text = "Очень длинное название песни, которое точно не помещается"
        text.layoutParams = FrameLayout.LayoutParams(120, 48)
        layout(frame, 360, 720)
        activity.setContentView(FrameLayout(activity))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(0f, offset(text), .001f)
        activity.finish()
    }

    @Test fun grabbingVerticalScrollbarReachesEndAndBeginningOfActualQueue() {
        val activity = host()
        val frame = FrameLayout(activity)
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 80
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(TextView(activity).apply {
                        layoutParams = RecyclerView.LayoutParams(-1, 44)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as TextView).text = "Исполнитель — Песня $position     3:58"
                }
            }
        }
        frame.addView(list, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = 32 })
        val scrollbar = QueueScrollbar(activity, list)
        frame.addView(scrollbar, FrameLayout.LayoutParams(44, -1, Gravity.END))
        val outer = FrameLayout(activity).apply { addView(frame, FrameLayout.LayoutParams(360, 352)) }
        activity.setContentView(outer)
        layout(outer, 360, 720)
        assertEquals(352, scrollbar.height)
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(0, 0, action, 22f, y, 0)
            assertTrue(scrollbar.onTouchEvent(event))
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 12f)
        touch(MotionEvent.ACTION_MOVE, 340f)
        touch(MotionEvent.ACTION_UP, 340f)
        assertEquals(79, (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition())
        assertFalse(list.canScrollVertically(1))
        touch(MotionEvent.ACTION_DOWN, 340f)
        touch(MotionEvent.ACTION_MOVE, 12f)
        touch(MotionEvent.ACTION_UP, 12f)
        assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
        assertFalse(list.canScrollVertically(-1))
        activity.finish()
    }
}
