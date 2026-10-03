/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only */

package org.fossify.musicplayer.spatial

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sin

/** Regression for Media3 ignoring an equal-position seek while paused. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HrtfPlaybackRefreshTest {
    @Test fun pausedHrtfChangeRecreatesNativeEngineAndKeepsQueueAndPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun onMain(block: () -> Unit) {
            val error = AtomicReference<Throwable>()
            instrumentation.runOnMainSync {
                try { block() } catch (failure: Throwable) { error.set(failure) }
            }
            // Keep assertion failures in the instrumentation thread rather
            // than Android's uncaught main-thread startup-report handler.
            error.get()?.let { throw it }
        }
        val context = instrumentation.targetContext
        SpatialPreferences.initialize(context)
        val original = SpatialPreferences.current
        HrtfProfiles.path(context, "kemar")
        val fixture = File.createTempFile("hrtf-refresh-", ".wav", context.cacheDir)
        val pcmSize = 48000 * 2 * 2 * 2
        val wave = ByteBuffer.allocate(44 + pcmSize).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + pcmSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(pcmSize)
        repeat(pcmSize / 2) { wave.putShort((sin(it * 0.05) * 2000).toInt().toShort()) }
        fixture.writeBytes(wave.array())

        val processor = SpatialAudioProcessor(context)
        val handleField = SpatialAudioProcessor::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val loaded = ConcurrentLinkedQueue<Pair<String, Long>>()
        val firstLoaded = CountDownLatch(1)
        val secondLoaded = CountDownLatch(1)
        val observed = object : AudioProcessor by processor {
            override fun flush() {
                processor.flush()
                val handle = handleField.getLong(processor)
                if (handle != 0L) {
                    val profile = SpatialPreferences.current.hrtf
                    loaded.add(profile to handle)
                    if (profile == "default") firstLoaded.countDown()
                    if (profile == "kemar") secondLoaded.countDown()
                }
            }
        }
        val firstReady = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val refreshing = AtomicBoolean(false)
        val failure = AtomicReference<PlaybackException>()
        var player: ExoPlayer? = null
        var pausedPosition = 0L
        try {
            SpatialPreferences.update(original.copy(hrtf = "default"))
            onMain {
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                            .setAudioProcessors(arrayOf(observed)).build()
                }
                player = ExoPlayer.Builder(context, renderers).build().apply {
                    volume = 0f
                    playWhenReady = false
                    repeatMode = Player.REPEAT_MODE_ALL
                    shuffleModeEnabled = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                if (refreshing.get()) secondReady.countDown() else firstReady.countDown()
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            failure.set(error); firstReady.countDown(); secondReady.countDown()
                        }
                    })
                    setMediaItems(listOf("first", "second").map { id ->
                        MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(fixture)).build()
                    }, 0, 400)
                    prepare()
                }
            }
            assertTrue("Initial preparation timed out", firstReady.await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("Initial HRTF was not loaded", firstLoaded.await(10, TimeUnit.SECONDS))
            val initialHandle = loaded.last { it.first == "default" }.second
            onMain {
                player!!.run {
                    pausedPosition = currentPosition
                    SpatialPreferences.update(SpatialPreferences.current.copy(hrtf = "kemar"))
                    refreshing.set(true)
                    assertTrue(refreshSpatialHrtf())
                }
            }
            assertTrue("HRTF reload timed out", secondReady.await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("New HRTF was not loaded", secondLoaded.await(10, TimeUnit.SECONDS))
            assertTrue("The new HRTF was not loaded by the audio processor", loaded.any { it.first == "kemar" && it.second != initialHandle })
            onMain {
                player!!.run {
                    assertFalse(playWhenReady)
                    assertEquals(2, mediaItemCount)
                    assertEquals("first", currentMediaItem!!.mediaId)
                    assertEquals("second", getMediaItemAt(1).mediaId)
                    assertEquals(Player.REPEAT_MODE_ALL, repeatMode)
                    assertTrue(shuffleModeEnabled)
                    assertTrue("Paused playback position changed", abs(currentPosition - pausedPosition) <= 2)
                }
            }
        } finally {
            try { onMain { player?.release() } } finally {
                SpatialPreferences.update(original)
                fixture.delete()
            }
        }
    }
}
