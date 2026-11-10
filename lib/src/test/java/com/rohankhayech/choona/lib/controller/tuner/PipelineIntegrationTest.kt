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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import be.tarsos.dsp.pitch.PitchDetectionResult
import com.rohankhayech.choona.lib.model.tuning.Notes
import com.rohankhayech.choona.lib.model.tuning.Tuning
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the whole audio pipeline of the tuner on a synthetic signal, on the host JVM.
 *
 * The chain is the one [TunerPipeline] builds, the same one [Tuner.start] runs —[SignalConditioner]
 * → [be.tarsos.dsp.pitch.FastYin] → [PitchTracker]— driven by a real
 * [be.tarsos.dsp.AudioDispatcher] fed by [FakeAudioStream] instead of the microphone, so no Android,
 * no device and no extra thread is involved: the stream reports the end of the audio, the
 * dispatcher closes and returns, and the test reads the frames the detector produced.
 *
 * The signals are the ones a tuner has to survive: a plucked string whose fundamental is weaker
 * than its octave, a bass note buried under the mains hum, and silence. They are generated from a
 * global sample index, so the phase runs continuously across the overlapping frames.
 *
 * The detector reports every frame by mutating **one** [be.tarsos.dsp.pitch.PitchDetectionResult]
 * instance, so the frames are copied as they arrive: keeping the reference would leave every stored
 * frame holding the values of the last one.
 *
 * @author Rohan Khayech
 */
class PipelineIntegrationTest {

    @Test
    fun testWeakFundamentalElectricGuitarResolvesToE2() {
        // The case of an electric guitar played without an amplifier: the fundamental of the low E
        // (82.41 Hz) arrives thirty times weaker than its second harmonic, which is the pitch of an
        // E3 and therefore no string. The third partial follows at half the level of the second.
        // The DC offset is what the high pass of the conditioner removes, and the noise is the
        // floor of the room. All three partials are harmonics of 82.41 Hz, so the signal is still
        // periodic at the period of the fundamental and the detector reads it: measured, every one
        // of the 29 frames reports 82.4 Hz with a clarity of 1.0, so neither the DC nor the
        // dominant harmonic move the reading off the string that is being played.
        val frames = capture(
            signal(
                partials = listOf(
                    Partial(E2_HZ, 0.02),
                    Partial(E3_HZ, 0.6),
                    Partial(3 * E2_HZ, 0.3),
                ),
                dc = DC_LEVEL,
                noiseRms = NOISE_RMS,
            ),
        ).frames

        val tracked = trackedFrames(frames, standardCandidates())
        val settled = tracked.takeLast(SETTLED_FRAMES)
        val offset = median(settled.map { it.second })

        assertEquals(
            "The low E must be attributed to the E2 string, but the offset settled at $offset " +
                "semitones from A4",
            E2_OFFSET,
            offset,
            0.15,
        )
        for ((frame, _) in settled) {
            assertTrue(
                "The frames of the settled note must be clear enough to move the needle, but one " +
                    "of them had a probability of ${frame.probability}",
                frame.probability >= 0.8f,
            )
        }
    }

    /**
     * Pins the mode of failure of a bass note under a mains hum that carries its own harmonics: the
     * tuner reports **no note** instead of an invented one.
     *
     * The conditioner notches 50 Hz and 60 Hz, which is enough for the hum itself: the companion
     * test below reads the same note at -40.999 semitones with the hum at 50 Hz louder than the
     * note, and loses it once the notches are removed. It is not enough for the harmonics of the
     * hum: 100 Hz and 150 Hz are not notched, and they are not harmonics of the note either —100 Hz
     * sits 3.4 semitones above the 82.41 Hz of its second partial, and 150 Hz 10.4 semitones above
     * it— so the signal is aperiodic for the detector.
     *
     * Measured on this signal, over the 29 frames of 1.5 s, with the harmonics of the hum at the
     * level a cable picks up next to a transformer (3, 6 and 9 dB below the second partial of the
     * note), [be.tarsos.dsp.pitch.FastYin] reports `isPitched = false` for **every** frame: its
     * `absoluteThreshold()` finds no lag whose normalised difference stays under
     * [TunerPipeline.YIN_THRESHOLD], so [PitchTracker] never sees a note and the tuner shows no
     * offset at all. The detection comes back once the harmonics are far enough below the note:
     *
     * - 3 dB, 6 dB and 9 dB below the second partial: 0 of 29 frames hold a note.
     * - 12 dB below: 25 of 29 frames, median offset -40.980 semitones.
     * - 18 dB below: 29 of 29 frames, median offset -40.990 semitones.
     * - 24 dB below: 29 of 29 frames, median offset -40.993 semitones.
     *
     * That silence is the safe half of the trade-off: the tuner cannot tune through such a hum, but
     * it never shows a note it did not hear, which is what would send the user to the wrong string.
     * Notching the harmonics of the hum instead is not free: the harmonic at 100 Hz sits 2 Hz
     * away from the 98 Hz of the G2 string, so a notch narrow enough to spare that string misses
     * the harmonic as soon as the mains drifts. Measured with the biquads of the conditioner, a
     * notch at Q = 25 is only 4 Hz wide at 100 Hz and still takes about 3 dB out of the G2, and
     * widening it to Q = 8 to cover the drift takes about 10 dB out of it. Detecting through the
     * harmonics is a decision about the conditioner, not about this test.
     *
     * The test therefore asserts the behaviour of today, frame by frame and end to end, so that a
     * future change of the conditioner is caught: if the mode of failure ever stops being safe —a
     * frame that holds a note under this hum— the tuner is inventing the offset and this test
     * fails. The three intensities are fixed here, not swept: a sweep would only make the test
     * slower.
     */
    @Test
    fun testE1BassUnderHumWithHarmonicsReportsNoNote() {
        for (harmonicDb in HUM_HARMONICS_DB) {
            // A bass string under a mains hum louder than the note itself, with a DC offset from
            // the electronics of the instrument. The hum sits at 50 Hz, inside the detection range
            // of the tuner, and its harmonics are at the level a cable picks up next to a
            // transformer.
            val frames = capture(
                signal(
                    partials = listOf(
                        Partial(E1_HZ, 0.15),
                        Partial(E2_HZ, 0.3),
                        Partial(MAINS_HZ, 0.3),
                        Partial(2 * MAINS_HZ, 0.3 * dbToRatio(harmonicDb)),
                        Partial(3 * MAINS_HZ, 0.3 * dbToRatio(harmonicDb)),
                    ),
                    dc = 0.3,
                    noiseRms = 5e-4,
                ),
            ).frames

            assertTrue(
                "The frames of the hum must reach the detector, or this test would pass on a " +
                    "signal the level gate of the tracker discards: ${frames.map { it.inputRms }}",
                frames.isNotEmpty() && frames.all { it.inputRms > PitchTracker.MIN_RMS },
            )
            val reported = frames.filter { it.isPitched }.map { it.pitch }
            assertTrue(
                "With the harmonics of the hum at $harmonicDb dB below the note the detector " +
                    "must find no period at all, but it reported $reported out of " +
                    "${frames.size} frames",
                reported.isEmpty(),
            )

            val tuner = Tuner(Tuning.STANDARD)
            for (frame in frames) {
                tuner.processPitch(
                    PitchDetectionResult().apply {
                        pitch = frame.pitch
                        probability = frame.probability
                        isPitched = frame.isPitched
                    },
                    frame.inputRms,
                )
            }
            assertNull(
                "The tuner must show no note instead of an invented one, but it settled at " +
                    "${tuner.noteOffset.value} semitones from A4",
                tuner.noteOffset.value,
            )
        }
    }

    @Test
    fun testE1BassSurvivesMainsHumFundamental() {
        // The same bass string under the same mains hum, without the harmonics of the hum: this is
        // the hum the conditioner is built to remove, at a level louder than the note itself. The
        // notches of the conditioner are what makes it detectable, which the control arm of the
        // test shows by removing them.
        val samples = signal(
            partials = listOf(
                Partial(E1_HZ, 0.15),
                Partial(E2_HZ, 0.3),
                Partial(MAINS_HZ, 0.3),
            ),
            dc = 0.3,
            noiseRms = 5e-4,
        )

        val frames = capture(samples).frames
        val offset = median(offsets(frames, null).takeLast(SETTLED_FRAMES))

        assertEquals(
            "The low E of the bass must be attributed to the E1 string under the mains hum, but " +
                "the offset settled at $offset semitones from A4",
            E1_OFFSET,
            offset,
            0.15,
        )
        val reported = frames.filter { it.isPitched }.map { it.pitch }
        assertTrue(
            "The 50 Hz of the mains must not be reported as a note, but the detector reported " +
                "$reported",
            reported.none { it > MAINS_HZ - HUMS_MARGIN_HZ && it < MAINS_HZ + HUMS_MARGIN_HZ },
        )

        val unNotched = offsets(capture(samples, notchFreqsHz = NO_NOTCHES).frames, null)
        assertTrue(
            "Without the notches of the conditioner the hum must swallow the note, but " +
                "${unNotched.size} of ${frames.size} frames were read: $unNotched",
            unNotched.size < frames.size / 2,
        )
        assertTrue(
            "Without the notches no frame may land on the low E, which is what the hum does to " +
                "the signal: $unNotched",
            unNotched.none { it.roundToInt() == Notes.getIndex("E1") },
        )
    }

    /**
     * Pins the sensitivity of the chain on the note the whole tuner exists for: a low E at the edge
     * of the noise of a room, at 10 dB SNR.
     *
     * The noise is scaled from the level of the fundamental, `amplitude / sqrt(2) / 10^(10/20)`, so
     * the signal to noise ratio is 10 dB in power. Measured on this chain, the detector reports all
     * **29** frames of the signal, both with the harmonics of a plucked string and with the
     * fundamental alone, and with [TunerPipeline.YIN_THRESHOLD] at 0.15 the clarity of the frames
     * runs from 0.850 to 0.960.
     *
     * Those two arms are why the test exists, and why [PitchTracker] carries no confidence gate of
     * its own:
     *
     * - The plucked string, six harmonics decaying roughly as `1/n` over the fundamental, is the
     *   case the tuner is played on: it pins the offset, since the detector resolves it to
     *   -28.971 semitones from A4.
     * - The fundamental alone is the tail of a decaying pluck, or the string a pickup does not
     *   capture the harmonics of, and it is the worst case of the detector: its clarity sits in
     *   `0.850..0.896`, right above the floor the detector enforces (`1 - 0.15 = 0.85`) and under
     *   the 0.90 of the gate this chain used to carry, which discarded **all 29** of its frames.
     *   With the threshold raised to 0.20 the clarity of the same signal falls to `0.80..0.89`,
     *   under the gate as well. A gate above the floor of the detector does not filter bad frames,
     *   it filters the note.
     *
     * The offset of the second arm is not asserted: without harmonic support the estimate of the
     * detector is biased about 0.9 semitones high, which is a property of the detector and not of
     * this chain, and pinning it would pin a number the tuner has no control over.
     */
    @Test
    fun testWeakFundamentalAtTenDbSnrIsDetected() {
        val amplitude = 0.2
        val plucked = capture(
            signal(
                partials = listOf(1.0, 0.8, 0.6, 0.4, 0.25, 0.15).mapIndexed { harmonic, level ->
                    Partial((harmonic + 1) * E2_HZ, amplitude * level)
                },
                noiseRms = amplitude / sqrt(2.0) / dbToRatio(SNR_DB),
            ),
        ).frames

        assertEquals(
            "Every frame of the signal must reach the detector, or a filter ahead of it is " +
                "swallowing the weak note",
            EXPECTED_FRAMES,
            plucked.size,
        )
        assertEquals(
            "At ${SNR_DB.toInt()} dB SNR the detector must hold a note on every frame of the " +
                "plucked low E, but it held one on ${plucked.count { it.isPitched }} of " +
                "${plucked.size}: a clarity gate above the floor of the detector, or a threshold " +
                "that raises it, is what loses the weak string",
            plucked.size,
            plucked.count { it.isPitched },
        )
        val tracked = offsets(plucked, standardCandidates())
        assertEquals(
            "Every frame of the plucked low E must reach the tracker as a note, but only " +
                "${tracked.size} of ${plucked.size} did",
            plucked.size,
            tracked.size,
        )
        val offset = median(tracked)
        assertEquals(
            "The low E must be attributed to the E2 string, but the offset settled at $offset " +
                "semitones from A4",
            E2_OFFSET,
            offset,
            0.15,
        )

        val fundamentalAlone = capture(
            signal(
                partials = listOf(Partial(E2_HZ, amplitude)),
                noiseRms = amplitude / sqrt(2.0) / dbToRatio(SNR_DB),
            ),
        ).frames

        assertEquals(
            "Every frame of the tone must reach the detector",
            EXPECTED_FRAMES,
            fundamentalAlone.size,
        )
        val clarity = fundamentalAlone.filter { it.isPitched }.map { it.probability }
        assertEquals(
            "The low E without its harmonics sits on the floor of the detector at ${SNR_DB.toInt()} " +
                "dB SNR, and every frame must still hold a note: it held " +
                "${clarity.size} of ${fundamentalAlone.size}, with a clarity of " +
                "${clarity.minOrNull()} to ${clarity.maxOrNull()}",
            fundamentalAlone.size,
            clarity.size,
        )
        assertEquals(
            "Every frame of that tone must reach the tracker as a note, which is what a clarity " +
                "gate above ${1f - TunerPipeline.YIN_THRESHOLD.toFloat()} takes away",
            fundamentalAlone.size,
            offsets(fundamentalAlone, standardCandidates()).size,
        )
    }

    @Test
    fun testNoteAtTheLevelOfTheOldGateIsDetected() {
        // The note of an electric guitar that is not plugged in, picked up by the microphone of a
        // phone from the faint acoustic sound of the string: a low E whose harmonics decay as 1/n,
        // at a frame level of 2e-4, which is -74 dBFS. The room on top of it is the one of a quiet
        // room, 1e-5.
        //
        // Measured on this chain, the detector is level independent —it normalises the difference of
        // each lag by the energy of the frame— and reports the E2 on **29 of 29** frames of this
        // signal. The conditioner leaves the frames between 1.96e-4 and 2.05e-4 (-74 dBFS), 6.0 dB
        // above the absolute floor of the gate (1e-4, -80 dBFS) and 14 dB under the -60 dBFS the
        // floor used to sit at: that floor published **none** of them, so on the phone it is the
        // difference between following the string and showing nothing at all.
        val amplitude = WEAK_NOTE_LEVEL / PARTIAL_RMS_FACTOR
        val frames = capture(
            signal(
                partials = (1..6).map { Partial(it * E2_HZ, amplitude / it) },
                noiseRms = QUIET_ROOM_RMS,
            ),
        ).frames

        assertEquals(
            "The signal must have the frames of every other one",
            EXPECTED_FRAMES,
            frames.size,
        )
        assertTrue(
            "Every frame must sit at the weak level this test exists for, but they measured " +
                "${frames.map { it.inputRms }}",
            frames.all { it.inputRms in WEAK_NOTE_BAND },
        )
        assertTrue(
            "Every frame must be under the -60 dBFS floor that used to discard them, but " +
                "${frames.count { it.inputRms >= OLD_MIN_RMS }} of them were not",
            frames.all { it.inputRms < OLD_MIN_RMS },
        )
        assertEquals(
            "The detector must hold the note on every frame, at any level: it held it on " +
                "${frames.count { it.isPitched }} of ${frames.size}",
            frames.size,
            frames.count { it.isPitched },
        )

        val tracked = offsets(frames, standardCandidates())
        assertEquals(
            "Every frame must reach the tracker as a note, but only ${tracked.size} of " +
                "${frames.size} did: the level gate is throwing the weak note away",
            frames.size,
            tracked.size,
        )
        val offset = median(tracked)
        assertEquals(
            "The weak low E must be attributed to the E2 string, but the offset settled at " +
                "$offset semitones from A4",
            E2_OFFSET,
            offset,
            0.15,
        )
    }

    /**
     * Pins that a weak note survives a detector that only reports it on every other frame.
     *
     * The frames the detector loses are not silent: they carry the level of the note, which is what
     * a noise floor learnt from them converges onto. Measured on the chain of the app, that floor
     * reaches the level of the note itself after **69 frames**, its gate then sits 6 dB above the
     * note and the note stops being published for good with the string held. The signal of this test
     * is long enough to reach that point —199 frames, half of them lost— and the -60 dBFS floor of
     * HEAD discarded the same note altogether, on all 29 frames of the shorter signal of
     * [testNoteAtTheLevelOfTheOldGateIsDetected]. The note must therefore be published on every one
     * of the 100 frames that carry it, and the last 30 of them are the ones a floor learnt from the
     * room takes away.
     */
    @Test
    fun testWeakNoteIsPublishedWhileTheDetectorDropsEveryOtherFrame() {
        val amplitude = WEAK_NOTE_LEVEL / PARTIAL_RMS_FACTOR
        val frames = capture(
            signal(
                partials = (1..6).map { Partial(it * E2_HZ, amplitude / it) },
                noiseRms = QUIET_ROOM_RMS,
                samples = BLINKING_SAMPLE_COUNT,
            ),
        ).frames
        val noteFrames = frames.filterIndexed { index, _ -> index % 2 == 0 }

        val published = publishedNotes(blinking(frames))

        assertEquals(
            "The signal must be long enough for the frames the detector drops to outnumber the 69 " +
                "a noise floor takes to close over the note, but it has ${frames.size} frames",
            BLINKING_BLOCKS - WARMUP_BLOCKS,
            frames.size,
        )
        assertEquals(
            "Every frame that carries the weak note must reach the needle, but the tuner published " +
                "${published.size} of ${noteFrames.size} frames, and the note frames it lost were " +
                "the last ones",
            noteFrames.size,
            published.size,
        )
        assertTrue(
            "The weak note must never be published as another string, but the tuner showed the " +
                "strings ${published.map { it.string }.distinct()}",
            published.all { it.string == stringIndexOf("E2") },
        )
        assertTrue(
            "The weak note must read in tune with the E2 string, but the offsets published were " +
                "${published.map { it.offset }}",
            published.all { abs(it.offset) <= WEAK_NOTE_TOLERANCE },
        )
    }

    @Test
    fun testSilenceProducesNoPitch() {
        // Under the absolute floor of the level gate: the frames never reach the detector, so
        // nothing can be invented.
        val quiet = capture(signal(partials = emptyList(), noiseRms = dbToRms(-90.0))).frames
        assertTrue(
            "A frame under ${PitchTracker.MIN_RMS} must not reach the detector, but it reported " +
                "${quiet.map { it.inputRms }}",
            quiet.all { it.inputRms <= PitchTracker.MIN_RMS },
        )
        assertTrue(
            "A quiet room must not produce an offset, but the tracker reported " +
                "${offsets(quiet, null)}",
            offsets(quiet, null).isEmpty(),
        )

        // Above the absolute floor the frames do reach the detector, and it is the detector which
        // rejects them, through its isPitched flag: a noise has no period to lock onto, so it
        // reports no pitch, and the level gate is not what saves this case. The three levels are the
        // noise of a quiet room (-70 dBFS), the noise of a room at -60 dBFS and a room 20 dB
        // louder than that (-40 dBFS): measured, the detector reports no pitch on any frame of the
        // three.
        for (levelDb in NOISE_LEVELS_DB) {
            val noisy = capture(signal(partials = emptyList(), noiseRms = dbToRms(levelDb))).frames
            assertTrue(
                "The noise at $levelDb dBFS must reach the detector, but no frame was that loud: " +
                    "${noisy.map { it.inputRms }}",
                noisy.any { it.inputRms > PitchTracker.MIN_RMS },
            )
            assertFalse(
                "The detector must reject the noise at $levelDb dBFS, but it reported " +
                    "${noisy.filter { it.isPitched }.map { it.pitch }}",
                noisy.any { it.isPitched },
            )
            assertTrue(
                "The noise at $levelDb dBFS must not produce an offset, but the tracker reported " +
                    "${offsets(noisy, null)}",
                offsets(noisy, null).isEmpty(),
            )
        }
    }

    /**
     * Pins that a room that is not noise cannot be kept out by the level of the frame.
     *
     * A fan, a television or the harmonic of the mains carries a period, so the detector does not
     * fail on it and reports it as a note: measured on this chain, the 120 Hz of a fan at the level of
     * an audible room is reported on **29 of 29** frames and reaches the tracker, which publishes it
     * as an offset. That is the reason a floor learnt from the room cannot replace the detector: no
     * threshold on the level of a frame can tell such a frame apart from a string that is being
     * played, so the floor would have to sit above the room, which is above the weakest note of an
     * unplugged guitar.
     */
    @Test
    fun testTonalRoomIsReportedAsANote() {
        val frames = capture(
            signal(partials = listOf(Partial(TONAL_ROOM_HZ, TONAL_ROOM_AMPLITUDE))),
        ).frames
        val reported = frames.filter { it.isPitched }

        assertTrue(
            "The frames of the tonal room must reach the detector, but no frame was that loud",
            frames.any { it.inputRms > PitchTracker.MIN_RMS },
        )
        assertEquals(
            "The detector must report the tonal room as a note it cannot tell from a string, but " +
                "it held ${reported.size} of ${frames.size} frames",
            frames.size,
            reported.size,
        )
        assertTrue(
            "The tonal room must reach the needle, since no gate of the chain can filter it, but " +
                "the tracker reported ${offsets(frames, standardCandidates())}",
            offsets(frames, standardCandidates()).isNotEmpty(),
        )
    }

    @Test
    fun testDetectedPitchTableDriven() {
        // Frequencies with a whole number of samples per period (196.0, 98.0, 147.0 and 294.0 Hz
        // at 44100 Hz) are avoided on purpose: FastYin reports isPitched = false for them by a
        // known numerical artefact, which says nothing about the tuner.
        val notes = listOf(
            "E1" to E1_HZ,
            "E2" to E2_HZ,
            "A2" to A2_HZ,
            "D3" to D3_HZ,
            "G3" to G3_HZ,
            "B3" to B3_HZ,
            "E4" to E4_HZ,
            "A4" to A4_HZ,
        )

        for ((note, frequency) in notes) {
            val frames = capture(
                signal(
                    partials = listOf(
                        Partial(frequency, 1.0),
                        Partial(2 * frequency, 0.5),
                        Partial(3 * frequency, 0.25),
                    ),
                    noiseRms = NOISE_RMS,
                ),
            ).frames

            val tracked = offsets(frames, null)
            val measured = median(tracked)

            assertEquals(
                "$note ($frequency Hz) must be detected in tune, but the median offset of " +
                    "${tracked.size} frames was $measured semitones from A4",
                Notes.getIndex(note).toDouble(),
                measured,
                MAX_OFFSET_ERROR,
            )
            assertTrue(
                "At least ${MIN_VALID_FRACTION * 100}% of the frames of $note ($frequency Hz) " +
                    "must hold a note, but only ${tracked.size} of ${frames.size} did",
                tracked.size >= frames.size * MIN_VALID_FRACTION,
            )
        }
    }

    /**
     * Pins that an octave error on the B3 string —the only one of the tuning whose double is a note
     * the tuner can represent— never reaches the needle as a note of another string.
     *
     * The signal is the failure mode of the whole chain: the fundamental of the string and its odd
     * harmonics sit 12 dB under the even ones, which is what a pickup does to a string plucked over
     * the neck, and the detector locks onto the second harmonic. Measured on this chain, all 29
     * frames report the 494.1 to 495.6 Hz of the B4, which is inside the range the tuner represents,
     * so no gate of the chain rejects it.
     *
     * The raw offset of those frames is a note of the tuning of its own —the double of the B3 is the
     * B4, two semitones above A4 and well inside the range— so publishing it would move the display
     * to the E4 string, the closest one of standard tuning, with the needle at the end of its
     * travel: the median cannot smooth it either, since a change of note clears it. The frame
     * publishes the folded offset instead, from the frame that asks for it, and the tuner settles on
     * the string being played, in tune.
     *
     * The same signal with the odd harmonics only 6 dB under the even ones is the control arm: the
     * detector reports the 246.92 to 246.96 Hz of the B3 itself on all 29 frames and the tuner shows
     * the B3 in tune from the first one, which is what makes the arm above an octave error and not
     * a signal the chain cannot read.
     */
    @Test
    fun testOctaveErrorOfB3IsNeverPublishedAsAnotherString() {
        val control = capture(octaveErrorSignal(B3_HZ, CONTROL_ODD_HARMONICS_DB)).frames
        assertEquals(
            "At ${CONTROL_ODD_HARMONICS_DB.toInt()} dB the detector must still report the " +
                "fundamental of the B3, or this test is not measuring the octave error",
            B3_HZ,
            median(control.map { it.pitch.toDouble() }),
            0.1,
        )

        val frames = capture(octaveErrorSignal(B3_HZ, ODD_HARMONICS_DB)).frames
        val reported = frames.filter { it.isPitched }.map { it.pitch }
        assertEquals(
            "Every frame must hold a note, or the octave error is not what this test measures: " +
                "$reported",
            frames.size,
            reported.size,
        )
        assertTrue(
            "Every frame must report the octave above the B3 string, but the detector reported " +
                "$reported",
            reported.all { it in DOUBLE_B3_BAND },
        )

        val published = publishedNotes(frames)

        assertTrue(
            "The folded note must reach the needle, but the tuner never published an offset",
            published.isNotEmpty(),
        )
        assertTrue(
            "A frame asking for the folding must never be published as the note of another " +
                "string, but the tuner showed the notes $published",
            published.all { it.string == stringIndexOf("B3") },
        )
        assertTrue(
            "The B3 string must be in tune and not pegged at the end of the needle, but the " +
                "offsets published were ${published.map { it.offset }}",
            published.all { abs(it.offset) <= PitchTracker.FOLD_TOLERANCE },
        )
        assertEquals(
            "The tuner must have selected the B3 string at the end of the signal, but it " +
                "selected the string ${published.last().string}",
            stringIndexOf("B3"),
            published.last().string,
        )
    }

    /**
     * Pins the octave error of the B3 against a detector that drops every other frame.
     *
     * A tuner that withholds the frames that ask for the folding instead of publishing them loses
     * the note as soon as the detector flickers: the withheld frame is followed by a reset that
     * throws the confirmation away, so the folding is never confirmed and the note is never shown.
     * Measured on this signal with half of its frames dropped, the withheld frames published **0 of
     * the 15** frames that carry the note. With the folding published on the frame that asks for it,
     * the B3 reaches the needle on all 15.
     */
    @Test
    fun testOctaveErrorOfB3IsFoldedWhileTheDetectorDropsEveryOtherFrame() {
        val frames = capture(octaveErrorSignal(B3_HZ, ODD_HARMONICS_DB)).frames
        val reported = frames.filterIndexed { index, frame -> index % 2 == 0 && frame.isPitched }

        val published = publishedNotes(blinking(frames))

        assertTrue(
            "The signal must carry the octave error on the frames that report it, but only " +
                "${reported.size} of ${frames.size} do",
            reported.size >= MIN_BLINKING_FRAMES,
        )
        assertTrue(
            "The octave error must reach the needle while the detector drops half of the frames, " +
                "but the tuner published ${published.size} of ${reported.size} frames",
            published.size >= MIN_BLINKING_FRAMES,
        )
        assertTrue(
            "The folded note must never be published as another string, but the tuner showed the " +
                "strings ${published.map { it.string }}",
            published.all { it.string == stringIndexOf("B3") },
        )
        assertTrue(
            "The folded note must read in tune with the B3 string, but the offsets published were " +
                "${published.map { it.offset }}",
            published.all { abs(it.offset) <= PitchTracker.FOLD_TOLERANCE },
        )
    }

    /**
     * Sweeps every string of the tuning with an octave error forced on it, and pins that the string
     * the tuner selects is always the string of the note, from the first frame it publishes.
     *
     * This is the general form of the failure the B3 pins. The detector locks onto the second
     * harmonic of the string, and the note it reports is a note of no string of the tuning, so
     * publishing the raw offset of a frame that asks for the folding hands the display a note
     * closer to a string that is not the one being played: measured, the raw offsets of the E3, A3,
     * D4, G4, B4 and E5 of the six strings land 2.02, 1.98, 1.98, 3.02, 7.03 and 12.02 semitones
     * away from the closest string of the tuning, which is the D3, the B3, the E4, the E4, the E4
     * and —past the ceiling of the range— the E4 as well. The last one is the case the folding and
     * the range gate share: the raw offset of the E5 is out of range and would be discarded on every
     * frame, while the folded one is the E4 string itself, which is why the folding is applied before
     * the range gate reads the offset.
     */
    @Test
    fun testForcedOctaveErrorNeverSelectsAnotherString() {
        for (note in OPEN_STRINGS) {
            val own = Notes.getPitch(Notes.getIndex(note))
            val frames = capture(octaveErrorSignal(own, ODD_HARMONICS_DB)).frames
            val doubled = frames.filter { it.isPitched }.map { it.pitch }
            assertTrue(
                "The detector must report the octave above the $note string, but it reported " +
                    "$doubled",
                doubled.isNotEmpty() && doubled.all { it > own * 1.8 },
            )

            val published = publishedNotes(frames)
            val expected = stringIndexOf(note)

            assertTrue(
                "The octave error of the $note string must be folded and shown, but the tuner " +
                    "published no offset",
                published.isNotEmpty(),
            )
            assertTrue(
                "The octave error of the $note string must never be shown as a note of another " +
                    "string, but the tuner showed the notes $published",
                published.all { it.string == expected },
            )
            assertTrue(
                "The $note string must end up in tune, but the offsets published were " +
                    "${published.map { it.offset }}",
                published.all { abs(it.offset) <= PitchTracker.FOLD_TOLERANCE },
            )
        }
    }

    @Test
    fun testConditionerRunsBeforeDetector() {
        val result = capture(signal(partials = listOf(Partial(A4_HZ, 0.5)), noiseRms = NOISE_RMS))

        assertEquals(
            "Every frame but the two of the warm up must reach the detector",
            result.blocksProcessed - WARMUP_BLOCKS,
            result.frames.size,
        )
        for (frame in result.frames) {
            assertFalse(
                "The level of the frame must be published before the detector reads it, but one " +
                    "frame reported ${frame.inputRms}",
                frame.inputRms.isNaN() || frame.inputRms <= 0f,
            )
        }
    }

    @Test
    fun testPipelineAddsConditionerFirst() {
        // The chain is built in one place only, TunerPipeline, precisely so that inverting the
        // order of its stages breaks this test. The dispatcher keeps its processors private, so the
        // order is asserted through what only a chain in the right order produces: every frame the
        // detector reports carries the level of its own frame, already published by the
        // conditioner, and the frames of the warm up of the conditioner never reach the detector.
        // With the detector added first it would analyse the empty buffer of the conditioner, with
        // no level published yet, and the warm up would not be skipped at all.
        val result = capture(signal(partials = listOf(Partial(E2_HZ, 0.5))))

        assertTrue(
            "The pipeline must report frames, but it reported none",
            result.frames.isNotEmpty(),
        )
        assertEquals(
            "The conditioner must run before the detector on every frame: the two frames it " +
                "reports as warm up must not reach the detector",
            result.blocksProcessed - WARMUP_BLOCKS,
            result.frames.size,
        )
        val first = result.frames.first()
        assertFalse(
            "The first frame the detector sees must already carry the level the conditioner " +
                "publishes for it, but it was ${first.inputRms}",
            first.inputRms.isNaN() || first.inputRms <= 0f,
        )
    }

    @Test
    fun testStreamIsClosedAfterRun() {
        // The dispatcher closes the stream when the audio ends, which is what releases the
        // AudioRecord of the production stream.
        assertTrue(
            "The dispatcher must close the stream once the audio is over",
            capture(signal(partials = listOf(Partial(E2_HZ, 0.5)))).streamClosed,
        )
    }

    /** A note the tuner published: the string it selected and the offset it showed, in semitones. */
    private data class PublishedNote(val string: Int, val offset: Double)

    /** A partial of a synthetic tone: a [frequencyHz] with an [amplitude]. */
    private data class Partial(val frequencyHz: Double, val amplitude: Double)

    /** A frame of the detector, copied as it is reported. */
    private data class Frame(
        val pitch: Float,
        val probability: Float,
        val isPitched: Boolean,
        val inputRms: Float,
    )

    /** Frames the pipeline reported, and what happened to the stream that fed it. */
    private class Capture(
        val frames: List<Frame>,
        val blocksProcessed: Int,
        val streamClosed: Boolean,
    )

    /**
     * Builds [SAMPLE_COUNT] samples of a tone made of [partials], on top of a [dc] offset and of a
     * white noise of the given [noiseRms] level.
     *
     * The phase is taken from the global sample index, so it never restarts at a frame boundary.
     */
    private fun signal(
        partials: List<Partial>,
        dc: Double = 0.0,
        noiseRms: Double = 0.0,
        samples: Int = SAMPLE_COUNT,
    ): DoubleArray {
        val random = Random(NOISE_SEED)
        val noisePeak = noiseRms * sqrt(3.0)
        return DoubleArray(samples) { index ->
            var value = dc
            for (partial in partials) {
                value += partial.amplitude *
                    sin(2.0 * PI * partial.frequencyHz * index / TunerPipeline.SAMPLE_RATE)
            }
            if (noiseRms > 0) {
                value += random.nextDouble(-noisePeak, noisePeak)
            }
            value
        }
    }

    /**
     * Runs the pipeline of [Tuner.start] over [samples] and collects the frames of the detector.
     *
     * The chain is the one [TunerPipeline] builds, which is the one the tuner runs: this test does
     * not assemble a chain of its own, so a change in the order of its stages is exercised here.
     *
     * The stream is fed a whole number of blocks and reports the end of the audio right after the
     * last one, so no frame is zero padded and [be.tarsos.dsp.AudioDispatcher.run] returns on the
     * calling thread, which makes the test synchronous.
     *
     * @param samples Signal to run through the pipeline.
     * @param notchFreqsHz Hum notches of the conditioner, the ones of the tuner by default.
     */
    private fun capture(
        samples: DoubleArray,
        notchFreqsHz: DoubleArray = SignalConditioner.DEFAULT_NOTCH_FREQUENCIES_HZ,
    ): Capture {
        val stream = FakeAudioStream.finite(toPcm16(samples))
        val frames = mutableListOf<Frame>()

        val dispatcher = TunerPipeline.createDispatcher(stream, notchFreqsHz) { result, inputRms ->
            frames += Frame(
                pitch = result.pitch,
                probability = result.probability,
                isPitched = result.isPitched,
                inputRms = inputRms,
            )
        }
        dispatcher.run()

        val blocksProcessed = (samples.size - TunerPipeline.BUFFER_SIZE) / STEP_SIZE + 1
        return Capture(frames, blocksProcessed, stream.closed)
    }

    /** Converts [samples] to PCM16, saturating the values outside the range of the format. */
    private fun toPcm16(samples: DoubleArray): ShortArray =
        ShortArray(samples.size) { (samples[it].coerceIn(-1.0, 1.0) * PCM16_PEAK).roundToInt().toShort() }

    /** Passes [frames] through a fresh tracker and keeps the ones that hold a note. */
    private fun trackedFrames(frames: List<Frame>, candidates: IntArray?): List<Pair<Frame, Double>> {
        val tracker = PitchTracker()
        return frames.mapNotNull { frame ->
            tracker.process(frame.pitch, frame.inputRms, candidates)
                ?.let { frame to it }
        }
    }

    /**
     * The same frames with the detector reporting the note on every other one.
     *
     * The frames it loses are not silent: their level is the level of the note, which is what a
     * noise floor learnt from them converges onto. Only the flag changes, so the signal, the level
     * and the frequency of each frame stay the ones the pipeline measured.
     */
    private fun blinking(frames: List<Frame>): List<Frame> =
        frames.mapIndexed { index, frame ->
            if (index % 2 == 0) frame else frame.copy(isPitched = false)
        }

    /**
     * Drives a [Tuner] wired as the app wires it with [frames], and returns every note it published,
     * with the string it selected at that moment.
     *
     * The tuner is the real one, not a tracker: the string is what the display shows, and it is the
     * part of the failure that a tracker alone cannot show.
     */
    private fun publishedNotes(frames: List<Frame>): List<PublishedNote> {
        val tuner = Tuner(Tuning.STANDARD)
        val published = mutableListOf<PublishedNote>()

        for (frame in frames) {
            tuner.processPitch(
                PitchDetectionResult().apply {
                    pitch = frame.pitch
                    probability = frame.probability
                    isPitched = frame.isPitched
                },
                frame.inputRms,
            )
            tuner.noteOffset.value?.let {
                published += PublishedNote(tuner.selectedString.value, it)
            }
        }
        return published
    }

    /**
     * Signal of a string that the detector hears an octave high: the fundamental and the odd
     * harmonics [oddAttenuationDb] under the even ones, which is the shape of a string plucked over
     * the neck of the guitar.
     */
    private fun octaveErrorSignal(frequencyHz: Double, oddAttenuationDb: Double): DoubleArray {
        val even = 0.5
        val odd = even * dbToRatio(-oddAttenuationDb)
        return signal(
            partials = listOf(
                Partial(frequencyHz, odd),
                Partial(2 * frequencyHz, even),
                Partial(3 * frequencyHz, odd / 2),
                Partial(4 * frequencyHz, even / 2),
            ),
            noiseRms = QUIET_ROOM_RMS,
        )
    }

    /** Index of the string of the standard tuning that plays [note], as the tuner numbers them. */
    private fun stringIndexOf(note: String): Int {
        val tuning = Tuning.STANDARD
        val index = Notes.getIndex(note)
        return (0 until tuning.numStrings()).first { tuning.getString(it).getNoteIndex(0) == index }
    }

    /** Offsets from A4 reported by the tracker for [frames]. */
    private fun offsets(frames: List<Frame>, candidates: IntArray?): List<Double> =
        trackedFrames(frames, candidates).map { it.second }

    /** Note indices of the strings of the standard tuning, as the tuner passes them to the tracker. */
    private fun standardCandidates(): IntArray {
        val tuning = Tuning.STANDARD
        return IntArray(tuning.numStrings()) { tuning.getString(it).getNoteIndex(0) }
    }

    /** Median of [values], as the tracker reduces its window. */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** Level in RMS of a signal of the given level in dBFS. */
    private fun dbToRms(db: Double): Double = 10.0.pow(db / 20.0)

    /** Linear amplitude of a level given in dB relative to a unit amplitude. */
    private fun dbToRatio(db: Double): Double = 10.0.pow(db / 20.0)

    private companion object {
        /** Samples a frame advances over the previous one. */
        const val STEP_SIZE = TunerPipeline.BUFFER_SIZE - TunerPipeline.BUFFER_OVERLAP

        /** Frames the conditioner discards while its notches ring. */
        const val WARMUP_BLOCKS = 2

        /** Blocks of the synthetic signals, roughly 1.5 s of audio. */
        const val BLOCKS = 31

        /** Samples fed to the pipeline: a whole number of blocks, with no zero padding at the end. */
        const val SAMPLE_COUNT =
            TunerPipeline.BUFFER_SIZE + (BLOCKS - 1) * STEP_SIZE

        /** Frames taken at the end of the signal, once the tracker has settled on the note. */
        const val SETTLED_FRAMES = 5

        /** Frames the pipeline reports for one of the synthetic signals. */
        const val EXPECTED_FRAMES = BLOCKS - WARMUP_BLOCKS

        /** Fraction of the frames of a steady tone that must hold a note. */
        const val MIN_VALID_FRACTION = 0.8

        /** Largest error accepted between the offset of a steady tone and the note it plays. */
        const val MAX_OFFSET_ERROR = 0.05

        /** Signal to noise ratio of the weak low E, in dB, over the noise of the room. */
        const val SNR_DB = 10.0

        /** Distance within which a reported pitch would be taken as the mains hum, in Hz. */
        const val HUMS_MARGIN_HZ = 1.0f

        /** Peak of the 16 bit signed samples the stream serves. */
        const val PCM16_PEAK = 32767.0

        /** Seed of the synthetic noise, which makes every run identical. */
        const val NOISE_SEED = 20260201

        /** Level of the noise floor of the synthetic signals. */
        const val NOISE_RMS = 1e-4

        /** Level of the room on top of the weak signals, the one of a room that is quiet. */
        const val QUIET_ROOM_RMS = 1e-5

        /**
         * Level in RMS of the weak note of an unplugged electric guitar, -74 dBFS: the acoustic
         * sound of the string reaching the microphone of a phone.
         */
        const val WEAK_NOTE_LEVEL = 2e-4

        /**
         * Ratio between the amplitude of a tone whose harmonics decay as `1/n` and the RMS of the
         * signal it produces, `sqrt(sum(1/n^2) / 2)` for `n` up to 6.
         */
        val PARTIAL_RMS_FACTOR = sqrt((1..6).sumOf { 1.0 / (it * it) } / 2.0)

        /** Band the frames of the weak note measure in, around [WEAK_NOTE_LEVEL]. */
        val WEAK_NOTE_BAND = 1e-4..1e-3

        /**
         * Largest distance from the E2 string accepted for the note of an unplugged electric guitar,
         * in semitones.
         */
        const val WEAK_NOTE_TOLERANCE = 0.15

        /**
         * Frames of the weak note and of the octave error that must still be published when the
         * detector only reports the note on half of them.
         */
        const val MIN_BLINKING_FRAMES = 10

        /**
         * Blocks of the signal in which the detector drops every other frame, roughly 9.4 s of
         * audio: long enough for the frames it drops to outnumber the 69 a noise floor learnt from
         * them takes to close the gate over the note.
         */
        const val BLINKING_BLOCKS = 201

        /** Samples of that signal: a whole number of blocks, with no zero padding at the end. */
        const val BLINKING_SAMPLE_COUNT =
            TunerPipeline.BUFFER_SIZE + (BLINKING_BLOCKS - 1) * STEP_SIZE

        /** Frequency in Hz of the tone of a fan, the room that no level gate can tell from a string. */
        const val TONAL_ROOM_HZ = 120.0

        /** Amplitude of the tone of the fan, which puts the level of the frame at -34 dBFS. */
        const val TONAL_ROOM_AMPLITUDE = 0.0283

        /**
         * Floor the level gate used to sit at, -60 dBFS, which discarded the whole tail of the
         * weak note: the absolute floor of [PitchTracker] is now -80 dBFS.
         */
        const val OLD_MIN_RMS = 1e-3f

        /**
         * Levels in dBFS of the noise of the room the tuner must not turn into a note: a quiet room,
         * the room the old floor learned from, and a room 20 dB louder than that one.
         */
        val NOISE_LEVELS_DB = listOf(-70.0, -60.0, -40.0)

        /**
         * Attenuation in dB of the odd harmonics of a string plucked over the neck of the guitar,
         * relative to the even ones, which is what makes the detector lock onto the octave above.
         */
        const val ODD_HARMONICS_DB = 12.0

        /**
         * Attenuation in dB of the odd harmonics at which the detector still reports the
         * fundamental of the B3 itself, which is the control arm of the octave error test.
         */
        const val CONTROL_ODD_HARMONICS_DB = 6.0

        /** Band the report of the detector falls in when it locks onto the octave above the B3. */
        val DOUBLE_B3_BAND = 493f..496f

        /** Strings of the standard tuning, from the lowest to the highest. */
        val OPEN_STRINGS = listOf("E2", "A2", "D3", "G3", "B3", "E4")

        /** DC offset the electronics of an instrument add to the signal. */
        const val DC_LEVEL = 0.05

        /** Frequency of the mains hum, the one of the region the tuner is used in. */
        const val MAINS_HZ = 50.0

        /**
         * Levels of the harmonics of the hum, in dB below the second partial of the note, at which
         * the detector is measured to find no period at all and the tuner therefore shows no note.
         */
        val HUM_HARMONICS_DB = listOf(-3.0, -6.0, -9.0)

        /** Notch configuration that leaves the mains hum untouched, for the control arm of a test. */
        val NO_NOTCHES = doubleArrayOf()

        const val E1_HZ = 41.2
        const val E2_HZ = 82.41
        const val E3_HZ = 164.81
        const val A2_HZ = 110.0
        const val D3_HZ = 146.83
        const val G3_HZ = 196.3
        const val B3_HZ = 246.94
        const val E4_HZ = 329.63
        const val A4_HZ = 440.0

        /** Offset from A4 of the strings the low signals are expected to be attributed to. */
        val E1_OFFSET = Notes.getIndex("E1").toDouble()
        val E2_OFFSET = Notes.getIndex("E2").toDouble()
    }
}
