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
 * The detector answers frame by frame, and its answer is noisy: it reports the octave above the
 * string that is actually being played and its offset jumps by a whole semitone when the tuning peg
 * moves by a fraction of a cent. This class sits between the detector and the rest of the tuner and
 * applies the decisions that turn that stream into something a needle can follow: a gate on the
 * level of the frame, the rejection of the notes outside the detectable range, the folding of the
 * octave error, a median over a short window, and a reset whenever the played note changes.
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
 * that is not finite or not positive, on a level under [MIN_RMS] and on an offset outside the
 * detectable range, but a frame that does not know its own level is let through, because a detector
 * that does not report it is the caller's choice, not a defect of the frame.
 *
 * The level gate is the absolute floor of [MIN_RMS] and it is deliberately **not** adapted to the
 * room the tuner is listening to. The three measurements that rule an adaptive floor out:
 *
 * - **It has nothing to filter.** White noise carries no period, so the detector reports no pitch on
 *   it: measured on this chain, **0 of 29** frames hold a note on the noise of a room at -60 dBFS
 *   and **0 of 29** on the noise of a room at -40 dBFS, a room 20 dB louder. A floor
 *   above the noise only repeats a filter the detector has already applied.
 *   `PipelineIntegrationTest.testSilenceProducesNoPitch` pins the three levels.
 * - **A room that is not noise is a note.** A fan, a television or the harmonic of the mains carries
 *   a period, so the detector does not fail on it and reports it: measured, **29 of 29** frames of a
 *   tonal room are pitched, and no threshold on the level can tell such a frame apart from a string
 *   that is being played.
 *   `PipelineIntegrationTest.testTonalRoomIsReportedAsANote` pins it.
 * - **A floor learnt from the frames without a note ends up over the note.** The detector drops
 *   frames of a note that is being played, and those frames carry the level of the note, not the
 *   level of the room: on a weak E2 with half of its frames dropped, a floor that learns from them
 *   converges onto the level of the note itself, the gate sits 6 dB above it and the note stops being
 *   published for good after **69 frames** (~3.2 s), with no way back while the string is held.
 *   `PipelineIntegrationTest.testWeakNoteIsPublishedWhileTheDetectorDropsEveryOtherFrame` pins that
 *   failure as the note that must survive it.
 *
 * The confidence of a frame is **not** gated here, and must not be: the detector has already applied
 * that filter. `FastYin` only sets `isPitched` when the normalised difference of the best lag stays
 * under its own absolute threshold, which is the [TunerPipeline.YIN_THRESHOLD] the pipeline builds
 * it with, and publishes its clarity as `1 - difference`: a frame that reaches this class therefore
 * always carries a clarity above `1 - 0.15 = 0.85`, and a frame below it is never reported as a
 * pitch at all. A second gate above that floor only throws away frames the detector accepted, and
 * one at or below it can never fire. Measured on the low E at 10 dB SNR with the threshold at 0.20,
 * the detector still reports its 29 frames but their clarity drops under the 0.90 of the gate this
 * class used to carry, which then keeps **0 of 29**.
 * `PipelineIntegrationTest.testWeakFundamentalAtTenDbSnrIsDetected` pins both arms of that
 * measurement.
 *
 * ### Octave folding
 * Reporting an octave too high is the most common error of the whole chain: the fundamental of a
 * plucked string is strong, but the second harmonic is close behind, and a detector that locks onto
 * it reports a note an octave high, which is exactly the pitch of another string of the tuning.
 * A frame is therefore considered to belong to the octave below when it is farther than
 * [foldTolerance] from every string but within [foldTolerance] of a string an octave down, and the
 * folding is applied to that frame.
 *
 * **A frame that asks for the folding is published already folded**: it is not held and its raw
 * offset is never published. The raw offset belongs to the note an octave above the string that is
 * being played, which is often a string of the tuning itself: measured, a B3 whose odd harmonics sit
 * 12 dB under its even ones is reported by the detector as the 494 to 495.6 Hz of the B4 on all 29
 * frames, and the raw offset of those frames, +2.05 semitones from A4, is 7.05 semitones away from
 * the E4 string —the closest string of standard tuning— while the folded one lands on the B3 the
 * string is tuned to. `PipelineIntegrationTest.testOctaveErrorOfB3IsNeverPublishedAsAnotherString`
 * pins that failure and `PipelineIntegrationTest.testForcedOctaveErrorNeverSelectsAnotherString`
 * sweeps every string of the tuning with an octave error forced on it.
 *
 * What keeps the needle still when the detector flickers between the string and its octave is that
 * the folded value is published on the frame that asks for it: measured on twenty frames of a B3
 * whose detector alternates between the note and its double, the tuner reports **one** value on all
 * twenty. The raw offset, published as it came, moved the needle twelve semitones every other frame
 * and with it the string the tuner selects.
 *
 * The confirmation of [foldConfirmFrames] frames and the release of [foldReleaseFrames] frames are
 * therefore a hysteresis that governs only the frames that do **not** ask for the folding: they
 * decide whether the folding is still held there, and cost [foldReleaseFrames] frames to let it go.
 * The withholding this replaces did not hold the note: measured on a B3 with an octave error in which
 * the detector drops every other frame, the raw offset was published on all fifteen frames of the
 * note, as the E4 string 7.05 semitones away —the wrong string, which is the defect this rule
 * removes— and held frames would have published none of them, as they did for a note above the
 * ceiling of the range, which arrived ≈46 ms late instead.
 *
 * Only **one** octave is ever folded: A4/2 = A3 is not a string, but A4/4 = A2 is one in standard
 * tuning, so folding by four would turn a correctly detected A4 into an "in tune" A2.
 * `offset(f/2)` is exactly `offset(f) - 12`, since a factor of two is one octave.
 *
 * **Known limitation:** the folding reads the octave and not the string, so a string far enough from
 * its note for its octave below to be a string of the tuning is published as that string. Measured on
 * standard tuning, a G3 three semitones flat reads as the E2 and a B3 three semitones sharp as the
 * D3. It is the price of the octave rule, which no frame can tell apart from the error it repairs.
 *
 * ### Range
 * The detector has no frequency range of its own: the lag it searches reaches 2047 samples, which
 * is 21 Hz at 44.1 kHz, so it can report anything from a rumble to a harmonic far above the string
 * that was plucked. The gate of [lowestNote] and [highestNote] discards the frames whose offset
 * falls outside the range the tuner can represent, which is what stops a B5 or a B6 from being
 * reported as a note tens of semitones away from the string being played. It keeps discarding on its
 * own: the folding does not replace it.
 *
 * The gate reads the **folded** offset, which is what lets a note above the ceiling be published on
 * the frame that asks for the folding: the raw offset of an E5 is +7 semitones from A4, twelve
 * semitones above the E4 string it belongs to and out of range, while the folded one is the string
 * itself. It allows [RANGE_TOLERANCE] of a semitone on each side of the range, and that margin is
 * what lets the user tune the two ends of the instrument: a D1 that enters the range from below, and
 * a B4 that sits high, since without it a B4 five cents sharp would be outside the range and the
 * highest string of the tuning could never be brought down. The other side of that coin is the
 * limitation the margin buys: a string more than [RANGE_TOLERANCE] of a semitone away from the end
 * of the range —a D1 a whole semitone flat, a B4 more than a semitone sharp— is not reported until
 * it enters the band, so the user has to trust the display for the first semitone of the correction.
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
 * the average of two different strings for a few frames. A change of octave folding state is covered
 * by the same reset, since the offsets on both sides of it live twelve semitones apart.
 *
 * The instance is stateful and not thread safe: [process] is meant to be called from the single
 * thread that delivers the audio frames, with [reset] called from the same thread when the tuner is
 * restarted.
 *
 * @param medianWindow Number of frames the reported offset is the median of. A value below 1 is
 * treated as 1, which emits every frame as it is.
 * @param minRms Absolute floor of the level gate: a frame below it is discarded. A negative value
 * disables the floor, since no measurable level falls below it: pass `-1f`, never [Float.NaN],
 * because a `NaN` threshold would compare false against every level and quietly disable the gate as
 * well.
 * @param foldTolerance Distance in semitones within which a frame is considered to belong to a
 * string, both in its own octave and in the octave below.
 * @param foldConfirmFrames Number of consecutive frames that must ask for the folding before it holds
 * on the frames that reject it. A value below 1 is treated as 1.
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

    /** Frames needed to hold the folding on the frames that reject it, never below 1. */
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
            val foldsWanted = isFoldWanted(raw, candidates)
            updateFold(foldsWanted)
            if (foldActive || foldsWanted) raw - SEMITONES_PER_OCTAVE else raw
        } else {
            clearFold()
            raw
        }

        if (applied < lowestNote - RANGE_TOLERANCE || applied > highestNote + RANGE_TOLERANCE) {
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

    /** Drops the octave folding and the counters that lead to it. */
    private fun clearFold() {
        foldActive = false
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

        /**
         * Absolute floor of the level gate: -80 dBFS, under any room that is worth tuning in.
         *
         * Measured on the chain of the app —a low E with its harmonics decaying as `1/n`, through the
         * conditioner and [be.tarsos.dsp.pitch.FastYin] at [TunerPipeline.YIN_THRESHOLD]— the
         * detector still resolves the note on **29 of 29** frames at a frame level of `6.6e-5`
         * (-84 dBFS), while the `1e-3` (-60 dBFS) this floor used to sit at discarded every frame
         * under it: the last 20 dB of the note, which on the microphone of a phone against an
         * unplugged electric guitar is the note itself. The floor stays at -80 dBFS rather than at
         * the -84 dBFS the detector reaches, because it is also the level under which a room stops
         * being silent, and a floor below the room buys noise rather than signal.
         * `PipelineIntegrationTest.testNoteAtTheLevelOfTheOldGateIsDetected` pins a note at
         * `1.96e-4` to `2.05e-4` (-74 dBFS), the level the old value lost.
         */
        const val MIN_RMS = 1e-4f

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

        /** Frames that must ask for the octave folding before it holds on the frames that reject it. */
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
