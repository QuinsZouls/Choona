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

import kotlin.math.abs
import com.rohankhayech.choona.lib.model.tuning.Notes

/**
 * Turns the raw result of the pitch detector into a stable offset from A4 in semitones, or `null`
 * when the frame carries no reliable note.
 *
 * The detector answers frame by frame, and its answer is noisy: it invents notes on silence, it
 * reports the octave above the string that is actually being played and its offset jumps by a whole
 * semitone when the tuning peg moves by a fraction of a cent. This class sits between the detector
 * and the rest of the tuner and applies the decisions that turn that stream into something a needle
 * can follow: a gate on the level of the frame, the rejection of the notes outside the detectable
 * range, the folding of the octave error, a median over a short window, and a reset whenever the
 * played note changes.
 *
 * The class is deliberately pure Kotlin, with no Android and no Tarsos dependency, so that it can
 * be exercised on the host JVM. It only reads [Notes] and uses `kotlin.math`.
 *
 * ### Frame contract
 * [process] takes the three values that a detection frame carries:
 *
 * - `pitchHz`: the detected frequency. A value that is not finite, or that is not positive, means
 * that no tone was detected at all.
 * - `inputRms`: the level of the frame, in the same scale as the received samples. [Float.NaN]
 * means **unknown** and is not gated.
 * - `candidates`: the indices of the notes of the current tuning, as defined by [Notes] (semitones
 * from A4), or `null` in chromatic mode. `null`, or an empty array, disables the octave folding.
 *
 * The returned value is the offset in semitones from A4, in the same space as
 * [Notes.getOffsetFromA4], so `0.0` is an A4 and `-5.0` an E4.
 *
 * ### Gate
 * Frames that cannot hold a note are discarded before any filtering, and discard resets the whole
 * state: a silence must not leave the window of the previous note behind. The gate closes on a pitch
 * that is not finite or not positive, on a level below the threshold and on a folded offset outside
 * the detectable range, but a frame that does not know its own level is let through, because a
 * detector that does not report it is the caller's choice, not a defect of the frame.
 *
 * The confidence of a frame is **not** gated here, and must not be: the detector has already applied
 * that filter. `FastYin` only sets `isPitched` when the normalised difference of the best lag stays
 * under its own absolute threshold, which is the [TunerPipeline.YIN_THRESHOLD] the pipeline builds
 * it with, and publishes its clarity as `1 - difference`: a frame that reaches this class therefore
 * always carries a clarity above `1 - 0.15 = 0.85`, and a frame below it is never reported as a
 * pitch at all. A second gate above that floor only throws away frames the detector accepted, and
 * one at or below it can never fire.
 *
 * The measurement that settles it, on the signal this class exists to save — the low E at 10 dB SNR,
 * the weak string of the low-signal case. In this chain the detector reports **29 of 29** frames
 * with [TunerPipeline.YIN_THRESHOLD] at 0.15. With the threshold at 0.20 it still reports them all,
 * but their clarity drops from `0.85..0.96` to `0.80..0.89`, under the 0.90 of the gate this class
 * used to carry, which then keeps **0 of 29**: the gate never filtered bad frames, it filtered the
 * note, and the threshold it opened was what pushed the note under it.
 * `PipelineIntegrationTest.testWeakFundamentalAtTenDbSnrIsDetected` pins both arms of that
 * measurement.
 *
 * ### Octave folding
 * Reporting an octave too high is the most common error of the whole chain: the fundamental of a
 * plucked string is strong, but the second harmonic is close behind, and a detector that locks onto
 * it reports a note an octave high, which is exactly the pitch of another string of the tuning.
 * A frame is therefore considered to belong to the octave below when it is farther than
 * [foldTolerance] from every string but within [foldTolerance] of a string an octave down, and the
 * folding is applied from that frame on.
 *
 * Only **one** octave is ever folded: A4/2 = A3 is not a string, but A4/4 = A2 is one in standard
 * tuning, so folding by four would turn a correctly detected A4 into an "in tune" A2.
 * `offset(f/2)` is exactly `offset(f) - 12`, since a factor of two is one octave.
 *
 * ### Range
 * The detector has no frequency range of its own: the lag it searches reaches 2047 samples, which
 * is 21 Hz at 44.1 kHz, so it can report anything from a rumble to a harmonic far above the string
 * that was plucked. The gate of [lowestNote] and [highestNote] discards the frames whose offset
 * falls outside the range the tuner can represent, which is what stops a B5 or a B6 from being
 * reported as a note tens of semitones away from the string being played.
 *
 * The gate reads the **folded** offset, so an E5 is still accepted, as the E4 string it belongs to,
 * and it allows [RANGE_TOLERANCE] of a semitone on each side of the range. That margin is what lets
 * the user tune the two ends of the instrument: a D1 that enters the range from below, and a B4 that
 * sits high, since without it a B4 five cents sharp would be outside the range and the highest
 * string of the tuning could never be brought down. The other side of that coin is the limitation
 * the margin buys: a string more than [RANGE_TOLERANCE] of a semitone away from the end of the range
 * —a D1 a whole semitone flat, a B4 more than a semitone sharp— is not reported until it enters the
 * band, so the user has to trust the display for the first semitone of the correction.
 *
 * The fold and the gate disagree on the first frames of a note above the ceiling of the range. An E5
 * reads as an offset of +7, twelve semitones above the E4 string it belongs to and therefore out of
 * range, until [foldConfirmFrames] frames have confirmed that it belongs to the octave below, where
 * it is already inside. Discarding those frames would reset the counters that are about to bring the
 * note into the range, so the fold would never be confirmed and the note would never be shown: a
 * frame that is out of range while the fold is being confirmed is **held** instead —nothing is
 * published and no state is dropped— and the note appears [foldConfirmFrames] frames later, roughly
 * 46 ms per frame on the window of the pipeline.
 *
 * A note far above the ceiling, such as a B5 or a B6, is rejected for good: the fold is only wanted
 * when the note an octave below is within [foldTolerance] of a string, and the octave below those is
 * not in the tuning either, so no amount of frames brings them into range.
 *
 * ### Median
 * The folded offset is reduced to the median of the last [medianWindow] frames, which removes the
 * single frames that drift without delaying the response: the first frame after a reset is already
 * emitted, so there is no warm-up. An even window returns the mean of the two central values.
 *
 * ### Note changes
 * A change of more than [resetJumpThreshold] semitones between two consecutive frames is treated as
 * a change of note, not as a drift, and clears the window: without it the median would answer with
 * the average of two different strings for a few frames. The same happens when the octave folding
 * changes state, since the offsets on both sides of that change live an octave apart.
 *
 * The instance is stateful and not thread safe: [process] is meant to be called from the single
 * thread that delivers the audio frames, with [reset] called from the same thread when the tuner is
 * restarted.
 *
 * @param medianWindow Number of frames the reported offset is the median of. A value below 1 is
 * treated as 1, which emits every frame as it is.
 * @param minRms Level below which a frame is discarded. A negative value disables the check, since
 * no measurable level falls below it: pass `-1f`, never [Float.NaN], because a `NaN` threshold
 * would compare false against every level and quietly disable the gate as well.
 * @param foldTolerance Distance in semitones within which a frame is considered to belong to a
 * string, both in its own octave and in the octave below.
 * @param foldConfirmFrames Number of consecutive frames that must ask for the folding before it is
 * applied. A value below 1 is treated as 1.
 * @param foldReleaseFrames Number of consecutive frames that must reject the folding before it is
 * dropped. A value below 1 is treated as 1.
 * @param foldEnabled Whether the octave folding is applied at all. Disabling it makes `candidates`
 * irrelevant.
 * @param resetJumpThreshold Change in semitones between two consecutive frames above which the
 * median window is discarded.
 * @param lowestNote Lowest note of the detectable range, as an index of [Notes]. Defaults to
 * [Int.MIN_VALUE], which leaves the range gate disabled.
 * @param highestNote Highest note of the detectable range, as an index of [Notes]. Defaults to
 * [Int.MAX_VALUE], which leaves the range gate disabled.
 *
 * @author Rohan Khayech
 */
internal class PitchTracker(
    private val medianWindow: Int = MEDIAN_WINDOW,
    private val minRms: Float = MIN_RMS,
    private val foldTolerance: Double = FOLD_TOLERANCE,
    private val foldConfirmFrames: Int = FOLD_CONFIRM_FRAMES,
    private val foldReleaseFrames: Int = FOLD_RELEASE_FRAMES,
    private val foldEnabled: Boolean = true,
    private val resetJumpThreshold: Double = RESET_JUMP_THRESHOLD,
    private val lowestNote: Int = Int.MIN_VALUE,
    private val highestNote: Int = Int.MAX_VALUE,
) {

    /** Number of frames kept in [history], never below 1. */
    private val window = medianWindow.coerceAtLeast(1)

    /** Frames needed to apply the folding, never below 1. */
    private val confirmFrames = foldConfirmFrames.coerceAtLeast(1)

    /** Frames needed to drop the folding, never below 1. */
    private val releaseLimit = foldReleaseFrames.coerceAtLeast(1)

    /**
     * Ring buffer of the offsets applied to the most recent frames, oldest at [historyStart].
     * A primitive array is used instead of a deque so that the audio thread neither boxes a
     * [Double] per frame nor allocates when the window is full.
     */
    private val history = DoubleArray(window)

    /** Scratch copy of [history] sorted in place to take the median without allocating. */
    private val sorted = DoubleArray(window)

    /** Index within [history] of the oldest stored offset. */
    private var historyStart = 0

    /** Number of offsets stored in [history], at most [window]. */
    private var historySize = 0

    /** Whether the offsets are currently being folded one octave down. */
    private var foldActive = false

    /**
     * Whether the current frame asked for the folding one octave down.
     *
     * A frame that is out of range while this flag is set is a note above the ceiling of the range
     * whose folded value is already inside it: the frame is held instead of discarded, so that the
     * counters that are about to bring it into the range survive.
     */
    private var foldPending = false

    /** Consecutive frames that asked for the folding while it is not applied. */
    private var coherentFrames = 0

    /** Consecutive frames that rejected the folding while it is applied. */
    private var releaseFrames = 0

    /** Offset applied to the previous frame, [Double.NaN] when no frame has been processed yet. */
    private var lastApplied = Double.NaN

    /**
     * Processes a single detection frame and returns the offset from A4 in semitones, or `null`
     * when the frame holds no reliable note.
     *
     * @param pitchHz Detected frequency in Hz. Not finite or not positive means no tone.
     * @param inputRms Level of the frame, or [Float.NaN] when unknown.
     * @param candidates Indices of the notes of the current tuning, or `null` in chromatic mode.
     * @return The offset from A4 in semitones, or `null` if the frame was discarded.
     */
    fun process(
        pitchHz: Float,
        inputRms: Float,
        candidates: IntArray?,
    ): Double? {
        if (!pitchHz.isFinite() || pitchHz <= 0f) return discard()
        if (!inputRms.isNaN() && inputRms < minRms) return discard()

        val raw = Notes.getOffsetFromA4(pitchHz.toDouble())
        val wasFolded = foldActive
        val applied = if (foldEnabled && candidates != null && candidates.isNotEmpty()) {
            foldPending = isFoldWanted(raw, candidates)
            updateFold(foldPending)
            if (foldActive) {
                // Only one octave is folded: A4/2 = A3 is not a string, but A4/4 = A2 is one in
                // standard tuning, so folding by four would turn a correctly detected A4 into an
                // "in tune" A2.
                raw - SEMITONES_PER_OCTAVE
            } else {
                raw
            }
        } else {
            clearFold()
            raw
        }

        if (applied < lowestNote - RANGE_TOLERANCE || applied > highestNote + RANGE_TOLERANCE) {
            // A note above the ceiling of the range is only out of range while its octave error is
            // being confirmed: the folded value is already inside the range. Discarding the frame
            // would reset the counters that are about to bring it back in, so the frame is held
            // instead: nothing is published and no state is reset.
            if (!foldActive && foldPending) return null
            return discard()
        }

        if (foldActive != wasFolded) clearHistory()
        if (!lastApplied.isNaN() && abs(applied - lastApplied) > resetJumpThreshold) clearHistory()

        push(applied)
        lastApplied = applied
        return median()
    }

    /**
     * Forgets every decision taken so far, so that the next frame starts a new note.
     *
     * The call is idempotent and clears the median window, the octave folding and the offset of the
     * previous frame.
     */
    fun reset() {
        clearHistory()
        clearFold()
        lastApplied = Double.NaN
    }

    /** Discards the frame, which leaves the tracker ready for a new note. */
    private fun discard(): Double? {
        reset()
        return null
    }

    /** Whether the given offset belongs to the octave below the one the detector reported. */
    private fun isFoldWanted(raw: Double, candidates: IntArray): Boolean {
        val nearest = distanceToNearest(raw, candidates)
        val nearestAnOctaveDown = distanceToNearest(raw - SEMITONES_PER_OCTAVE, candidates)
        return nearest > foldTolerance && nearestAnOctaveDown <= foldTolerance
    }

    /** Distance in semitones between the given offset and the closest candidate note. */
    private fun distanceToNearest(offset: Double, candidates: IntArray): Double {
        var nearest = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            val distance = abs(offset - candidate)
            if (distance < nearest) {
                nearest = distance
            }
        }
        return nearest
    }

    /** Advances the confirm and release counters with the decision of this frame. */
    private fun updateFold(wanted: Boolean) {
        if (!foldActive) {
            coherentFrames = if (wanted) coherentFrames + 1 else 0
            if (coherentFrames >= confirmFrames) {
                foldActive = true
                coherentFrames = 0
                releaseFrames = 0
            }
        } else if (wanted) {
            releaseFrames = 0
        } else {
            releaseFrames++
            if (releaseFrames >= releaseLimit) {
                clearFold()
            }
        }
    }

    /** Drops the octave folding, the counters that lead to it and the fold pending of the frame. */
    private fun clearFold() {
        foldActive = false
        foldPending = false
        coherentFrames = 0
        releaseFrames = 0
    }

    /** Appends the offset to the window, dropping the oldest one when the window is full. */
    private fun push(offset: Double) {
        if (historySize < window) {
            history[(historyStart + historySize) % window] = offset
            historySize++
        } else {
            history[historyStart] = offset
            historyStart = (historyStart + 1) % window
        }
    }

    /** Drops every offset of the window. */
    private fun clearHistory() {
        historyStart = 0
        historySize = 0
    }

    /** Median of the window. An even window returns the mean of the two central offsets. */
    private fun median(): Double {
        for (i in 0 until historySize) {
            sorted[i] = history[(historyStart + i) % window]
        }
        sorted.sort(0, historySize)

        val middle = historySize / 2
        return if (historySize % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    companion object {

        /** Number of frames the reported offset is the median of. */
        const val MEDIAN_WINDOW = 5

        /** Level below which a frame is discarded: -60 dBFS, below which notes are invented. */
        const val MIN_RMS = 1e-3f

        /**
         * Difference in semitones allowed outside the detectable range on each side.
         *
         * The range is given in whole notes, so a string tuned a few cents sharp sits outside of it
         * by a fraction of a semitone: without this margin the highest string of a tuning would be
         * rejected as soon as it went above its nominal pitch, which is exactly the state the user
         * is trying to correct.
         */
        const val RANGE_TOLERANCE = 1.0

        /** Distance in semitones within which a frame is considered to belong to a string. */
        const val FOLD_TOLERANCE = 0.5

        /** Frames that must ask for the octave folding before it is applied. */
        const val FOLD_CONFIRM_FRAMES = 2

        /** Frames that must reject the octave folding before it is dropped. */
        const val FOLD_RELEASE_FRAMES = 2

        /** Change in semitones above which two consecutive frames are taken as a change of note. */
        const val RESET_JUMP_THRESHOLD = 2.5

        /** Semitones in an octave, the offset between a note and the same note one octave down. */
        private const val SEMITONES_PER_OCTAVE = 12.0

        /**
         * Returns a tracker that reports every frame as it is, with no gate, no median and no
         * folding.
         *
         * This reproduces the historical semantics of the tuner, frame after frame, and exists for
         * the callers and tests that pin down the pure calculation of the offset, not for the audio
         * path of the application.
         */
        fun passthrough(): PitchTracker = PitchTracker(
            medianWindow = 1,
            minRms = -1f,
            foldEnabled = false,
        )
    }
}
