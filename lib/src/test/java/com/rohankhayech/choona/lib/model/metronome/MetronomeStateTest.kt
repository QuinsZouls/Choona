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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MetronomeState] and [BeatPulse].
 *
 * @author Rohan Khayech
 */
class MetronomeStateTest {

    /**
     * Verifies the defaults a fresh metronome starts from: a walking-pace 120 BPM in common
     * time, with the first beat accented and the transport stopped.
     */
    @Test
    fun defaults() {
        val state = MetronomeState()
        assertEquals(120, state.bpm)
        assertEquals(4, state.beatsPerBar)
        assertTrue(state.accentFirstBeat)
        assertFalse(state.playing)

        assertEquals(MetronomeState.DEFAULT_BPM, state.bpm)
        assertEquals(MetronomeState.DEFAULT_BEATS_PER_BAR, state.beatsPerBar)
    }

    /**
     * Verifies that [MetronomeState.coerceBpm] clamps on both ends and keeps every tempo
     * already inside the range unchanged.
     */
    @Test
    fun coerceBpm() {
        assertEquals(30, MetronomeState.coerceBpm(29))
        assertEquals(30, MetronomeState.coerceBpm(0))
        assertEquals(30, MetronomeState.coerceBpm(-5))
        assertEquals(30, MetronomeState.coerceBpm(30))
        assertEquals(300, MetronomeState.coerceBpm(300))
        assertEquals(300, MetronomeState.coerceBpm(301))
        assertEquals(300, MetronomeState.coerceBpm(1000))
    }

    /**
     * Verifies that [MetronomeState.coerceBeatsPerBar] clamps on both ends and keeps every
     * metre already inside the range unchanged.
     */
    @Test
    fun coerceBeatsPerBar() {
        assertEquals(1, MetronomeState.coerceBeatsPerBar(0))
        assertEquals(1, MetronomeState.coerceBeatsPerBar(1))
        assertEquals(12, MetronomeState.coerceBeatsPerBar(12))
        assertEquals(12, MetronomeState.coerceBeatsPerBar(13))
        assertEquals(1, MetronomeState.coerceBeatsPerBar(-3))
    }

    /**
     * Verifies that [MetronomeState.coerce] clamps the tempo and the metre in one pass, and
     * that it leaves the accent and the transport flag alone: coercing a restored state must
     * never start or stop playback.
     */
    @Test
    fun coerceClampsBothFieldsAndPreservesFlags() {
        val coerced = MetronomeState(bpm = 1000, beatsPerBar = 0, accentFirstBeat = false, playing = true).coerce()
        assertEquals(300, coerced.bpm)
        assertEquals(1, coerced.beatsPerBar)
        assertFalse(coerced.accentFirstBeat)
        assertTrue(coerced.playing)

        val tooSlow = MetronomeState(bpm = 10, beatsPerBar = 20, accentFirstBeat = true, playing = false).coerce()
        assertEquals(30, tooSlow.bpm)
        assertEquals(12, tooSlow.beatsPerBar)
        assertTrue(tooSlow.accentFirstBeat)
        assertFalse(tooSlow.playing)
    }

    /**
     * Verifies that [MetronomeState.coerce] is idempotent on an already valid state, so it can
     * be applied unconditionally at every entry point of the model.
     */
    @Test
    fun coerceIsIdempotent() {
        val state = MetronomeState(bpm = 90, beatsPerBar = 7, accentFirstBeat = true, playing = true)
        val once = state.coerce()
        assertEquals(state, once)
        assertEquals(once, once.coerce())

        val boundary = MetronomeState(bpm = MetronomeState.MAX_BPM, beatsPerBar = MetronomeState.MAX_BEATS_PER_BAR)
        assertEquals(boundary, boundary.coerce())
    }

    /**
     * Pins the values of the supported ranges, not just their relationships: the audio renderer,
     * the mixer and the click are all built around them —a 200 ms shortest beat interval against a
     * 40 ms click, and at most twelve dots on the beat indicator— so a change here has to be a
     * deliberate one, and the tests that rely on the numbers should fail loudly rather than quietly
     * follow the constant.
     */
    @Test
    fun supportedRangesArePinned() {
        assertEquals(30, MetronomeState.MIN_BPM)
        assertEquals(300, MetronomeState.MAX_BPM)
        assertEquals(200, MetronomeState.MIN_BEAT_INTERVAL_MS)
        assertEquals(1, MetronomeState.MIN_BEATS_PER_BAR)
        assertEquals(12, MetronomeState.MAX_BEATS_PER_BAR)

        assertEquals(MetronomeState.MIN_BPM, MetronomeState.coerceBpm(MetronomeState.MIN_BPM - 1))
        assertEquals(MetronomeState.MAX_BPM, MetronomeState.coerceBpm(MetronomeState.MAX_BPM + 1))
        assertEquals(
            MetronomeState.MIN_BEATS_PER_BAR,
            MetronomeState.coerceBeatsPerBar(MetronomeState.MIN_BEATS_PER_BAR - 1),
        )
        assertEquals(
            MetronomeState.MAX_BEATS_PER_BAR,
            MetronomeState.coerceBeatsPerBar(MetronomeState.MAX_BEATS_PER_BAR + 1),
        )
    }

    /**
     * Verifies that [MetronomeState.coerceBpm] clamps the extremes of the [Int] range too, which is
     * what a tempo restored from a corrupt or foreign preference can be, instead of overflowing on
     * the way into the range.
     */
    @Test
    fun coerceBpmClampsTheExtremes() {
        assertEquals(MetronomeState.MIN_BPM, MetronomeState.coerceBpm(Int.MIN_VALUE))
        assertEquals(MetronomeState.MAX_BPM, MetronomeState.coerceBpm(Int.MAX_VALUE))
        assertEquals(MetronomeState.MIN_BEATS_PER_BAR, MetronomeState.coerceBeatsPerBar(Int.MIN_VALUE))
        assertEquals(MetronomeState.MAX_BEATS_PER_BAR, MetronomeState.coerceBeatsPerBar(Int.MAX_VALUE))
    }

    /**
     * Verifies that two pulses of the same beat in the bar but of different beats are not
     * equal: this is the property that makes an effect keyed on the pulse re-fire on every
     * beat, which keying on the repeating [BeatPulse.beatInBar] would not.
     */
    @Test
    fun pulsesOfSameBeatInBarAreDistinct() {
        val firstBeatOfFirstBar = BeatPulse(count = 0, beatInBar = 0)
        val firstBeatOfSecondBar = BeatPulse(count = 4, beatInBar = 0)

        assertNotEquals(firstBeatOfFirstBar, firstBeatOfSecondBar)
        assertNotEquals(firstBeatOfFirstBar.hashCode(), firstBeatOfSecondBar.hashCode())

        val bar = (0 until 4).map { BeatPulse(count = it.toLong(), beatInBar = it) } +
            (0 until 4).map { BeatPulse(count = (it + 4).toLong(), beatInBar = it) }
        assertEquals(bar.size, bar.toSet().size)
    }

    /**
     * Verifies that [BeatPulse.copy] keeps the beat in the bar while the counter advances, so
     * a caller can emit the next beat of the same position with a fresh identity.
     */
    @Test
    fun pulseCopyKeepsBeatInBarAndAdvancesCount() {
        val pulse = BeatPulse(count = 6, beatInBar = 2)
        val next = pulse.copy(count = 7)

        assertEquals(6L, pulse.count)
        assertEquals(2, next.beatInBar)
        assertEquals(7L, next.count)
        assertNotEquals(pulse, next)
        assertEquals(pulse, BeatPulse(count = 6, beatInBar = 2))
    }
}
