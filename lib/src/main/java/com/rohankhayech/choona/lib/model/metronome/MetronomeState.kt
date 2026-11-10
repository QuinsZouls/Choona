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
 * User-facing configuration of the metronome, plus whether it is currently running.
 *
 * This type holds *only* what the user can change (tempo, beats per bar, first-beat accent)
 * and the playing flag. It deliberately never carries a beat counter: the beat pulse is a
 * separate high-frequency signal ([BeatPulse]) published on its own, so that a signal firing
 * up to [MAX_BPM] times a minute cannot recompose the whole screen on every beat. Anything
 * that changes rarely enough to be worth a recomposition belongs here; anything that changes
 * per beat belongs to [BeatPulse].
 *
 * Instances coming from a control, a stored preference or a restored state may hold values
 * outside the supported ranges, so [coerce] is the single place where they are brought back
 * into range instead of duplicating the bounds at every call site.
 *
 * @property bpm Tempo in beats per minute, normally within [MIN_BPM]..[MAX_BPM].
 * @property beatsPerBar Number of beats in a bar, normally within
 *  [MIN_BEATS_PER_BAR]..[MAX_BEATS_PER_BAR].
 * @property accentFirstBeat Whether the first beat of the bar is accented, i.e. played louder
 *  and highlighted: the accent tells the player where the bar starts without counting.
 * @property playing Whether the metronome is currently generating beats. Kept in the same
 *  snapshot as the configuration so the transport control and the tempo controls never
 *  disagree about the running session.
 *
 * @author Rohan Khayech
 */
data class MetronomeState(
    val bpm: Int = DEFAULT_BPM,
    val beatsPerBar: Int = DEFAULT_BEATS_PER_BAR,
    val accentFirstBeat: Boolean = true,
    val playing: Boolean = false,
) {

    /**
     * Returns a copy of this state with [bpm] and [beatsPerBar] clamped into their supported
     * ranges, leaving [accentFirstBeat] and [playing] untouched.
     *
     * Calling it on a state that is already in range returns an equal value, so callers can
     * apply it unconditionally when accepting external input.
     *
     * @return This state with both numeric fields coerced by [coerceBpm] and
     *  [coerceBeatsPerBar].
     */
    fun coerce(): MetronomeState =
        copy(bpm = coerceBpm(bpm), beatsPerBar = coerceBeatsPerBar(beatsPerBar))

    companion object {
        /**
         * Slowest supported tempo in beats per minute.
         *
         * The [MIN_BPM]..[MAX_BPM] range covers every practical practice tempo, from a slow
         * exercise through a metronome mark for a fast passage, so no piece needs a tempo
         * outside it. The upper bound is what matters for the audio renderer: [MAX_BPM] keeps
         * a beat interval of at least [MIN_BEAT_INTERVAL_MS], long enough for a click voice to
         * finish before the next beat starts, which lets the renderer keep at most one click
         * voice alive at a time instead of mixing overlapping voices.
         */
        const val MIN_BPM = 30

        /**
         * Fastest supported tempo in beats per minute.
         *
         * Bounded by the audio renderer rather than by musical taste: see [MIN_BPM] for the
         * minimum beat interval this guarantees.
         */
        const val MAX_BPM = 300

        /** Tempo in beats per minute used when no tempo has been chosen yet. */
        const val DEFAULT_BPM = 120

        /** Fewest beats a bar can have, i.e. one beat per bar. */
        const val MIN_BEATS_PER_BAR = 1

        /**
         * Most beats a bar can have.
         *
         * Twelve covers the compound and additive metres a player is likely to practise
         * (12/8 being the widest common one) without turning the beat indicator into a row of
         * dots too small to read.
         */
        const val MAX_BEATS_PER_BAR = 12

        /** Beats per bar used when no metre has been chosen yet, i.e. common time. */
        const val DEFAULT_BEATS_PER_BAR = 4

        /**
         * Shortest beat interval in milliseconds that [MAX_BPM] can produce, i.e. the time the
         * audio renderer may rely on having between two consecutive clicks.
         */
        const val MIN_BEAT_INTERVAL_MS = 60_000 / MAX_BPM

        /**
         * Clamps the given tempo into [MIN_BPM]..[MAX_BPM].
         * @param bpm Tempo in beats per minute, possibly out of range.
         * @return [bpm] limited to the supported range.
         */
        fun coerceBpm(bpm: Int): Int = bpm.coerceIn(MIN_BPM, MAX_BPM)

        /**
         * Clamps the given number of beats into [MIN_BEATS_PER_BAR]..[MAX_BEATS_PER_BAR].
         * @param beats Beats per bar, possibly out of range.
         * @return [beats] limited to the supported range.
         */
        fun coerceBeatsPerBar(beats: Int): Int = beats.coerceIn(MIN_BEATS_PER_BAR, MAX_BEATS_PER_BAR)
    }
}
