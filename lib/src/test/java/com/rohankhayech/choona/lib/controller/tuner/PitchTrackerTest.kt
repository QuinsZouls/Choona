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

import kotlin.math.pow
import com.rohankhayech.choona.lib.model.tuning.Notes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Level of a frame that is clearly above the gate, in the scale of the received samples. */
private const val AUDIBLE_RMS = 0.5f

/** Tolerance of the offsets compared against an exact pitch of [Notes]. */
private const val DELTA = 1e-6

/** Tolerance of the offsets of a pitch shifted by a fraction of a semitone through [Float]. */
private const val JITTER_DELTA = 1e-5

class PitchTrackerTest {

    /** Strings of the standard tuning, as note indices of [Notes]. */
    private val standardCandidates = intArrayOf(
        Notes.getIndex("E2"),
        Notes.getIndex("A2"),
        Notes.getIndex("D3"),
        Notes.getIndex("G3"),
        Notes.getIndex("B3"),
        Notes.getIndex("E4"),
    )

    // ---- A. Gate ----

    @Test
    fun gateAcceptsAndRejectsFrames() {
        val cases = listOf(
            GateCase("a detected note", 440f, AUDIBLE_RMS, expected = 0.0),
            GateCase("silent frame is rejected", 440f, 5e-4f, expected = null),
            GateCase("unknown level", 440f, Float.NaN, expected = 0.0),
            GateCase(
                "negative min rms disables the level gate",
                440f,
                -1f,
                minRms = -1f,
                expected = 0.0,
            ),
            GateCase("zero pitch is rejected", 0f, AUDIBLE_RMS, expected = null),
            GateCase("negative pitch", -1f, AUDIBLE_RMS, expected = null),
            GateCase("nan pitch", Float.NaN, AUDIBLE_RMS, expected = null),
            GateCase(
                "positive infinite pitch is rejected",
                Float.POSITIVE_INFINITY,
                AUDIBLE_RMS,
                expected = null,
            ),
            GateCase(
                "negative infinite pitch is rejected",
                Float.NEGATIVE_INFINITY,
                AUDIBLE_RMS,
                expected = null,
            ),
        )

        for (case in cases) {
            val tracker = PitchTracker(minRms = case.minRms)
            val result = tracker.process(case.pitchHz, case.inputRms, standardCandidates)
            val expected = case.expected
            if (expected == null) {
                assertNull(case.description, result)
            } else {
                assertEquals(case.description, expected, offsetOf(result), DELTA)
            }
        }
    }

    @Test
    fun frameOfAnyConfidenceIsTracked() {
        // The confidence of a frame is filtered by the detector, which only reports a pitch whose
        // clarity stays above 1 - YIN_THRESHOLD: every frame that reaches the tracker has already
        // passed that filter, and a second one here would only discard the weak frames it accepts.
        val tracker = PitchTracker()

        assertEquals(
            "A detected note must be tracked whatever clarity came with it",
            Notes.getOffsetFromA4(440.0),
            offsetOf(tracker.process(440f, AUDIBLE_RMS, null)),
            DELTA,
        )
    }

    @Test
    fun discardedFrameResetsTheWindow() {
        val tracker = PitchTracker()
        repeat(4) { tracker.processNote("E2") }

        assertNull(
            "A frame below the level gate should be discarded",
            tracker.process(440f, 1e-4f, standardCandidates),
        )
        assertEquals(
            "The first frame after a discard should stand alone: a frame 0.3 semitones away is " +
                "inside the threshold of a note change, so the window of the discarded note only " +
                "disappears if the discard clears it",
            -28.7,
            offsetOf(tracker.processNote("E2", 0.3)),
            JITTER_DELTA,
        )
    }

    @Test
    fun windowOfTheSameNoteSurvivesWhenNoFrameIsDiscarded() {
        val tracker = PitchTracker()
        repeat(5) { tracker.processNote("E2") }

        assertEquals(
            "Without a discard the frame is averaged with the window of the same note, whose " +
                "median is the note itself",
            -29.0,
            offsetOf(tracker.processNote("E2", 0.3)),
            JITTER_DELTA,
        )
    }

    // ---- A2. Range ----

    @Test
    fun rangeGateRejectsTheNotesOutsideTheDetectableRange() {
        val tracker = rangeGatedTracker()

        assertEquals(
            "The lowest note of the range must be tracked",
            Tuner.LOWEST_NOTE.toDouble(),
            offsetOf(tracker.process(pitchOf("D1"), AUDIBLE_RMS, null)),
            JITTER_DELTA,
        )
        assertNull(
            "A C1 sits below the range the tuner can represent",
            tracker.process(pitchOf("C1"), AUDIBLE_RMS, null),
        )
        assertEquals(
            "The highest note of the range must be tracked",
            Tuner.HIGHEST_NOTE.toDouble(),
            offsetOf(tracker.process(pitchOf("B4"), AUDIBLE_RMS, null)),
            JITTER_DELTA,
        )
        assertNull(
            "A B5 is twelve semitones above the highest note of the range",
            tracker.process(pitchOf("B5"), AUDIBLE_RMS, null),
        )
        assertNull(
            "A B6 is far above the range",
            tracker.process(pitchOf("B6"), AUDIBLE_RMS, null),
        )
    }

    @Test
    fun rangeGateKeepsASemitoneOfMargin() {
        assertEquals(
            "A B4 five cents sharp sits above the end of the range by a fraction of a semitone " +
                "and must still be tracked, or the highest string could never be tuned",
            2.05,
            offsetOf(rangeGatedTracker().processNote("B4", 0.05)),
            JITTER_DELTA,
        )
        assertNull(
            "A B4 two semitones sharp is outside of the range by more than the margin",
            rangeGatedTracker().processNote("B4", 2.0),
        )
        assertEquals(
            "A D1 five cents flat sits below the end of the range by a fraction of a semitone and " +
                "must still be tracked, or the lowest string could never be tuned",
            -43.05,
            offsetOf(rangeGatedTracker().processNote("D1", -0.05)),
            JITTER_DELTA,
        )
    }

    @Test
    fun rangeGateReadsTheFoldedOffset() {
        val tracker = rangeGatedTracker()

        assertNull(
            "An E5 that is not folded yet reads as the harmonic it is, an octave above the E4 " +
                "string, and is out of range: the frame is held, and nothing is published",
            tracker.processNote("E5"),
        )
        assertEquals(
            "The E5 is folded on the next frame, since the held frame kept the counter that " +
                "confirms the folding, and the range accepts the note as the E4 string it belongs " +
                "to",
            -5.0,
            offsetOf(tracker.processNote("E5")),
            JITTER_DELTA,
        )
    }

    // ---- B. Median ----

    @Test
    fun medianIsTakenOverTheWindow() {
        val tracker = PitchTracker()
        val shifts = listOf(-0.3, 0.3, -0.1, 0.1, 0.0)
        // The first frame is its own median, the even windows average their two central offsets.
        val expected = listOf(-29.3, -29.0, -29.1, -29.0, -29.0)

        for ((index, shift) in shifts.withIndex()) {
            val result = tracker.processNote("E2", shift)
            assertEquals("Frame $index", expected[index], offsetOf(result), JITTER_DELTA)
        }
    }

    @Test
    fun singleOutlierDoesNotMoveTheMedian() {
        val tracker = PitchTracker(resetJumpThreshold = 10.0)
        repeat(4) { index ->
            assertEquals("Frame $index", -29.0, offsetOf(tracker.processNote("E2")), DELTA)
        }

        assertEquals(
            "One frame three semitones off should not move the output",
            -29.0,
            offsetOf(tracker.processNote("E2", 3.0)),
            0.01,
        )
    }

    @Test
    fun resetForgetsTheWindow() {
        val tracker = PitchTracker()
        repeat(5) { tracker.processNote("E2") }

        tracker.reset()
        tracker.reset()

        assertEquals(
            "The first frame after a reset should stand alone",
            -17.0,
            offsetOf(tracker.processNote("E3", candidates = null)),
            DELTA,
        )
    }

    @Test
    fun windowOfOneReturnsTheFrameOffset() {
        val tracker = PitchTracker(medianWindow = 1)

        assertEquals("First frame", -29.0, offsetOf(tracker.processNote("E2")), DELTA)
        assertEquals(
            "Second frame",
            -17.0,
            offsetOf(tracker.processNote("E3", candidates = null)),
            DELTA,
        )
    }

    // ---- C. Octave folding ----

    @Test
    fun octaveErrorIsFoldedOntoTheString() {
        val tracker = PitchTracker(foldConfirmFrames = 1)

        assertEquals(
            "E3 is folded onto the low E string",
            -29.0,
            offsetOf(tracker.processNote("E3")),
            DELTA,
        )
    }

    @Test
    fun notesOfTheTuningAreNotFolded() {
        val cases = listOf("E2" to -29.0, "E4" to -5.0, "B3" to -10.0, "A2" to -24.0)

        for ((note, expected) in cases) {
            val tracker = PitchTracker(foldConfirmFrames = 1)
            assertEquals("$note is a string", expected, offsetOf(tracker.processNote(note)), DELTA)
        }
    }

    @Test
    fun a4IsNotFoldedToA2() {
        val tracker = PitchTracker(foldConfirmFrames = 1)

        assertEquals("A4 is no string", 0.0, offsetOf(tracker.processNote("A4")), DELTA)
    }

    @Test
    fun chromaticModeDoesNotFold() {
        val tracker = PitchTracker(foldConfirmFrames = 1)

        assertEquals(
            "E3 in chromatic mode",
            -17.0,
            offsetOf(tracker.processNote("E3", candidates = null)),
            DELTA,
        )
    }

    @Test
    fun emptyCandidatesDoNotFold() {
        val tracker = PitchTracker(foldConfirmFrames = 1)

        assertEquals(
            "E3 with a tuning of no strings",
            -17.0,
            offsetOf(tracker.processNote("E3", candidates = IntArray(0))),
            DELTA,
        )
    }

    @Test
    fun disabledFoldingKeepsTheDetectedOctave() {
        val tracker = PitchTracker(foldEnabled = false)

        assertEquals("E3 unfolded", -17.0, offsetOf(tracker.processNote("E3")), DELTA)
    }

    // ---- D. Hysteresis ----

    @Test
    fun foldNeedsTwoCoherentFrames() {
        val tracker = PitchTracker()

        assertEquals(
            "The first frame should keep the detected octave",
            -17.0,
            offsetOf(tracker.processNote("E3")),
            DELTA,
        )
        assertEquals(
            "The second frame should fold",
            -29.0,
            offsetOf(tracker.processNote("E3")),
            DELTA,
        )
    }

    @Test
    fun foldIsReleasedAfterTwoIncoherentFrames() {
        val tracker = PitchTracker()
        repeat(PitchTracker.FOLD_CONFIRM_FRAMES) { tracker.processNote("E3") }

        assertEquals(
            "The first frame should keep the folding",
            -17.0,
            offsetOf(tracker.processNote("E4")),
            DELTA,
        )
        assertEquals(
            "The second frame should release it",
            -5.0,
            offsetOf(tracker.processNote("E4")),
            DELTA,
        )
    }

    // ---- E. Note changes ----

    @Test
    fun noteChangeDiscardsTheWindow() {
        val tracker = PitchTracker(foldEnabled = false)
        repeat(5) { index ->
            assertEquals("Frame $index", -29.0, offsetOf(tracker.processNote("E2")), DELTA)
        }

        assertEquals(
            "A new note should not be averaged with the previous one",
            -17.0,
            offsetOf(tracker.processNote("E3")),
            DELTA,
        )
    }

    @Test
    fun smallDriftKeepsTheWindow() {
        val tracker = PitchTracker(foldEnabled = false, medianWindow = 2)
        tracker.processNote("E2")

        assertEquals(
            "Two frames 2 semitones apart should be averaged",
            -28.0,
            offsetOf(tracker.processNote("E2", 2.0)),
            JITTER_DELTA,
        )
    }

    // ---- F. Passthrough ----

    @Test
    fun passthroughReportsTheRawOffset() {
        val tracker = PitchTracker.passthrough()

        val result = tracker.process(452f, Float.NaN, null)

        assertEquals(Notes.getOffsetFromA4(452.0), offsetOf(result), 1e-9)
    }

    // ---- G. Candidates ----

    @Test
    fun candidatesAreNotModified() {
        val candidates = standardCandidates.copyOf()
        val tracker = PitchTracker(foldConfirmFrames = 1)

        tracker.processNote("E3", candidates = candidates)
        tracker.processNote("A4", candidates = candidates)

        assertArrayEquals(standardCandidates, candidates)
    }

    /** Feeds the pitch of a note and returns the offset reported by the tracker. */
    private fun PitchTracker.processNote(
        note: String,
        semitones: Double = 0.0,
        candidates: IntArray? = standardCandidates,
    ): Double? = process(pitchOf(note, semitones), AUDIBLE_RMS, candidates)

    /** Creates a tracker with the range of notes the tuner wires it with, as production does. */
    private fun rangeGatedTracker(): PitchTracker = PitchTracker(
        lowestNote = Tuner.LOWEST_NOTE,
        highestNote = Tuner.HIGHEST_NOTE,
    )

    /** Frequency in Hz of the given note, shifted by a fraction of a semitone. */
    private fun pitchOf(note: String, semitones: Double = 0.0): Float =
        (Notes.getPitch(Notes.getIndex(note)) * 2.0.pow(semitones / 12.0)).toFloat()

    /** Unwraps the offset of a frame that must not have been discarded. */
    private fun offsetOf(result: Double?): Double =
        checkNotNull(result) { "Expected a tracked offset, but the frame was discarded" }

    /**
     * A frame fed to the gate, with the tracker settings it needs and the offset it must report.
     *
     * @param description What the case exercises, used as the assertion message.
     * @param pitchHz Detected frequency in Hz.
     * @param inputRms Level of the frame.
     * @param minRms Level gate of the tracker. Defaults to the app default.
     * @param expected Offset the frame must report, or `null` if it must be discarded.
     */
    private data class GateCase(
        val description: String,
        val pitchHz: Float,
        val inputRms: Float,
        val minRms: Float = PitchTracker.MIN_RMS,
        val expected: Double?,
    )
}
