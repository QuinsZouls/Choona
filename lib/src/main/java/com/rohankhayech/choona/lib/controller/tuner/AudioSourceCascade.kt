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
import android.os.Build

/**
 * Policy of the audio source cascade of the tuner: which sources are tried, and in which order.
 *
 * The capture itself needs an [android.media.AudioRecord] and therefore a device, but the decision
 * of *what* to ask the microphone for is pure: it only depends on the SDK level. Keeping that
 * decision here, apart from the capture, is what makes it testable on the host JVM: returning a
 * single source would quietly bring back the automatic gain control of the manufacturer for every
 * device, and no test of the pipeline would notice.
 *
 * The values are the constants of [MediaRecorder.AudioSource], which are compile time constants:
 * the host unit tests read them without touching the Android runtime, so no copy of them is kept
 * here and none can fall out of sync.
 *
 * @author Rohan Khayech
 */
internal object AudioSourceCascade {

    /**
     * Audio sources to try, best first, for the given SDK level.
     *
     * UNPROCESSED bypasses every voice-processing stage of the device (AGC, noise suppression, EQ),
     * which is what a tuner wants: an AGC squashes a decaying electric guitar into inaudibility. It
     * is only available from API 24 up. VOICE_RECOGNITION is the next best source, since it is
     * documented to avoid the AGC. MIC is the always-available fallback.
     *
     * MIC is always the last candidate, and never missing: it is the only source every device has,
     * so a cascade that ends without it would leave the tuner unable to capture at all on a device
     * that rejects the other two.
     *
     * @param sdkInt SDK level of the device, as [Build.VERSION.SDK_INT] reports it.
     * @return The sources to try, in the order they must be attempted.
     */
    fun candidates(sdkInt: Int): List<Int> {
        val sources = mutableListOf<Int>()
        if (sdkInt >= Build.VERSION_CODES.N) {
            sources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        sources.add(MediaRecorder.AudioSource.MIC)
        return sources
    }
}
