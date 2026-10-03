/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the meter cannot retain energy across silence, flush or timestamp wrap. */
class PlaybackEnergyTest {
    @Test fun silenceAndClearRemoveOldEnergy() {
        try {
            val now = 12_000_000_000L
            PlaybackEnergy.publish(25.0, 0.5f, 100, now)
            assertTrue(PlaybackEnergy.level(now) > 0f)
            PlaybackEnergy.publish(0.0, 0f, 100, now + 1_000_000L)
            assertEquals(0f, PlaybackEnergy.level(now + 1_000_000L), 0f)
            PlaybackEnergy.publish(25.0, 0.5f, 100, now)
            PlaybackEnergy.clear()
            assertEquals(0f, PlaybackEnergy.level(now), 0f)
        } finally { PlaybackEnergy.clear() }
    }

    @Test fun oldAndInvalidMeasurementsCannotLeaveStuckGlow() {
        try {
            // Cross the packed timestamp's 32-bit millisecond wrap.
            val now = (0xffffffffL - 100L) * 1_000_000L
            PlaybackEnergy.publish(1.0, 0.1f, 100, now)
            assertTrue(PlaybackEnergy.level(now + 200_000_000L) in 0.01f..1f)
            assertEquals(0f, PlaybackEnergy.level(now + 300_000_000L), 0f)
            PlaybackEnergy.publish(Double.NaN, Float.POSITIVE_INFINITY, 100, now)
            assertEquals(0f, PlaybackEnergy.level(now), 0f)
        } finally { PlaybackEnergy.clear() }
    }
}
