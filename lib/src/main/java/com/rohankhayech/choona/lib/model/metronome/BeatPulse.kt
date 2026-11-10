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

package com.rohankhayech.choona.lib.model.metronome

/**
 * A single beat emitted by the metronome while it is playing.
 *
 * This is a high-frequency signal, separate from the rarely changing [MetronomeState], so a
 * beat only touches what has to react to it.
 *
 * Two pulses are distinct whenever either field differs, which is what makes this type usable
 * as a trigger: an effect keyed on the pulse identity re-fires on every beat, and only on a
 * beat. Keying it on [beatInBar] alone would not, since that value repeats.
 *
 * @property count Number of beats emitted since the current playback session started, i.e. a
 *  monotonically increasing counter that is never reset while playing (it only restarts when
 *  a new session starts). This is the field animation and effect keys are built on: since it
 *  never repeats, an effect keyed on it re-fires on every beat, whereas a four-beat bar only
 *  yields [beatInBar] 0, 1, 2, 3, 0, 1, 2, 3, so keying on that would stop re-firing after
 *  the third beat. It is a [Long] so it cannot saturate however long the session runs.
 * @property beatInBar Position of the beat within its bar, zero-based, i.e. in
 *  `0 until beatsPerBar`. It is produced by the audio scheduler together with the click it
 *  describes, so the accented click and the highlighted beat indicator can never disagree
 *  about which beat is sounding.
 *
 * @author Rohan Khayech
 */
data class BeatPulse(val count: Long, val beatInBar: Int)
