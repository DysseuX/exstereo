/*
 * Modifications Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.playback.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor.DEFAULT_PADDING_SILENCE_US
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.fossify.musicplayer.spatial.SpatialAudioProcessor
import org.fossify.musicplayer.spatial.SpatialInputResampler

private const val SKIP_SILENCE_MINIMUM_DURATION_US = 300000L
private const val SKIP_SILENCE_THRESHOLD_LEVEL = 16.toShort()

@UnstableApi
open class AudioOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(
            SKIP_SILENCE_MINIMUM_DURATION_US,
            DEFAULT_PADDING_SILENCE_US,
            SKIP_SILENCE_THRESHOLD_LEVEL
        )

        return DefaultAudioSink.Builder(context)
            // Media3's float-output path skips custom processors.
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(SpatialInputResampler(), SpatialAudioProcessor(context)),
                    silenceSkippingAudioProcessor,
                    SonicAudioProcessor()
                )
            )
            .build()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) = Unit

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) = Unit

    override fun buildMetadataRenderers(
        context: Context,
        output: MetadataOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) = Unit

    override fun buildCameraMotionRenderers(context: Context, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit

    override fun buildMiscellaneousRenderers(context: Context, eventHandler: Handler, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit
}
