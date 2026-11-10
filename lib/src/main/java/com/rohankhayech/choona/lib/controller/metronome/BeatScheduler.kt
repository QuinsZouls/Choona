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

package com.rohankhayech.choona.lib.controller.metronome

import com.rohankhayech.choona.lib.model.metronome.MetronomeState

/**
 * Drift-free beat clock for the metronome: converts a stream of audio blocks into beats placed
 * at exact absolute sample positions.
 *
 * Audio is rendered in blocks, so a beat rarely coincides with the start of one. The scheduler
 * is advanced once per block with [advance], which reports every beat starting inside that block
 * together with its offset in the block and its position in the bar, so the caller can trigger
 * the click voice at the right sample and highlight the right beat indicator at the same time.
 *
 * ## The drift problem
 *
 * The interval between beats, `samplesPerBeat`, is in general not a whole number of samples: at
 * 44.1 kHz and 137 BPM it is 19313.8686... samples. The obvious implementation keeps a frame
 * counter for the next beat and rounds or truncates it to a whole frame every time the beat
 * fires, which throws away the fractional remainder, i.e. up to one sample per beat. That error
 * never cancels out, it accumulates: after a few thousand beats a metronome at 137 BPM is
 * several seconds off, and the tempo the player hears is not the tempo that was requested
 * (a rounded interval of 19314 samples is 136.9977 BPM, one of 19313 is 137.0048 BPM).
 *
 * This class therefore never rounds. The next beat is held in [nextBeatFrame] as an absolute
 * *fractional* frame index, and the interval is added to it as a [Double] on every beat, so the
 * remainder of a beat that did not fit on a sample boundary is carried into the next one. The
 * only rounding happens when the offset inside the block is reported to the caller, which is
 * unavoidable because samples are whole, and that rounding never feeds back into the schedule:
 * the accumulated position stays exact, so the error stays bounded by under one sample forever
 * instead of growing with the number of beats.
 *
 * ## Invariants
 *
 * * [framePosition] is the number of frames advanced so far, and every reported beat satisfies
 *   `0 <= offset < frames`, i.e. the offset is always a valid index into the block being
 *   advanced. To guarantee that, the loop tests `nextBeatFrame < framePosition + frames` with a
 *   *strict* comparison: a beat landing exactly on the end of a block does not belong to that
 *   block, it is emitted at offset 0 of the next one. A block never reports a beat it does not
 *   contain, and no beat is lost, since the following advance starts where this one ended.
 * * [nextBeatFrame] is never behind [framePosition], so the remaining time to the next beat is
 *   never negative.
 * * Every reported `beatInBar` is in `0 until beatsPerBar`, and consecutive beats advance it by
 *   one, wrapping to 0 at the start of each bar. The first beat of a bar is therefore always
 *   reported as 0, which is what lets the caller accent it.
 * * Construction leaves the scheduler in exactly the state [reset] produces: the first beat is
 *   emitted at frame 0 of the first [advance], as `beatInBar == 0`.
 *
 * ## Changing the tempo while playing
 *
 * Changing [bpm] preserves the *phase* of the current beat, not the time already elapsed in it:
 * a beat that is 25% played at 120 BPM becomes a beat that is 25% played at the new tempo, so
 * the rest of the interval is sped up or slowed down proportionally. Dragging a tempo slider
 * therefore bends the groove from the next beat on, instead of jump-starting a beat (making one
 * interval far too short) or stretching the whole interval (making one beat far too long). See
 * the setter for the formula.
 *
 * ## Changing the metre while playing
 *
 * Changing [beatsPerBar] takes effect from the next beat on: the accent marker follows the new
 * metre rather than waiting for the next bar to end, so a player who switches from 4/4 to 3/4
 * hears the downbeat where they now expect it. A bar that shrinks past the position of the next
 * beat clamps that position back into the new bar, so it is never reported outside
 * `0 until beatsPerBar`.
 *
 * ## Threading
 *
 * This class is deliberately **not** thread-safe and uses no locks, no atomics and no
 * synchronisation: it is advanced on the single thread that renders audio, once per block, and
 * allocating or blocking there would glitch the click. Tempo and metre changes must be handed
 * over by the owner of the scheduler instead, e.g. published in a volatile field that the render
 * thread copies into [bpm] and [beatsPerBar] at the start of a block, or applied on the render
 * thread itself when draining a queue of control events. [advance] allocates nothing: the beat
 * callback is invoked synchronously inside the loop and is not retained.
 *
 * @param sampleRateHz Sample rate of the audio being rendered, in frames per second. Must be
 *  positive, since the beat interval is derived from it; a non-positive rate is rejected.
 * @param bpm Initial tempo in beats per minute, clamped into
 *  [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM].
 * @param beatsPerBar Initial number of beats in a bar, clamped into
 *  [MetronomeState.MIN_BEATS_PER_BAR]..[MetronomeState.MAX_BEATS_PER_BAR].
 *
 * @author Rohan Khayech
 */
class BeatScheduler(
    private val sampleRateHz: Int,
    bpm: Int = MetronomeState.DEFAULT_BPM,
    beatsPerBar: Int = MetronomeState.DEFAULT_BEATS_PER_BAR,
) {

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
    }

    private var samplesPerBeat: Double = samplesPerBeatAt(MetronomeState.coerceBpm(bpm))

    private var advancedFrames = 0L

    private var nextBeatFrame = 0.0

    private var nextBeatInBar = 0

    /**
     * Total number of frames advanced so far, i.e. the absolute index of the next frame to be
     * rendered. It restarts from 0 on [reset] and is never rounded, so it is the exact length of
     * the audio already described by this scheduler.
     */
    val framePosition: Long
        get() = advancedFrames

    /**
     * Tempo in beats per minute, always within [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM].
     *
     * Assignment clamps the value with [MetronomeState.coerceBpm] and ignores a change that ends
     * up as the current tempo. A real change keeps the phase of the beat in progress: if
     * `remaining` is the time left before the next beat and `oldSamplesPerBeat` the interval it
     * was measured in, the beat is rescheduled at
     * `framePosition + remaining * newSamplesPerBeat / oldSamplesPerBeat`. The fraction of the
     * interval already elapsed is thus unchanged, so the remainder of the beat stretches or
     * shrinks with the new tempo instead of the beat being restarted or played out in full.
     *
     * The next beat therefore lands neither where it would have landed at the old tempo nor a
     * full new interval away, but proportionally in between, which is what makes a tempo slider
     * feel continuous while playing.
     *
     * The setter cannot throw: both the current and the new interval are positive by construction
     * —[sampleRateHz] is required positive and both tempos are clamped into
     * [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM]— so the retiming ratio above is always
     * finite, and there is nothing left to check that a caller could have got wrong.
     */
    var bpm: Int = MetronomeState.coerceBpm(bpm)
        set(value) {
            val coerced = MetronomeState.coerceBpm(value)
            if (coerced == field) {
                return
            }
            val previousSamplesPerBeat = samplesPerBeat
            val retimedSamplesPerBeat = samplesPerBeatAt(coerced)
            val remainingFrames = nextBeatFrame - advancedFrames
            samplesPerBeat = retimedSamplesPerBeat
            nextBeatFrame = advancedFrames + remainingFrames * retimedSamplesPerBeat /
                previousSamplesPerBeat
            field = coerced
        }

    /**
     * Number of beats in a bar, always within
     * [MetronomeState.MIN_BEATS_PER_BAR]..[MetronomeState.MAX_BEATS_PER_BAR].
     *
     * Assignment clamps the value with [MetronomeState.coerceBeatsPerBar] and ignores a change
     * that ends up as the current metre. A real change applies to the bar in progress: the beat
     * already scheduled keeps its place in the bar when the new bar is long enough to hold it,
     * and is clamped to the last beat of the bar otherwise, so the next reported index is always
     * a valid one. Shortening the bar to 2 while beat 3 is pending therefore makes the next beat
     * the last beat of the (new) bar, i.e. index 1.
     */
    var beatsPerBar: Int = MetronomeState.coerceBeatsPerBar(beatsPerBar)
        set(value) {
            val coerced = MetronomeState.coerceBeatsPerBar(value)
            if (coerced == field) {
                return
            }
            field = coerced
            if (nextBeatInBar >= coerced) {
                nextBeatInBar = coerced - 1
            }
        }

    /**
     * Restarts the schedule at frame 0, so the next [advance] reports its first beat.
     *
     * The frame position, the pending beat and the position in the bar all go back to their
     * starting values: the next advance emits a beat at offset 0 with `beatInBar == 0`, i.e. on
     * the downbeat, which is accented when the accent is enabled. The tempo and the metre are
     * kept, so restarting does not silently change what the player configured. A freshly
     * constructed scheduler is already in this state, so callers do not need to reset before
     * starting.
     *
     * Currently unused by the engine, which constructs a fresh scheduler per session and therefore
     * has nothing to rewind. It is kept for a future session-reuse path, and the tests exercise it.
     */
    fun reset() {
        advancedFrames = 0L
        nextBeatFrame = 0.0
        nextBeatInBar = 0
    }

    /**
     * Advances the schedule by the given number of frames, reporting the beats that start inside
     * them in order.
     *
     * For every beat whose absolute frame lies in this block, [onBeat] is invoked with its offset
     * from the start of the block (always in `0 until frames`) and its zero-based position in the
     * bar (always in `0 until beatsPerBar`). A block that covers several beats reports them all,
     * so the callback never sees a duplicate and never misses a beat however small the blocks and
     * however fast the tempo. A beat falling exactly on the end of the block is reported by the
     * next advance, at offset 0.
     *
     * The loop is bounded by `frames / samplesPerBeat`, so advancing a long stretch of audio at
     * once costs one iteration per beat in it and allocates nothing.
     *
     * @param frames Number of frames rendered in this block. Must be positive.
     * @param onBeat Callback invoked for each beat starting inside the block, with its offset in
     *  the block and its position in the bar.
     * @throws IllegalArgumentException If [frames] is not positive.
     */
    fun advance(frames: Int, onBeat: (offsetInBlock: Int, beatInBar: Int) -> Unit) {
        require(frames > 0) { "frames must be positive, was $frames" }
        val endFrame = advancedFrames + frames
        while (nextBeatFrame < endFrame) {
            val offset = (nextBeatFrame - advancedFrames).toInt()
            onBeat(offset, nextBeatInBar)
            nextBeatInBar = (nextBeatInBar + 1) % beatsPerBar
            nextBeatFrame += samplesPerBeat
        }
        advancedFrames = endFrame
    }

    private fun samplesPerBeatAt(bpm: Int): Double =
        SECONDS_PER_MINUTE * sampleRateHz / bpm

    private companion object {
        /** Seconds in a minute, i.e. the factor between frames per beat and beats per minute. */
        const val SECONDS_PER_MINUTE = 60.0
    }
}
