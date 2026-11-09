/*
 * Choona - Guitar Tuner
 * Copyright (C) 2026 Rohan Khayech
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.rohankhayech.choona.lib.controller.tuner

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the source cascade of the microphone, on the host JVM: the policy depends only on the SDK
 * level, so it can be asserted without an [android.media.AudioRecord] and without a device.
 *
 * The cascade is the whole reason the tuner asks for an unprocessed source: with `MIC` alone the
 * automatic gain control and the noise suppression of the manufacturer deform the envelope of the
 * note exactly where the tuner reads it, which is what these tests keep from regressing.
 */
class AudioSourceCascadeTest {

    @Test
    fun candidatesBelowApi24SkipUnprocessed() {
        val candidates = AudioSourceCascade.candidates(API_BEFORE_UNPROCESSED)

        assertEquals(
            "A device below API $API_UNPROCESSED has no unprocessed source, so only the last two " +
                "sources of the cascade can be offered, but the cascade was $candidates",
            2,
            candidates.size,
        )
        assertFalse(
            "UNPROCESSED does not exist below API $API_UNPROCESSED, but the cascade asked for " +
                "it: $candidates",
            candidates.contains(MediaRecorder.AudioSource.UNPROCESSED),
        )
        assertEquals(
            "The cascade must fall back to MIC, the source every device has, but it was " +
                "$candidates",
            MediaRecorder.AudioSource.MIC,
            candidates.last(),
        )
        assertTrue(
            "VOICE_RECOGNITION is available on every supported device and must be tried before " +
                "MIC, but the cascade was $candidates",
            candidates.contains(MediaRecorder.AudioSource.VOICE_RECOGNITION),
        )
    }

    @Test
    fun candidatesFromApi24UpStartWithUnprocessed() {
        val candidates = AudioSourceCascade.candidates(API_UNPROCESSED)

        assertEquals(
            "From API $API_UNPROCESSED the cascade is the three sources of the product, but it " +
                "was $candidates",
            3,
            candidates.size,
        )
        assertEquals(
            "The unprocessed source must be tried first or the device applies its voice " +
                "processing, but the cascade was $candidates",
            MediaRecorder.AudioSource.UNPROCESSED,
            candidates.first(),
        )
        assertEquals(
            "The cascade must end with the always available source, but it was $candidates",
            MediaRecorder.AudioSource.MIC,
            candidates.last(),
        )
        assertEquals(
            "A source tried twice would only add a second failure to the cascade, but it was " +
                "$candidates",
            candidates.size,
            candidates.distinct().size,
        )
    }

    @Test
    fun micIsAlwaysTheLastResort() {
        for (sdkInt in LOWEST_SDK..HIGHEST_SDK) {
            val candidates = AudioSourceCascade.candidates(sdkInt)

            assertTrue(
                "The cascade of API $sdkInt must offer at least one source, but it was $candidates",
                candidates.isNotEmpty(),
            )
            assertEquals(
                "MIC is the only source every device has, so it must be the last one tried on " +
                    "API $sdkInt, but the cascade was $candidates",
                MediaRecorder.AudioSource.MIC,
                candidates.last(),
            )
            assertTrue(
                "VOICE_RECOGNITION exists on every supported device, so it must be offered on " +
                    "API $sdkInt instead of leaving MIC as the only fallback, but the cascade " +
                    "was $candidates",
                candidates.contains(MediaRecorder.AudioSource.VOICE_RECOGNITION),
            )
        }
    }

    @Test
    fun candidatesAreOrderedByPreference() {
        val expectations = listOf(
            API_BEFORE_UNPROCESSED to listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            ),
            API_UNPROCESSED to listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            ),
        )

        for ((sdkInt, expected) in expectations) {
            val candidates = AudioSourceCascade.candidates(sdkInt)

            assertEquals(
                "The cascade of API $sdkInt must be tried in the order of preference of the " +
                    "product, best source first",
                expected,
                candidates,
            )
            assertEquals(
                "A repeated source would be tried twice, but the cascade of API $sdkInt was " +
                    "$candidates",
                candidates.size,
                candidates.distinct().size,
            )
        }
    }

    private companion object {
        /** First SDK level with an unprocessed audio source. */
        const val API_UNPROCESSED = 24

        /** Last SDK level without it. */
        const val API_BEFORE_UNPROCESSED = 23

        /** Lowest SDK level the tuner can run on, which is the `minSdk` of the module. */
        const val LOWEST_SDK = 21

        /** An SDK level well above the ones that added a source, to pin the ramp. */
        const val HIGHEST_SDK = 34
    }
}
