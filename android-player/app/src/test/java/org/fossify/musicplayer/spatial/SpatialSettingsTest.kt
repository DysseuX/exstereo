/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33], application = org.fossify.musicplayer.LocalizedTestApplication::class)
class SpatialSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun reload() {
        SpatialPreferences::class.java.getDeclaredField("preferences").apply { isAccessible = true }.set(null, null)
        SpatialPreferences.initialize(context)
    }

    @Before fun clearSettings() {
        context.getSharedPreferences("spatial_audio", Context.MODE_PRIVATE).edit().clear().commit()
        reload()
    }

    @Test fun stereoAndSurroundLayoutsAndCalibrationSurviveReloadIndependently() {
        val surround = SpatialSettings.defaultSpeakers().toMutableList()
        surround[5] = SpeakerPosition(-115f, 22f, 3.2f)
        val stereo = mutableListOf(SpeakerPosition(-120f, 35f, 2.4f), SpeakerPosition(65f, -20f, 0.6f))
        val saved = SpatialSettings(mode = 1, speakers = surround, stereoSpeakers = stereo,
            virtualStereo = false, hrtfBlend = 0.65f, listenerYaw = 31f, listenerPitch = -14f,
            hrtfBalanceDb = 2f, hrtfGainDb = -4f, wideningAlgorithm = 5, wideningAmount = .7f,
            widenerLow = .2f, widenerHigh = .8f, widenerCrossover = 1800f,
            transientProtection = false, widenerAmplitudePreserving = true,
            bandLowWidth = .75f, bandMidWidth = 1.1f, bandHighWidth = 1.8f,
            bandLowCrossover = 350f, bandHighCrossover = 5000f, correlationGuard = false,
            paeAmbientWidth = 2.1f, paeAmbienceGainDb = -3.5f, sideSynthesisAmount = .3f, sideHighpass = 400f,
            shuffleAmount = -.4f, shuffleFrequency = 1000f, microCents = 8f, microDelayMs = 15f,
            roomEnabled = true, roomMix = .22f, roomSize = 7f, roomDamping = .7f, roomDecay = .8f)
        SpatialPreferences.update(saved)
        // Caller-owned list mutations must not cross to the audio thread.
        stereo[0] = SpeakerPosition(0f)
        surround[5] = SpeakerPosition(0f)
        assertEquals(-120f, SpatialPreferences.current.stereoSpeakers[0].azimuth)
        assertEquals(-115f, SpatialPreferences.current.speakers[5].azimuth)
        val snapshot = SpatialPreferences.current
        reload()
        assertEquals(snapshot, SpatialPreferences.current)
        SpatialPreferences.update(snapshot.copy(mode = 2))
        assertEquals(snapshot.stereoSpeakers, SpatialPreferences.current.stereoSpeakers)
        assertEquals(snapshot.speakers, SpatialPreferences.current.speakers)
    }

    @Test fun mirrorMovesEveryPairAndPersistsWithoutTouchingOtherLayouts() {
        for (mode in 0..2) {
            val base = SpatialSettings(mode = mode, mirrorSpeakers = true)
            val pairs = if (mode == 2) listOf(0 to 1, 1 to 0, 3 to 4, 4 to 3, 5 to 6, 6 to 5) else listOf(0 to 1, 1 to 0)
            for ((source, partner) in pairs) {
                val moved = base.moveSpeaker(source, SpeakerPosition(-110f, 25f, 2.5f))
                val positions = if (mode == 2) moved.speakers else moved.stereoSpeakers
                assertEquals(SpeakerPosition(-110f, 25f, 2.5f), positions[source])
                assertEquals(SpeakerPosition(110f, 25f, 2.5f), positions[partner])
                if (mode == 2) assertEquals(base.stereoSpeakers, moved.stereoSpeakers)
                else assertEquals(base.speakers, moved.speakers)
                SpatialPreferences.update(moved)
                reload()
                assertEquals(moved.normalized(), SpatialPreferences.current)
            }
        }
        val surround = SpatialSettings(mode = 2, mirrorSpeakers = true)
        val centered = surround.moveSpeaker(2, SpeakerPosition(12f, 15f, 2f))
        surround.speakers.indices.filter { it != 2 }.forEach { assertEquals(surround.speakers[it], centered.speakers[it]) }
        val independent = SpatialSettings(mode = 0).moveSpeaker(0, SpeakerPosition(-100f))
        assertEquals(SpatialSettings.defaultStereoSpeakers()[1], independent.stereoSpeakers[1])
    }

    @Test fun previousVersionPreferencesMigrateWithoutLosingSurroundLayout() {
        context.getSharedPreferences("spatial_audio", Context.MODE_PRIVATE).edit()
            .putInt("mode", 0).putFloat("azimuth_4", 140f).putFloat("elevation_4", 12f).commit()
        reload()
        val loaded = SpatialPreferences.current
        assertEquals(SpeakerPosition(140f, 12f, 1f), loaded.speakers[4])
        assertEquals(SpatialSettings.defaultStereoSpeakers(), loaded.stereoSpeakers)
        assertTrue(loaded.virtualStereo)
        assertEquals(1f, loaded.hrtfBlend)
        assertEquals(0, loaded.wideningAlgorithm)
        assertTrue(loaded.transientProtection)
        assertArrayEquals(SpatialSettings().nativeParameters().copyOfRange(44, 63),
            loaded.nativeParameters().copyOfRange(44, 63), 0f)
    }

    @Test fun malformedCoordinatesNeverReachNativeAudio() {
        val malformed = SpatialSettings(speakers = emptyList(),
            stereoSpeakers = listOf(SpeakerPosition(Float.NaN, Float.POSITIVE_INFINITY, -1f)),
            listenerYaw = Float.NaN, hrtfGainDb = 900f, hrtfBlend = Float.NEGATIVE_INFINITY,
            wideningAlgorithm = 99, wideningAmount = Float.NaN, widenerCrossover = Float.POSITIVE_INFINITY,
            widenerLow = -30f, widenerHigh = 30f)
        SpatialPreferences.update(malformed)
        val safe = SpatialPreferences.current
        assertEquals(7, safe.speakers.size)
        assertEquals(2, safe.stereoSpeakers.size)
        assertEquals(SpeakerPosition(0f, 0f, 0.5f), safe.stereoSpeakers[0])
        assertEquals(6f, safe.hrtfGainDb)
        assertEquals(0f, safe.listenerYaw)
        assertEquals(10, safe.wideningAlgorithm)
        assertEquals(.5f, safe.wideningAmount)
        assertEquals(1000f, safe.widenerCrossover)
        assertEquals(0f, safe.widenerLow)
        assertEquals(1f, safe.widenerHigh)
        assertTrue(safe.nativeParameters().all { it.isFinite() })
        reload()
        assertEquals(safe, SpatialPreferences.current)
    }

    @Test fun nativeV3PreservesPrevious44ValuesAndAppendsAllNewControlsInOrder() {
        val settings = SpatialSettings(bandLowWidth = .75f, bandMidWidth = 1.1f, bandHighWidth = 1.8f,
            bandLowCrossover = 350f, bandHighCrossover = 5000f, correlationGuard = false,
            paeAmbientWidth = 2.1f, paeAmbienceGainDb = -3.5f, sideSynthesisAmount = .3f, sideHighpass = 400f,
            shuffleAmount = -.4f, shuffleFrequency = 1000f, microCents = 8f, microDelayMs = 15f,
            roomEnabled = true, roomMix = .22f, roomSize = 7f, roomDamping = .7f, roomDecay = .8f)
        val values = settings.nativeParameters()
        assertEquals(63, values.size)
        val expectedLegacy = floatArrayOf(2f, 1.25f, .5011872f, 1f, 1f, 1f, 0f, 0f, 0f, 0f,
            -30f, 0f, 1f, 30f, 0f, 1f, 0f, 0f, 1f, -150f, 0f, 1f,
            150f, 0f, 1f, -90f, 0f, 1f, 90f, 0f, 1f, -30f, 0f, 1f, 30f, 0f, 1f,
            0f, .5f, 0f, .5f, 1000f, 1f, 0f)
        assertArrayEquals(expectedLegacy, values.copyOfRange(0, 44), .000001f)
        assertArrayEquals(floatArrayOf(.75f, 1.1f, 1.8f, 350f, 5000f, 0f, 2.1f, -3.5f,
            .3f, 400f, -.4f, 1000f, 8f, 15f, 1f, .22f, 7f, .7f, .8f), values.copyOfRange(44, 63), 0f)
    }

    @Test fun everyNewFloatRejectsNonFiniteValuesAndClampsToItsAudioRange() {
        val broken = SpatialSettings(bandLowWidth = Float.NaN, bandMidWidth = Float.POSITIVE_INFINITY,
            bandHighWidth = Float.NEGATIVE_INFINITY, bandLowCrossover = Float.NaN,
            bandHighCrossover = Float.POSITIVE_INFINITY, paeAmbientWidth = Float.NaN,
            paeAmbienceGainDb = Float.NEGATIVE_INFINITY, sideSynthesisAmount = Float.NaN,
            sideHighpass = Float.POSITIVE_INFINITY, shuffleAmount = Float.NaN,
            shuffleFrequency = Float.NEGATIVE_INFINITY, microCents = Float.NaN, microDelayMs = Float.POSITIVE_INFINITY,
            roomMix = Float.NaN, roomSize = Float.POSITIVE_INFINITY, roomDamping = Float.NEGATIVE_INFINITY,
            roomDecay = Float.NaN)
        SpatialPreferences.update(broken)
        assertEquals(SpatialSettings(), SpatialPreferences.current)
        reload()
        assertEquals(SpatialSettings(), SpatialPreferences.current)
        val extremes = SpatialSettings(bandLowWidth = -9f, bandMidWidth = 9f, bandHighWidth = 9f,
            bandLowCrossover = -1f, bandHighCrossover = 90000f, paeAmbientWidth = 99f,
            paeAmbienceGainDb = -99f, sideSynthesisAmount = 99f, sideHighpass = -99f,
            shuffleAmount = -99f, shuffleFrequency = 99999f, microCents = 99f, microDelayMs = -1f,
            roomMix = 99f, roomSize = 99f, roomDamping = -1f, roomDecay = 99f).normalized()
        assertArrayEquals(floatArrayOf(0f, 2f, 2f, 80f, 8000f, 1f, 3f, -12f, 1f, 20f, -1f,
            2000f, 20f, 5f, 0f, 1f, 12f, 0f, 1.5f), extremes.nativeParameters().copyOfRange(44, 63), 0f)
    }
}
