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

package com.rohankhayech.choona.lib.model.tempo

/**
 * Snapshot of a tap-tempo measurement.
 *
 * @property bpm Detected tempo in beats per minute, before applying [multiplier].
 *  Null until at least two taps have been registered in the current sequence.
 * @property intervalMs Trimmed mean interval between the registered taps in milliseconds,
 *  i.e. the mean of the intervals left once the ones atypically far from the median are
 *  discarded. This is the interval used to detect the tempo.
 *  Null until at least two taps have been registered in the current sequence.
 * @property tapCount Number of taps registered in the current tap sequence.
 * @property steady Whether the taps were regular enough for the detected tempo to be considered steady.
 * @property multiplier Factor applied to [bpm] to produce [displayBpm].
 * @author Rohan Khayech
 */
data class TapTempoState(
    val bpm: Double? = null,
    val intervalMs: Double? = null,
    val tapCount: Int = 0,
    val steady: Boolean = false,
    val multiplier: Double = DEFAULT_MULTIPLIER,
) {
    /** Tempo to display, i.e. [bpm] scaled by [multiplier]. Null if no tempo has been detected. */
    val displayBpm: Double? get() = bpm?.times(multiplier)

    companion object {
        /** Multiplier used when no manual tempo adjustment has been applied. */
        const val DEFAULT_MULTIPLIER = 1.0
    }
}
