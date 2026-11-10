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
import kotlin.math.pow
import com.rohankhayech.choona.lib.model.tuning.Notes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Level of a frame that is clearly above the gate, in the scale of the received samples. */
private const val AUDIBLE_RMS = 0.5f

/** Frame level of a note that only a chain without the old cliff can follow: -74 dBFS. */
private const val WEAK_NOTE_RMS = 2e-4f

/** Frames of a signal in which the detector only reports the note on every other one. */
private const val BLINKING_FRAMES = 30

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
            GateCase("silent frame is rejected", 440f, 5e-5f, expected = null),
            GateCase("unknown level", 440f, Float.NaN, expected = 0.0),
            GateCase(
                "negative min rms disables the level gate",
                440f,
                1e-6f,
                minRms = -1f,
                fold = false,
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
            val candidates = if (case.fold) standardCandidates else null
            val result = tracker.process(case.pitchHz, case.inputRms, candidates)
            val expected = case.expected
            if (expected == null) {
                assertNull(case.description, result)
            } else {
                assertNotNull(case.description, result)
                assertEquals(case.description, expected, result!!, DELTA)
            }
        }
    }

    @Test
    fun gateAcceptsAFrameAtTheLevelOfAWeakNote() {
        // The floor sits under the level of the string of an unplugged electric guitar, -74 dBFS,
        // and the -60 dBFS it used to sit at discarded every frame of it.
        val levels = listOf(1.2e-4f, WEAK_NOTE_RMS, 5e-4f, 1e-3f)

        for (level in levels) {
            assertEquals(
                "A note at a frame level of $level must be tracked, not discarded by the gate",
                -29.0,
                offsetOf(PitchTracker().process(pitchOf("E2"), level, standardCandidates)),
                DELTA,
            )
        }
    }

    @Test
    fun decayingNoteIsTrackedDownToTheFloor() {
        // A pluck loses about 3 dB per frame, and the note must stay on the needle until its level
        // reaches the absolute floor of the gate. The floor and the decay are written out here
        // instead of read from [PitchTracker.MIN_RMS], so that moving the floor breaks this test
        // rather than silently changing what it measures: the 25th frame measures 1.26e-4 and the
        // 26th 8.89e-5, which is where the note has to disappear.
        val levels = (0 until 40).map { (AUDIBLE_RMS * 10.0.pow(-3.0 * it / 20.0)).toFloat() }
        val tracker = PitchTracker()

        val published = levels.map { rms ->
            tracker.process(pitchOf("E2"), rms, standardCandidates) != null
        }

        assertEquals(
            "The note must be published on every frame at or above the 1e-4 floor of the gate and " +
                "on none under it, but the tracker published the frames " +
                "${published.indices.filter { published[it] }}",
            (0 until 25).toList(),
            published.indices.filter { published[it] },
        )
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
            tracker.process(440f, 5e-5f, standardCandidates),
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
        // The fold is left out of these two arms so that they read the range gate alone: a B4 is
        // also the double of the B3 string, which is a question of the folding.
        assertEquals(
            "A B4 five cents sharp sits above the end of the range by a fraction of a semitone " +
                "and must still be tracked, or the highest string could never be tuned",
            2.05,
            offsetOf(rangeGatedTracker().process(pitchOf("B4", 0.05), AUDIBLE_RMS, null)),
            JITTER_DELTA,
        )
        assertNull(
            "A B4 two semitones sharp is outside of the range by more than the margin",
            rangeGatedTracker().process(pitchOf("B4", 2.0), AUDIBLE_RMS, null),
        )
        assertEquals(
            "A D1 five cents flat sits below the end of the range by a fraction of a semitone and " +
                "must still be tracked, or the lowest string could never be tuned",
            -43.05,
            offsetOf(rangeGatedTracker().process(pitchOf("D1", -0.05), AUDIBLE_RMS, null)),
            JITTER_DELTA,
        )
    }

    @Test
    fun rangeGateReadsTheFoldedOffset() {
        val tracker = rangeGatedTracker()

        assertEquals(
            "An E5 reads as the harmonic it is, +7 semitones from A4 and twelve above the E4 " +
                "string, but it asks for the folding: the range gate reads the folded offset and " +
                "accepts the frame as the E4 string it belongs to, on that very frame",
            -5.0,
            offsetOf(tracker.processNote("E5")),
            JITTER_DELTA,
        )
        assertEquals(
            "The E5 must keep being published as the E4 string once the folding is confirmed",
            -5.0,
            offsetOf(tracker.processNote("E5")),
            JITTER_DELTA,
        )
    }

    @Test
    fun frameThatAsksForTheFoldingIsPublishedFoldedOnItsFirstFrame() {
        // +2.0 semitones from A4 is the double of the B3 string: inside the range, two semitones
        // above it, and seven semitones away from the E4 string the raw offset is closest to.
        // Nothing but the folding rule stops it from reaching the needle as a note of that string,
        // so the folded offset is what the frame publishes, with no frame held back.
        val tracker = rangeGatedTracker()

        assertEquals(
            "A frame that asks for the folding must publish the folded note, not the octave above " +
                "the string it belongs to",
            -10.0,
            offsetOf(tracker.process(pitchOf("B4"), AUDIBLE_RMS, standardCandidates)),
            DELTA,
        )
        assertEquals(
            "The frame that confirms the folding must publish the same B3 string",
            -10.0,
            offsetOf(tracker.process(pitchOf("B4"), AUDIBLE_RMS, standardCandidates)),
            DELTA,
        )
    }

    @Test
    fun framesNearAStringOrDetunedAwayFromEveryStringArePublishedAtOnce() {
        // A frame within the tolerance of a string is a note of that string, and a frame three
        // semitones off one whose octave below is no string either is a tuning in progress: neither
        // asks for the folding, so both are published on their first frame as they are. The other
        // half of the rule is the limitation of the octave folding and not a mistake of this test:
        // a string three semitones off whose octave below *is* a string of the tuning —a G3 flat to
        // an E3, a B3 sharp to a D4— is published as that string.
        for ((note, shift) in listOf("E2" to 0.0, "B3" to 0.4, "E4" to -0.5, "D3" to 3.0)) {
            val tracker = PitchTracker()

            assertEquals(
                "$note${if (shift == 0.0) "" else " $shift"} must be published on its first frame",
                Notes.getOffsetFromA4(pitchOf(note, shift).toDouble()),
                offsetOf(tracker.processNote(note, shift)),
                JITTER_DELTA,
            )
        }
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
    fun frameThatAsksForTheFoldingIsPublishedWhileTheDetectorBlinks() {
        // The detector drops frames of a note that is being played, and a tracker that withheld the
        // frames that ask for the folding lost the note for good when it did: half of the frames
        // carry the note and the withheld one is followed by a reset that throws its confirmation
        // away, which published 0 of the 15 frames.
        val tracker = PitchTracker()
        val expected = Notes.getOffsetFromA4(pitchOf("B3").toDouble())
        val published = mutableListOf<Double>()

        repeat(BLINKING_FRAMES) { index ->
            if (index % 2 == 0) {
                tracker.process(pitchOf("B4"), AUDIBLE_RMS, standardCandidates)
                    ?.let { published += it }
            } else {
                tracker.reset()
            }
        }

        assertEquals(
            "Every frame that holds the note must be published, but the tracker published " +
                "${published.size} of ${BLINKING_FRAMES / 2} frames",
            BLINKING_FRAMES / 2,
            published.size,
        )
        assertTrue(
            "The frames must publish the B3 string the octave error belongs to, but they " +
                "published $published",
            published.all { abs(it - expected) <= PitchTracker.FOLD_TOLERANCE },
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
    fun foldNeedsTwoCoherentFramesToHoldOnTheFramesThatRejectIt() {
        val tracker = PitchTracker()

        assertEquals(
            "The first frame of an E3 asks for the folding, so it is published folded: its own " +
                "octave is a note of no string",
            -29.0,
            offsetOf(tracker.processNote("E3")),
            DELTA,
        )
        assertEquals(
            "A single frame that asks for the folding is not enough to hold it, so the frame of " +
                "the string itself is published as it was detected",
            -5.0,
            offsetOf(tracker.processNote("E4")),
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
        /** Whether the frame is given the candidates of the tuning, which is what folds an octave. */
        val fold: Boolean = true,
        val expected: Double?,
    )
}
