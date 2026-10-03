/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.spatial

import org.junit.Assert.*
import org.junit.Test

class SpatialChannelLayoutTest {
    @Test fun standardSurroundChannelsKeepTheirSpeakerIdentity() {
        assertArrayEquals(floatArrayOf(1f,2f,3f,0f,0f,0f,0f,0f),
            SpatialChannelLayout.expand(floatArrayOf(1f,2f,3f),3), 0f)
        assertArrayEquals(floatArrayOf(1f,2f,0f,0f,3f,4f,0f,0f),
            SpatialChannelLayout.expand(floatArrayOf(1f,2f,3f,4f),4), 0f)
        assertArrayEquals(floatArrayOf(1f,2f,3f,0f,4f,5f,0f,0f),
            SpatialChannelLayout.expand(floatArrayOf(1f,2f,3f,4f,5f),5), 0f)
        val input = floatArrayOf(1f,2f,3f,4f,5f,6f,7f)
        val expected = floatArrayOf(1f,2f,3f,4f,5f*0.70710677f,5f*0.70710677f,6f,7f)
        assertArrayEquals(expected + expected, SpatialChannelLayout.expand(input + input,7), 0f)
    }

    @Test fun existingLayoutsAreNotPermutedTwice() {
        for (channels in listOf(1,2,6,8)) {
            val input = FloatArray(channels*2) { it.toFloat() }
            assertSame(input, SpatialChannelLayout.expand(input,channels))
            assertEquals(channels, SpatialChannelLayout.nativeChannels(channels))
        }
    }
}
