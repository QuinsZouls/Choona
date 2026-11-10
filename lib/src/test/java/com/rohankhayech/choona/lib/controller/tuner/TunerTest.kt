/*
 * Choona - Guitar Tuner
 * Copyright (C) 2025 Rohan Khayech
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

import be.tarsos.dsp.pitch.PitchDetectionResult
import com.rohankhayech.choona.lib.model.tuning.GuitarString
import com.rohankhayech.choona.lib.model.tuning.Notes
import com.rohankhayech.choona.lib.model.tuning.Tuning
import com.rohankhayech.choona.lib.model.tuning.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TunerTest {

    private lateinit var tuner: Tuner

    @Before
    fun setUp() {
        // The real pipeline applies multi-frame median/octave folding (PitchTracker).
        // This suite pins the pure, frame-by-frame offset calculation.
        tuner = Tuner(Tuning.STANDARD, PitchTracker.passthrough())
    }

    @Test
    fun testSelectString() {
        tuner.selectString(5)
        assertEquals(5, tuner.selectedString.value)
        assertFalse(tuner.autoDetect.value)

        // Test invalid
        assertThrows(IllegalArgumentException::class.java) { tuner.selectString(-1) }
        assertThrows(IllegalArgumentException::class.java) { tuner.selectString(6) }
    }

    @Test
    fun testSetTuning() {
        for (i in 0 until tuner.tuning.value.numStrings()) {
            tuner.setTuned(i)
        }

        tuner.setTuning(Tuning.DROP_D)
        assertSame(Tuning.DROP_D, tuner.tuning.value)

        // Check reset tuned status on different string.
        assertFalse(tuner.tuned.value[5])
        for (i in 0..4) {
            try {
                assertTrue(tuner.tuned.value[i])
            } catch (_: AssertionError) {
                fail("String $i should be tuned")
            }
        }
        assertFalse(tuner.chromatic.value)

        tuner.setChromatic()
        tuner.setTuning(Tunings.WHOLE_STEP_DOWN)

        assertSame(Tunings.WHOLE_STEP_DOWN, tuner.tuning.value)
        assertFalse(tuner.chromatic.value)
    }

    @Test
    fun testTuneUp() {
        for (i in 0 until tuner.tuning.value.numStrings()) {
            tuner.setTuned(i)
        }

        tuner.tuneUp()
        assertEquals(Tuning.STANDARD.higherTuning(), tuner.tuning.value)
        for (i in 0..5) {
            assertFalse(tuner.tuned.value[i])
        }

        // Test highest.
        tuner.setTuning(
            Tuning(
                GuitarString.A2,
                GuitarString.fromRootNote(
                    Notes.getSymbol(Tuner.HIGHEST_NOTE - 1)
                )
            )
        )
        assertTrue(tuner.tuneUp())
        assertFalse(tuner.tuneUp())

    }

    @Test
    fun testTuneDown() {
        for (i in 0 until tuner.tuning.value.numStrings()) {
            tuner.setTuned(i)
        }

        tuner.tuneDown()
        assertEquals(Tuning.STANDARD.lowerTuning(), tuner.tuning.value)
        for (i in 0..5) {
            assertFalse(tuner.tuned.value[i])
        }

        // Test lowest.
        tuner.setTuning(
            Tuning(
                GuitarString.A2,
                GuitarString.fromRootNote(
                    Notes.getSymbol(Tuner.LOWEST_NOTE + 1)
                )
            )
        )
        assertTrue(tuner.tuneDown())
        assertFalse(tuner.tuneDown())
    }

    @Test
    fun testTuneStringUp() {
        tuner.setTuned(0)

        tuner.tuneStringUp(0)
        assertEquals(GuitarString.E4.higherString(), tuner.tuning.value.getString(0))
        assertFalse(tuner.tuned.value[0])

        // Test invalid
        assertThrows(IllegalArgumentException::class.java) { tuner.tuneStringUp(-1) }
        assertThrows(IllegalArgumentException::class.java) { tuner.tuneStringUp(6) }

        // Test highest.
        tuner.setTuning(Tuning(GuitarString.fromRootNote(Notes.getSymbol(Tuner.HIGHEST_NOTE - 1))))
        assertTrue(tuner.tuneStringUp(0))
        assertFalse(tuner.tuneStringUp(0))
    }

    @Test
    fun testTuneStringDown() {
        tuner.setTuned(0)

        tuner.tuneStringDown(0)
        assertEquals(GuitarString.E4.lowerString(), tuner.tuning.value.getString(0))
        assertFalse(tuner.tuned.value[0])

        // Test invalid
        assertThrows(IllegalArgumentException::class.java) { tuner.tuneStringDown(-1) }
        assertThrows(IllegalArgumentException::class.java) { tuner.tuneStringDown(6) }

        // Test lowest.
        tuner.setTuning(Tuning(GuitarString.fromRootNote(Notes.getSymbol(Tuner.LOWEST_NOTE + 1))))
        assertTrue(tuner.tuneStringDown(0))
        assertFalse(tuner.tuneStringDown(0))
    }

    @Test
    fun testSelectNote() {
        tuner.setChromatic()
        tuner.setTuned()

        tuner.selectNote(Tuner.LOWEST_NOTE)
        assertEquals(Tuner.LOWEST_NOTE, tuner.selectedNote.value)
        assertFalse(tuner.noteTuned.value)

        tuner.selectNote(Tuner.HIGHEST_NOTE)
        assertEquals(Tuner.HIGHEST_NOTE, tuner.selectedNote.value)

        tuner.setTuned()
        tuner.selectNote(Tuner.HIGHEST_NOTE)
        assertTrue(tuner.noteTuned.value)

        assertThrows(IllegalArgumentException::class.java) { tuner.selectNote(Tuner.LOWEST_NOTE-1) }
        assertThrows(IllegalArgumentException::class.java) { tuner.selectNote(Tuner.HIGHEST_NOTE+1) }
    }

    @Test
    fun testSetTuned() {
        tuner.setTuned()
        assertTrue(tuner.tuned.value[0])
        tuner.setTuned(1)
        assertTrue(tuner.tuned.value[1])
        tuner.setTuned(1, false)
        assertFalse(tuner.tuned.value[1])

        // Test tuned.
        assertThrows(IllegalArgumentException::class.java) { tuner.setTuned(-1) }
        assertThrows(IllegalArgumentException::class.java) { tuner.setTuned(6) }
    }

    @Test
    fun testSetAutoDetect() {
        tuner.setAutoDetect(false)
        assertFalse(tuner.autoDetect.value)
        tuner.setAutoDetect(false)
        assertFalse(tuner.autoDetect.value)
    }

    @Test
    fun testProcessPitch() {
        // Test with auto detect.
        tuner.selectString(5)
        tuner.setAutoDetect(true)
        tuner.processPitch(PitchDetectionResult().apply { pitch = 440f; isPitched = true })
        assertEquals(0, tuner.selectedString.value) // Test auto-detect.
        assertEquals(5.0, tuner.noteOffset.value!!, 0.001) // Test note offset.

        // Test with auto detect off.
        tuner.setAutoDetect(false)
        tuner.processPitch(PitchDetectionResult().apply { pitch = 246.94f; isPitched = true })
        assertEquals(0, tuner.selectedString.value) // Test no auto-detect.
        assertEquals(-5.0, tuner.noteOffset.value!!, 0.001) // Test note offset.

        // Test un-pitched.
        tuner.setAutoDetect(true)
        tuner.processPitch(PitchDetectionResult().apply { pitch = -1f; isPitched = false })
        assertEquals(0, tuner.selectedString.value)
        assertNull(tuner.noteOffset.value) // Test not offset.

        // Test chromatic with auto detect.
        tuner.setChromatic()
        tuner.setTuned()
        tuner.setAutoDetect(true)
        tuner.processPitch(PitchDetectionResult().apply { pitch = 452f; isPitched = true })
        assertEquals(Notes.getIndex("A4"), tuner.selectedNote.value) // Test auto-detect.
        assertEquals(0.466, tuner.noteOffset.value!!, 0.001) // Test note offset.
        assertFalse(tuner.noteTuned.value)

        // Test with auto detect off.
        tuner.setAutoDetect(false)
        tuner.processPitch(PitchDetectionResult().apply { pitch = 246.94f; isPitched = true })
        assertEquals(Notes.getIndex("A4"), tuner.selectedNote.value) // Test no auto-detect.
        assertEquals(-10.0, tuner.noteOffset.value!!, 0.001) // Test note offset.

        // Test un-pitched.
        tuner.setAutoDetect(true)
        tuner.processPitch(PitchDetectionResult().apply { pitch = -1f; isPitched = false })
        assertEquals(Notes.getIndex("A4"), tuner.selectedNote.value) // Test no change.
        assertNull(tuner.noteOffset.value) // Test not offset.
    }

    // Behaviour of the real pipeline: the gates, the octave folding and the median of PitchTracker,
    // which [Tuner.start] wires between the detector and the rest of the tuner.

    @Test
    fun testProcessPitchRmsGate() {
        val tuner = newPipelineTuner()
        val frame = frame(pitch = A4_HZ)

        tuner.processPitch(frame, 5e-5f)
        assertNull(
            "A frame below MIN_RMS (${PitchTracker.MIN_RMS}) must not move the needle",
            tuner.noteOffset.value,
        )

        tuner.processPitch(frame, 0.1f)
        assertEquals(
            "The same frame above the level gate must be reported",
            5.0,
            tuner.noteOffset.value!!,
            0.001,
        )
        assertEquals(
            "Auto detect must select the E4 string, the closest one to an A4",
            stringIndexOf("E4"),
            tuner.selectedString.value,
        )
    }

    @Test
    fun testProcessPitchDoesNotFilterOnTheConfidenceOfTheFrame() {
        val tuner = newPipelineTuner()

        // The confidence is filtered by the detector itself: FastYin only flags a frame as pitched
        // when its clarity stays above 1 - YIN_THRESHOLD (0.85), so a frame that reaches the tuner
        // has already passed the only confidence filter of the chain. Repeating it here would throw
        // away the weak frames the detector accepted, which is the note the tuner is played to see.
        tuner.processPitch(frame(pitch = A4_HZ, probability = 0.4f), 0.1f)
        assertEquals(
            "A detected frame must move the needle whatever clarity the detector reported with it",
            5.0,
            tuner.noteOffset.value!!,
            0.001,
        )
    }

    @Test
    fun testProcessPitchOctaveFold() {
        val tuner = newPipelineTuner()
        // The second harmonic of the low E2 string is an E3, which is no string of the tuning: the
        // detector reports a note an octave high and only the folding of the tracker undoes it. The
        // folding is applied to the frame that asks for it, so the first one is already the E2.
        val secondHarmonicOfE2 = frame(pitch = E3_HZ, probability = 0.99f)

        tuner.processPitch(secondHarmonicOfE2, 0.1f)
        assertEquals(
            "The frame that asks for the folding must be shown folded, not as the octave above the " +
                "string",
            0.0,
            checkNotNull(tuner.noteOffset.value),
            0.05,
        )
        assertEquals(
            "The folded note must be attributed to the E2 string",
            stringIndexOf("E2"),
            tuner.selectedString.value,
        )

        tuner.processPitch(secondHarmonicOfE2, 0.1f)
        assertEquals(
            "The folded note must keep being in tune with the low E",
            0.0,
            tuner.noteOffset.value!!,
            0.05,
        )
        assertEquals(
            "The folded note must keep being attributed to the E2 string",
            stringIndexOf("E2"),
            tuner.selectedString.value,
        )
    }

    @Test
    fun testE5AboveTheRangeIsFoldedOnItsFirstFrame() {
        val tuner = Tuner(Tuning.STANDARD)
        // The second harmonic of the highest E string reads as +7 semitones from A4, which is above
        // the ceiling of the range (B4, +2). Its octave below is the E4 string, an octave down and
        // inside the range, so the note is folded rather than dropped, and the folding is applied to
        // the frame that asks for it: the first frame of the E5 is already the E4 string, in tune.
        val e5 = frame(pitch = E5_HZ)

        tuner.processPitch(e5, 0.1f)
        assertEquals(
            "An E5 must be attributed to the E4 string it belongs to on its first frame, instead " +
                "of the wrong string at the end of the needle",
            stringIndexOf("E4"),
            tuner.selectedString.value,
        )
        assertEquals(
            "The folded E5 must read in tune with the E4 string",
            0.0,
            checkNotNull(tuner.noteOffset.value),
            0.05,
        )

        tuner.processPitch(e5, 0.1f)
        assertEquals(
            "The E5 must keep being shown as the E4 string it belongs to",
            stringIndexOf("E4"),
            tuner.selectedString.value,
        )
    }

    @Test
    fun testB5AboveTheRangeIsNeverShown() {
        val tuner = Tuner(Tuning.STANDARD)
        val initialString = tuner.selectedString.value
        // A B5 is twelve semitones above the ceiling of the range, and it does not fold: the note an
        // octave below is a B4, which is no string of the standard tuning, so the folded value sits
        // seven semitones away from the closest string (the E4). No number of frames brings it into
        // the range, and it must never move the needle.
        repeat(PitchTracker.FOLD_CONFIRM_FRAMES + 3) { index ->
            tuner.processPitch(frame(pitch = B5_HZ), 0.1f)
            assertNull(
                "A B5 must never be shown, but frame $index moved the needle to " +
                    "${tuner.noteOffset.value}",
                tuner.noteOffset.value,
            )
            assertEquals(
                "A B5 must not change the selected string",
                initialString,
                tuner.selectedString.value,
            )
        }
    }

    @Test
    fun testB6AboveTheRangeIsNeverShown() {
        val instrument = Tuner(Tuning.STANDARD)
        repeat(PitchTracker.FOLD_CONFIRM_FRAMES + 1) {
            instrument.processPitch(frame(pitch = B6_HZ), 0.1f)
        }
        assertNull(
            "A B6 is far above the range and must not move the needle, but it read " +
                "${instrument.noteOffset.value}",
            instrument.noteOffset.value,
        )

        val chromatic = chromaticTuner()
        chromatic.selectNote(Tuner.HIGHEST_NOTE)
        chromatic.setAutoDetect(true)
        chromatic.processPitch(frame(pitch = B6_HZ), 0.1f)
        assertNull(
            "A B6 must not move the needle in chromatic mode either",
            chromatic.noteOffset.value,
        )
        assertEquals(
            "An out of range note must not move the selected note",
            Tuner.HIGHEST_NOTE,
            chromatic.selectedNote.value,
        )
    }

    @Test
    fun testB4AtTheEdgeOfTheRangeIsAccepted() {
        // The margin of RANGE_TOLERANCE (1 semitone) exists for the two ends of the instrument: a
        // B4 that sits high has to be tracked, or the highest string could never be brought down.
        // Chromatic mode is used to isolate the range gate: in instrument mode a B4 is folded onto
        // the B3 string, which is a question of the folding and not of the range.
        val instrument = Tuner(Tuning.STANDARD)
        instrument.processPitch(frame(pitch = B4_HZ), 0.1f)
        assertEquals(
            "A B4 is the highest note the tuner represents and must be tracked as the B3 string an " +
                "octave below it, in tune",
            stringIndexOf("B3"),
            instrument.selectedString.value,
        )
        assertEquals(
            "The B3 string an octave below the B4 must read in tune",
            0.0,
            checkNotNull(instrument.noteOffset.value),
            0.01,
        )

        val inTune = chromaticTuner()
        inTune.processPitch(frame(pitch = B4_HZ), 0.1f)
        assertEquals(
            "A B4 must read in tune with itself in chromatic mode",
            0.0,
            checkNotNull(inTune.noteOffset.value),
            0.01,
        )

        val sharp = chromaticTuner()
        sharp.processPitch(frame(pitch = B4_SHARP_CENTS_HZ), 0.1f)
        assertEquals(
            "A B4 five cents sharp is outside the range by a fraction of a semitone and must " +
                "still be tracked",
            0.05,
            checkNotNull(sharp.noteOffset.value),
            0.01,
        )
    }

    @Test
    fun testProcessPitchUnpitchedResetsTracker() {
        val tuner = newPipelineTuner()
        tuner.selectString(stringIndexOf("E4"))

        tuner.processPitch(frame(pitch = A4_HZ), 0.1f)
        assertEquals(5.0, tuner.noteOffset.value!!, 0.001)

        tuner.processPitch(unpitchedFrame(), 0.1f)
        assertNull("An unpitched frame must clear the needle", tuner.noteOffset.value)

        tuner.processPitch(frame(pitch = B3_HZ), 0.1f)
        assertEquals(
            "The frame after a silence must be reported on its own, not averaged with the old note",
            -5.0,
            tuner.noteOffset.value!!,
            0.001,
        )

        // A move of a single semitone is small enough to keep the median window of the previous
        // note, so the silence in between is what proves that the window was dropped.
        repeat(3) { tuner.processPitch(frame(pitch = A4_HZ), 0.1f) }
        tuner.processPitch(unpitchedFrame(), 0.1f)
        tuner.processPitch(frame(pitch = A_SHARP4_HZ), 0.1f)
        assertEquals(
            "The first frame of the new note must not be mixed with the window of the old one",
            6.0,
            tuner.noteOffset.value!!,
            0.001,
        )
    }

    /**
     * Creates a tuner whose tracker applies the level gate, the folding and the median of the
     * pipeline, with the range left open on both sides, which is what lets the other tests feed
     * notes the application would reject.
     */
    private fun newPipelineTuner() = Tuner(Tuning.STANDARD, PitchTracker())

    /** Creates a tuner in chromatic mode, where the tracker does not fold the octave error. */
    private fun chromaticTuner() = Tuner(Tuning.STANDARD).apply { setChromatic() }

    /** Builds a detection frame of the given [pitch], with an unknown clarity unless stated. */
    private fun frame(
        pitch: Float,
        probability: Float = -1f,
        isPitched: Boolean = true,
    ): PitchDetectionResult = PitchDetectionResult().apply {
        this.pitch = pitch
        this.probability = probability
        this.isPitched = isPitched
    }

    /** Builds the frame of a detector that reports no pitch at all. */
    private fun unpitchedFrame() = frame(pitch = -1f, probability = 0f, isPitched = false)

    /** Index of the string of [Tuning.STANDARD] whose root note is the given [note]. */
    private fun stringIndexOf(note: String): Int {
        val noteIndex = Notes.getIndex(note)
        return (0 until Tuning.STANDARD.numStrings()).first {
            Tuning.STANDARD.getString(it).getNoteIndex(0) == noteIndex
        }
    }

    private companion object {
        /** An A4, one octave above the highest string of the standard tuning. */
        const val A4_HZ = 440f

        /** A B3, the root of the second string of the standard tuning. */
        const val B3_HZ = 246.94f

        /** An A#4, a semitone above an A4. */
        const val A_SHARP4_HZ = 466.16f

        /** An E3, the second harmonic of the low E string. */
        const val E3_HZ = 164.8138f

        /** An E5, the second harmonic of the highest string of the standard tuning. */
        const val E5_HZ = 659.2551f

        /** A B4, the highest note of the range. */
        const val B4_HZ = 493.8833f

        /** A B4 five cents sharp, outside the range by a fraction of a semitone. */
        const val B4_SHARP_CENTS_HZ = 495.3f

        /** A B5, twelve semitones above [Tuner.HIGHEST_NOTE]. */
        const val B5_HZ = 987.7666f

        /** A B6, well above [Tuner.HIGHEST_NOTE]. */
        const val B6_HZ = 1975.5333f
    }
}