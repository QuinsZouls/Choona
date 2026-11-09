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

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import be.tarsos.dsp.pitch.PitchDetectionResult
import com.rohankhayech.choona.lib.model.error.TunerException
import com.rohankhayech.choona.lib.model.tuning.Notes
import com.rohankhayech.choona.lib.model.tuning.Tuning
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers the lifecycle of [Tuner.start] and [Tuner.stop] on the host JVM.
 *
 * The two things the tuner owns while it runs are the audio stream and the dispatcher that walks the
 * analysis chain over it, and the microphone is only released by stopping the dispatcher: the stream
 * belongs to it from the moment it is built. Neither can be observed from the outside through the
 * states the tuner publishes, so this suite drives the lifecycle through the two test seams of the
 * class —the factory of the source and the factory of the dispatcher— and asserts the ownership on
 * the [FakeAudioStream] the tuner was given, with no Android and no device involved.
 *
 * What it pins:
 *
 * - A start that fails outside the capture —the chain cannot be built— must close the stream it
 *   opened before failing, or the `AudioRecord` leaks and the microphone stays busy for the whole
 *   process. The failed start must also leave the tuner usable.
 * - A start that fails **before** the capture —the microphone cannot be opened— must report that
 *   error as it is, without wrapping it, and must not leave anything half built behind.
 * - A stop must release the stream and end the audio thread, exactly once, and be safe to repeat.
 * - Neither may leave the tuner half started: the state that [Tuner.error] and [Tuner.isRunning]
 *   publish after each of them is asserted too.
 * - The chain the app really runs —the defaults of the class, with no test factory in between— must
 *   notch the mains hum: the bass note under a hum is heard through them and vanishes without them.
 *
 * @author Rohan Khayech
 */
class TunerLifecycleTest {

    @Test
    fun startTwiceThrowsIllegalStateException() {
        val fixture = Fixture()

        fixture.tuner.startWithPermissionCheck { true }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                fixture.tuner.startWithPermissionCheck { true }
            }
            assertEquals(
                "The second start must be refused before it opens anything",
                "Tuner already started.",
                error.message,
            )
        } finally {
            fixture.tuner.stop()
        }

        assertFalse("The tuner must be stopped once stop() has returned", fixture.tuner.isRunning)
        assertEquals(
            "The refused start must not open a second stream, but the factory was called twice",
            1,
            fixture.sourceCalls,
        )
        assertEquals(
            "The refused start must not build a second chain",
            1,
            fixture.dispatcherCalls,
        )
        assertEquals(
            "The stream of the first start must be closed by the stop in the finally block",
            1,
            fixture.streams.single().closeCount,
        )
    }

    @Test
    fun startWithoutPermissionThrowsAndDoesNotStart() {
        val fixture = Fixture()

        val error = assertThrows(IllegalStateException::class.java) {
            fixture.tuner.startWithPermissionCheck { false }
        }

        assertEquals(
            "The refusal must report the missing permission",
            "RECORD_AUDIO permission not granted.",
            error.message,
        )
        assertFalse(
            "A refused start must not leave the tuner running",
            fixture.tuner.isRunning,
        )
        assertEquals(
            "The microphone must not be opened without the permission, but the source factory " +
                "was called ${fixture.sourceCalls} times",
            0,
            fixture.sourceCalls,
        )
        assertEquals(
            "The chain must not be built without the permission",
            0,
            fixture.dispatcherCalls,
        )
        assertTrue(
            "No stream may be opened without the permission, but the tuner holds " +
                "${fixture.streams.size}",
            fixture.streams.isEmpty(),
        )
        assertNull(
            "A missing permission is not an error of the capture, but the tuner reported " +
                "${fixture.tuner.error.value}",
            fixture.tuner.error.value,
        )
    }

    @Test
    fun stopStopsTheDispatcherAndClosesTheStream() {
        val fixture = Fixture()
        fixture.tuner.startWithPermissionCheck { true }
        val stream = fixture.streams.single()

        awaitUntil("the audio thread to read the first blocks") {
            stream.blocksRead >= BLOCKS_BEFORE_STOP
        }
        val audioThread = stream.reader

        fixture.tuner.stop()

        assertTrue(
            "stop() must close the stream, which is what releases the microphone",
            stream.closed,
        )
        assertEquals(
            "The stream must be closed exactly once, but it was closed ${stream.closeCount} times",
            1,
            stream.closeCount,
        )
        assertFalse("The tuner must not be running after stop()", fixture.tuner.isRunning)
        assertNull(
            "A clean stop must not report an error, but the tuner reported " +
                "${fixture.tuner.error.value}",
            fixture.tuner.error.value,
        )
        assertNotNull("The stream must have been read by the audio thread", audioThread)
        awaitUntil("the audio thread of the dispatcher to finish") {
            audioThread?.isAlive != true
        }
    }

    @Test
    fun stopIsIdempotent() {
        val fixture = Fixture()
        fixture.tuner.startWithPermissionCheck { true }
        val stream = fixture.streams.single()
        awaitUntil("the audio thread to read the first blocks") {
            stream.blocksRead >= BLOCKS_BEFORE_STOP
        }

        fixture.tuner.stop()
        fixture.tuner.stop()
        fixture.tuner.stop()

        assertTrue("The stream must be closed by the first stop()", stream.closed)
        assertEquals(
            "Only the first stop() may release the stream, but it was released " +
                "${stream.closeCount} times",
            1,
            stream.closeCount,
        )
        assertFalse("The tuner must not be running after stop()", fixture.tuner.isRunning)
    }

    @Test
    fun stopWithoutStartIsNoOp() {
        val fixture = Fixture()

        fixture.tuner.stop()

        assertFalse("A tuner that never started must not be running", fixture.tuner.isRunning)
        assertEquals(
            "stop() must not open anything",
            0,
            fixture.sourceCalls,
        )
        assertTrue(
            "stop() must not open a stream, but the tuner holds ${fixture.streams.size}",
            fixture.streams.isEmpty(),
        )
        assertNull(
            "stop() must not report an error, but the tuner reported ${fixture.tuner.error.value}",
            fixture.tuner.error.value,
        )
    }

    @Test
    fun failedStartReleasesTheStreamAndReportsTheError() {
        val fixture = Fixture()
        fixture.createDispatcher = { _, _ -> throw IllegalStateException(BOOM) }

        val error = assertThrows(TunerException::class.java) {
            fixture.tuner.startWithPermissionCheck { true }
        }

        val failed = fixture.streams.single()
        assertEquals("The reported error must carry the cause", BOOM, error.message)
        assertTrue(
            "The stream opened before the chain failed must be closed, or the microphone stays " +
                "captured for the rest of the process",
            failed.closed,
        )
        assertEquals(
            "The stream of the failed start must be released exactly once, but it was released " +
                "${failed.closeCount} times",
            1,
            failed.closeCount,
        )
        assertFalse("A failed start must not leave the tuner running", fixture.tuner.isRunning)
        assertNotNull("A failed start must report the error", fixture.tuner.error.value)
        assertSame(
            "The error the start threw must be the one the tuner reports",
            error,
            fixture.tuner.error.value,
        )

        fixture.createDispatcher = { stream, onFrame ->
            TunerPipeline.createDispatcher(stream, onFrame = onFrame)
        }
        fixture.tuner.startWithPermissionCheck { true }

        assertTrue(
            "A start after a failed one must work: the failure must not leave the tuner half " +
                "started",
            fixture.tuner.isRunning,
        )
        assertNull(
            "A successful start must clear the error of the failed one, but the tuner reported " +
                "${fixture.tuner.error.value}",
            fixture.tuner.error.value,
        )
        assertEquals("The second start must open its own stream", 2, fixture.streams.size)

        fixture.tuner.stop()

        assertTrue("stop() must close the stream of the second start", fixture.streams[1].closed)
        assertEquals(
            "Each of the two starts must release its own stream exactly once",
            2,
            fixture.streams.sumOf { it.closeCount },
        )
    }

    @Test
    fun startAfterStopStartsAgain() {
        val fixture = Fixture(newStream = { FakeAudioStream.finite(ShortArray(FINITE_SAMPLES)) })

        for (cycle in 0 until CYCLES) {
            fixture.tuner.startWithPermissionCheck { true }
            val stream = fixture.streams[cycle]
            awaitUntil("the audio thread of cycle $cycle to read the signal") { stream.blocksRead > 0 }

            assertTrue(
                "The tuner must be running after a successful start, in cycle $cycle",
                fixture.tuner.isRunning,
            )
            assertNull(
                "A successful start must not report an error, in cycle $cycle: " +
                    "${fixture.tuner.error.value}",
                fixture.tuner.error.value,
            )

            fixture.tuner.stop()

            assertTrue("stop() must close the stream of cycle $cycle", stream.closed)
            assertEquals(
                "The stream of cycle $cycle must be released exactly once, but it was released " +
                    "${stream.closeCount} times",
                1,
                stream.closeCount,
            )
            assertFalse(
                "The tuner must not be running after stop(), in cycle $cycle",
                fixture.tuner.isRunning,
            )
        }

        assertEquals("Every cycle must open its own stream", CYCLES, fixture.streams.size)
        assertEquals(
            "Every cycle must close its own stream",
            CYCLES,
            fixture.streams.sumOf { it.closeCount },
        )
    }

    /**
     * Pins that the dispatcher factory of the app —the default of [Tuner], which the application
     * never overrides— is what makes the tuner hear a bass note under a mains hum.
     *
     * The signal is the low E of a bass buried under a hum louder than the note itself, with a DC
     * offset from the electronics of the instrument: it is the one
     * [PipelineIntegrationTest.testE1BassSurvivesMainsHumFundamental] reads, and it is generated
     * here because the helpers of that suite are private to it. The amplitudes are those of a
     * signal that is a tone and not a buzz: the fundamental of the E1 at 0.15, its octave at 0.3 and
     * the 50 Hz of the mains over both of them at 0.3, the same levels as the integration test,
     * with the noise floor of a room on top. The octave belongs to the note, so the signal is still
     * periodic at the period of the E1 and only the hum has to go.
     *
     * The tuner under test is built with no dispatcher factory of its own, which is the point: the
     * suite that covers this note builds the chain with the notches it passes explicitly, so the
     * default of the class —the chain the app really runs— was covered by nothing, and dropping its
     * notches would have left every test green.
     *
     * Measured, on the 29 frames of the signal and through the default chain: all of them hold the
     * note, and the offset settles at -40.999 semitones from A4, the E1 string. Removing the notches
     * of the chain —measured as a mutation— leaves the tuner reporting **no note at all** for the
     * whole signal: the hum is the strongest period of the frame, the detector finds no period of the
     * note in it, and not one frame survives to reach the needle. The note is therefore only within
     * [OFFSET_TOLERANCE] of the E1 with the 50 Hz notch of the default chain in place.
     */
    @Test
    fun defaultDispatcherFactoryKeepsTheMainsNotch() {
        val fixture = Fixture(
            newStream = {
                FakeAudioStream.finite(
                    tone(
                        listOf(
                            Partial(E1_HZ, 0.15),
                            Partial(E2_HZ, 0.3),
                            Partial(MAINS_HZ, 0.3),
                        ),
                        dc = DC_LEVEL,
                        noiseRms = NOISE_RMS,
                    ),
                )
            },
            useDefaultDispatcher = true,
        )

        try {
            fixture.tuner.startWithPermissionCheck { true }
            val stream = fixture.streams.single()
            awaitUntil("the audio thread to serve the whole signal") { stream.closed }

            assertNull(
                "A start of a finite signal must not report an error, but the tuner reported " +
                    "${fixture.tuner.error.value}",
                fixture.tuner.error.value,
            )
            val offset = offsetFromA4(fixture.tuner)
            assertNotNull(
                "The chain of the app must hold the bass note under the hum, but the tuner " +
                    "settled on no note",
                offset,
            )
            assertEquals(
                "The chain of the app must attribute the bass note to the E1 string, but the " +
                    "offset settled at $offset semitones from A4",
                Notes.getOffsetFromA4(E1_HZ),
                offset!!,
                OFFSET_TOLERANCE,
            )
            assertEquals(
                "The whole signal must be analysed, but the stream was read " +
                    "${stream.blocksRead} times instead of $BLOCKS",
                BLOCKS,
                stream.blocksRead,
            )
        } finally {
            fixture.tuner.stop()
        }
    }

    /**
     * Pins that a microphone that cannot be opened fails the start with its own error, untouched,
     * and leaves the tuner as clean as a failed build of the chain does.
     *
     * The stream is opened before the chain is built, and it is the only one of the two steps that
     * can fail with a meaningful error of its own: the production stream reports that another app
     * holds the microphone. The tuner must hand that error to the caller and to [Tuner.error]
     * without wrapping it in another exception, since it is already a [TunerException], and it owns
     * no stream on that path, so nothing may be closed and nothing may be left running.
     */
    @Test
    fun sourceFailureIsPropagatedAndLeavesTheTunerClean() {
        val fixture = Fixture()
        val busy = TunerException(MICROPHONE_BUSY, null)
        fixture.failSource = busy

        val error = assertThrows(TunerException::class.java) {
            fixture.tuner.startWithPermissionCheck { true }
        }

        assertSame(
            "The error of the source must reach the caller untouched, but the start threw a " +
                "different one: $error",
            busy,
            error,
        )
        assertFalse("A failed start must not leave the tuner running", fixture.tuner.isRunning)
        assertSame(
            "The tuner must report the error the start threw",
            busy,
            fixture.tuner.error.value,
        )
        assertTrue(
            "No stream was opened, so none may be held, but the tuner holds " +
                "${fixture.streams.size}",
            fixture.streams.isEmpty(),
        )
        assertEquals(
            "The chain must not be built over a stream that could not be opened",
            0,
            fixture.dispatcherCalls,
        )

        fixture.failSource = null
        fixture.tuner.startWithPermissionCheck { true }

        assertTrue(
            "A start after a failed one must work: the failure must not leave the tuner half " +
                "started",
            fixture.tuner.isRunning,
        )
        assertNull(
            "A successful start must clear the error of the failed one, but the tuner reported " +
                "${fixture.tuner.error.value}",
            fixture.tuner.error.value,
        )
        val stream = fixture.streams.single()

        awaitUntil("the audio thread to read the first blocks") {
            stream.blocksRead >= BLOCKS_BEFORE_STOP
        }
        fixture.tuner.stop()

        assertTrue("stop() must close the stream of the second start", stream.closed)
        assertEquals(
            "The stream of the second start must be released exactly once, but it was released " +
                "${stream.closeCount} times",
            1,
            stream.closeCount,
        )
        assertFalse("The tuner must not be running after stop()", fixture.tuner.isRunning)
    }

    /**
     * A tuner wired to the fakes, recording what its lifecycle does.
     *
     * The tuner is built with [PitchTracker] rather than the default tracker, so that the chain the
     * fakes drive behaves as a plain pipeline: the frames of the silence the streams serve carry no
     * note either way, and the tests only read the ownership of the stream.
     *
     * @param newStream Factory of the stream the tuner opens on each start, a live capture by
     *                  default, which only ends when the tuner is stopped.
     * @param useDefaultDispatcher Whether the tuner is left with the dispatcher factory of the app,
     *                             which builds the chain with the production notches, instead of
     *                             the one of [createDispatcher] that the fixture records and can
     *                             replace.
     */
    private class Fixture(
        private val newStream: () -> FakeAudioStream = { FakeAudioStream.silentUntilClosed() },
        useDefaultDispatcher: Boolean = false,
    ) {

        /** Streams the tuner opened, in the order it opened them. */
        val streams = mutableListOf<FakeAudioStream>()

        /** Number of times the source factory was called. */
        var sourceCalls = 0
            private set

        /** Number of times the dispatcher factory was called. */
        var dispatcherCalls = 0
            private set

        /**
         * Error the source factory throws instead of opening a stream, or `null` to open one.
         *
         * It is how a start that fails before the microphone is captured is exercised: the
         * production stream fails to open when another app holds the microphone.
         */
        var failSource: TunerException? = null

        /**
         * Factory of the chain the next [Tuner.start] builds, the one of the app by default.
         *
         * A test replaces it to make the build fail, which is the failure that happens once the
         * microphone is already captured.
         */
        var createDispatcher: (
            TarsosDSPAudioInputStream,
            (PitchDetectionResult, Float) -> Unit,
        ) -> AudioDispatcher = { stream, onFrame ->
            TunerPipeline.createDispatcher(stream, onFrame = onFrame)
        }

        /** Tuner under test. */
        val tuner = if (useDefaultDispatcher) {
            Tuner(Tuning.STANDARD, PitchTracker(), sourceFactory = ::openStream)
        } else {
            Tuner(
                Tuning.STANDARD,
                PitchTracker(),
                sourceFactory = ::openStream,
                dispatcherFactory = { stream, onFrame ->
                    dispatcherCalls++
                    createDispatcher(stream, onFrame)
                },
            )
        }

        /** Source factory of the tuner: opens the stream of the test, or fails with [failSource]. */
        private fun openStream(sampleRateHz: Int, bufferSize: Int): TarsosDSPAudioInputStream {
            sourceCalls++
            failSource?.let { throw it }
            return newStream().also { streams += it }
        }
    }

    /**
     * Waits for [condition] to hold, polling every [POLL_INTERVAL_MS] until [timeoutMs] elapses.
     *
     * The lifecycle is crossed by a thread the tuner starts, so a test cannot read its outcome the
     * instant it asks for it. The wait is bounded and reported: a condition that never holds fails
     * with what was awaited instead of hanging the suite.
     *
     * @param description What is being waited for, reported when the timeout expires.
     * @param timeoutMs Longest time to wait, in milliseconds.
     * @param condition Condition to wait for.
     */
    private fun awaitUntil(
        description: String,
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("Timed out after $timeoutMs ms waiting for $description")
    }

    /**
     * Offset from A4, in semitones, of the note the tuner is reporting, or `null` if it reports
     * none.
     *
     * The tuner publishes the offset of the note against the root of the selected string —or of the
     * selected note, in chromatic mode— and not against A4, so a test that compares the note with
     * the frequency it was generated at has to bring that root back, which is the index of the note
     * as [Notes] defines it.
     */
    private fun offsetFromA4(tuner: Tuner): Double? {
        val offset = tuner.noteOffset.value ?: return null
        val note = if (tuner.chromatic.value) {
            tuner.selectedNote.value
        } else {
            tuner.tuning.value.getString(tuner.selectedString.value).getNoteIndex(0)
        }
        return note + offset
    }

    /** A partial of the synthetic tone: a [frequencyHz] with an [amplitude]. */
    private data class Partial(val frequencyHz: Double, val amplitude: Double)

    /**
     * Builds [SIGNAL_SAMPLES] samples of a tone made of [partials], on top of a [dc] offset and of a
     * white noise of the given [noiseRms] level, as PCM16.
     *
     * The phase is taken from the global sample index, so it never restarts at a frame boundary, and
     * the noise is seeded, so the signal is the same on every run. The values outside the range of
     * the format are clipped, as a capture would clip them.
     */
    private fun tone(
        partials: List<Partial>,
        dc: Double = 0.0,
        noiseRms: Double = 0.0,
    ): ShortArray {
        val random = Random(NOISE_SEED)
        val noisePeak = noiseRms * sqrt(3.0)
        return ShortArray(SIGNAL_SAMPLES) { index ->
            var value = dc
            for (partial in partials) {
                value += partial.amplitude *
                    sin(2.0 * PI * partial.frequencyHz * index / TunerPipeline.SAMPLE_RATE)
            }
            if (noiseRms > 0) {
                value += random.nextDouble(-noisePeak, noisePeak)
            }
            (value.coerceIn(-1.0, 1.0) * PCM16_PEAK).roundToInt().toShort()
        }
    }

    private companion object {

        /** Blocks the audio thread must have read before it is stopped. */
        const val BLOCKS_BEFORE_STOP = 2

        /** Samples of the finite stream of the restart test, half a second of silence. */
        const val FINITE_SAMPLES = TunerPipeline.BUFFER_SIZE * 2

        /** Number of start/stop cycles of the restart test. */
        const val CYCLES = 2

        /** Message of the failure the dispatcher factory throws in the failing start test. */
        const val BOOM = "boom"

        /** Message of the failure the source factory throws in the failing source test. */
        const val MICROPHONE_BUSY = "microphone busy"

        /** Longest time a test waits for the audio thread, in milliseconds. */
        const val AWAIT_TIMEOUT_MS = 5_000L

        /** Time between two polls of a condition, in milliseconds. */
        const val POLL_INTERVAL_MS = 5L

        /** Samples a frame advances over the previous one, the step of the analysis window. */
        const val STEP_SIZE = TunerPipeline.BUFFER_SIZE - TunerPipeline.BUFFER_OVERLAP

        /** Blocks of the signal of the mains hum test, roughly 1.5 s of audio. */
        const val BLOCKS = 31

        /** Samples of the signal of the mains hum test: a whole number of blocks. */
        const val SIGNAL_SAMPLES = TunerPipeline.BUFFER_SIZE + (BLOCKS - 1) * STEP_SIZE

        /** Largest error accepted between the settled offset and the note that was generated. */
        const val OFFSET_TOLERANCE = 0.15

        /** Peak of the 16 bit signed samples the stream serves. */
        const val PCM16_PEAK = 32767.0

        /** Seed of the synthetic noise, which makes every run identical. */
        const val NOISE_SEED = 20260201

        /** Level of the noise floor of the synthetic signal. */
        const val NOISE_RMS = 1e-4

        /** DC offset the electronics of an instrument add to the signal. */
        const val DC_LEVEL = 0.05

        /** Frequency of the low E of a bass string, the note the mains hum test looks for. */
        const val E1_HZ = 41.2

        /** Frequency of the low E of a guitar string, the first harmonic of [E1_HZ]. */
        const val E2_HZ = 82.41

        /** Frequency of the mains hum, the one of the region the tuner is used in. */
        const val MAINS_HZ = 50.0
    }
}
