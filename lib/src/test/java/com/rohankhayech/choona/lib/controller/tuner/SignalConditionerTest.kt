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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the frequency response of [SignalConditioner] and the block handling it needs to stay
 * continuous across the overlapping frames of the dispatcher.
 *
 * The frames are produced by [FakeDispatcher], which mimics the buffer management of
 * [be.tarsos.dsp.AudioDispatcher]: one array for the whole stream, the tail of each frame copied
 * over the head of the next one and only the new samples read from the source. That is what makes
 * the in place filtering of the first draft visible here: it filtered those already filtered
 * samples a second time.
 *
 * The magnitudes are measured on the last frame of a long stream of a continuous signal, once the
 * ringing of the narrow notches has died out, and the tones are generated from a global sample
 * index so the phase never restarts at a frame boundary.
 *
 * @author Rohan Khayech
 */
class SignalConditionerTest {

    private val format =
        TarsosDSPAudioFormat(SAMPLE_RATE_HZ.toFloat(), BITS_PER_SAMPLE, CHANNELS, true, false)

    @Test
    fun testHighPassRemovesDc() {
        val conditioner = newConditioner()
        FakeDispatcher { DC_LEVEL }.feed(conditioner, MEASURE_BLOCKS)

        val outputRms = rms(conditioner.getBuffer())

        assertTrue(
            "The 30 Hz high pass should remove a DC offset, but the output RMS was $outputRms",
            outputRms < 1e-4,
        )
    }

    @Test
    fun testNotchAt50Hz() {
        assertAttenuated("50 Hz mains hum", gainDbAt(MAINS_50_HZ))
    }

    @Test
    fun testNotchAt60Hz() {
        assertAttenuated("60 Hz mains hum", gainDbAt(MAINS_60_HZ))
    }

    @Test
    fun testPassbandE2() {
        // The low E of a guitar, the lowest note of a four string bass in standard tuning, must
        // come through untouched: attenuating it would flatten the slope the detector needs.
        assertGainBetween("82.41 Hz (low E)", gainDbAt(E2_HZ), -0.5, 0.5)
    }

    @Test
    fun testPassbandE1() {
        assertGainBetween("41.2 Hz (low E of a bass)", gainDbAt(E1_HZ), -1.5, 0.5)
    }

    @Test
    fun testB0AttenuationDocumented() {
        // The low B of a five string bass sits on the knee of the high pass, so it loses a couple
        // of dB. This is the price of removing the rumble and it is documented in the class, not a
        // defect: the detector still reads the note well below the mains hum it removes.
        assertGainBetween("30.87 Hz (low B of a five string bass)", gainDbAt(B0_HZ), -3.5, -1.5)
    }

    @Test
    fun testHighNotesPassband() {
        for (frequency in listOf(440.0, 1000.0)) {
            assertGainBetween("$frequency Hz", gainDbAt(frequency), -0.2, 0.2)
        }
    }

    @Test
    fun testInputRmsIsPreFilter() {
        val conditioner = newConditioner()
        FakeDispatcher { DC_LEVEL }.feed(conditioner, MEASURE_BLOCKS)

        assertEquals(
            "inputRms must report the frame as received, before the high pass removes the DC",
            DC_LEVEL,
            conditioner.inputRms,
            1e-3f,
        )
    }

    @Test
    fun testInputRmsIsNaNBeforeFirstBlock() {
        assertTrue(
            "The reported level must be unknown until a frame has been processed",
            newConditioner().inputRms.isNaN(),
        )
    }

    @Test
    fun testEventBufferIsNotMutated() {
        val conditioner = newConditioner()
        val feeder = continuousTone(E2_HZ)
        feeder.feed(conditioner, MEASURE_BLOCKS)

        assertArrayEquals(
            "The conditioner must leave the frame of the event untouched",
            feeder.rawWindow(MEASURE_BLOCKS - 1),
            feeder.snapshot(),
            0.0f,
        )
    }

    @Test
    fun testStreamingMatchesSinglePass() {
        val conditioner = newConditioner()
        continuousTone(E2_HZ).feed(conditioner, STREAMING_BLOCKS)

        val streamed = conditioner.getBuffer()
        val reference = singlePass(E2_HZ, DEFAULT_AMPLITUDE, STREAMING_BLOCKS)

        var maxError = 0.0
        for (i in reference.indices) {
            maxError = maxOf(maxError, abs(reference[i] - streamed[i].toDouble()))
        }

        assertEquals(
            "Streaming with overlapping frames must equal filtering the signal in one pass, but " +
                "the last frame differed by $maxError: the overlap is being filtered twice",
            0.0,
            maxError,
            1e-6,
        )
    }

    @Test
    fun testWarmupBlocksAreSkipped() {
        val conditioner = newConditioner()
        val feeder = continuousTone(E2_HZ)

        val accepted = List(4) { feeder.feed(conditioner) }

        assertEquals(
            "The frames inside the warm up must not be reported as worth analysing",
            listOf(false, false, true, true),
            accepted,
        )
    }

    @Test
    fun testTruncatedBlockIsIgnored() {
        val conditioner = newConditioner()
        FakeDispatcher { DC_LEVEL }.feed(conditioner, MEASURE_BLOCKS)
        val reportedRms = conditioner.inputRms
        val conditioned = conditioner.getBuffer().copyOf()
        val truncated = AudioEvent(format).apply {
            setFloatBuffer(FloatArray(BUFFER_SIZE / 2))
            setOverlap(OVERLAP)
        }

        assertFalse("A frame of the wrong size must be rejected", conditioner.process(truncated))
        assertEquals(
            "A rejected frame must not change the reported level",
            reportedRms,
            conditioner.inputRms,
            0.0f,
        )
        assertArrayEquals(
            "A rejected frame must not change the conditioned samples",
            conditioned,
            conditioner.getBuffer(),
            0.0f,
        )
    }

    @Test
    fun testResetClearsState() {
        val conditioner = newConditioner()
        conditionedOutput(conditioner, E2_HZ, MEASURE_BLOCKS)
        assertFalse("The conditioner should have produced samples", conditioner.inputRms.isNaN())

        conditioner.reset()

        assertTrue("Reset must forget the reported level", conditioner.inputRms.isNaN())
        assertArrayEquals(
            "Reset must silence the conditioned samples",
            FloatArray(BUFFER_SIZE),
            conditioner.getBuffer(),
            0.0f,
        )
        assertFalse("Reset must restart the warm up", continuousTone(E2_HZ).feed(conditioner))

        conditioner.reset()
        assertArrayEquals(
            "Reset must leave the filters in the state of a new instance",
            conditionedOutput(newConditioner(), E2_HZ, MEASURE_BLOCKS),
            conditionedOutput(conditioner, E2_HZ, MEASURE_BLOCKS),
            0.0f,
        )
    }

    @Test
    fun testInvalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SignalConditioner(0, BUFFER_SIZE) }
        assertThrows(IllegalArgumentException::class.java) {
            SignalConditioner(SAMPLE_RATE_HZ.toInt(), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalConditioner(SAMPLE_RATE_HZ.toInt(), BUFFER_SIZE, warmupBlocks = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalConditioner(
                SAMPLE_RATE_HZ.toInt(),
                BUFFER_SIZE,
                highPassHz = SAMPLE_RATE_HZ / 2.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalConditioner(
                SAMPLE_RATE_HZ.toInt(),
                BUFFER_SIZE,
                notchFreqsHz = doubleArrayOf(MAINS_50_HZ, 0.0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalConditioner(SAMPLE_RATE_HZ.toInt(), BUFFER_SIZE, notchQ = 0.0)
        }
    }

    /** Creates a conditioner configured like the tuner configures it. */
    private fun newConditioner() = SignalConditioner(SAMPLE_RATE_HZ.toInt(), BUFFER_SIZE)

    /** Creates a dispatcher that streams a continuous tone of [frequency] from the first sample. */
    private fun continuousTone(frequency: Double, amplitude: Double = DEFAULT_AMPLITUDE) =
        FakeDispatcher { sampleIndex -> sineAt(frequency, amplitude, sampleIndex) }

    /** Conditions [blockCount] frames of a continuous tone of [frequency] into [conditioner]. */
    private fun conditionedOutput(
        conditioner: SignalConditioner,
        frequency: Double,
        blockCount: Int,
    ): FloatArray {
        continuousTone(frequency).feed(conditioner, blockCount)
        return conditioner.getBuffer().copyOf()
    }

    /** Level change the conditioner applies to a continuous tone of [frequency], in dB. */
    private fun gainDbAt(frequency: Double): Double {
        val conditioner = newConditioner()
        val feeder = continuousTone(frequency)
        feeder.feed(conditioner, MEASURE_BLOCKS)
        return gainDb(conditioner.getBuffer(), feeder.rawWindow(MEASURE_BLOCKS - 1))
    }

    /** Level change the conditioner applies to [input], in dB. */
    private fun gainDb(output: FloatArray, input: FloatArray): Double =
        20.0 * log10(rms(output) / rms(input))

    /** RMS of the whole [signal] window. */
    private fun rms(signal: FloatArray): Double {
        var energy = 0.0
        for (sample in signal) {
            val value = sample.toDouble()
            energy += value * value
        }
        return sqrt(energy / signal.size)
    }

    /**
     * Filters the whole continuous tone of [frequency] in one pass, and returns the samples of the
     * last frame, which is what the streaming conditioner must reproduce.
     */
    private fun singlePass(frequency: Double, amplitude: Double, blockCount: Int): FloatArray {
        val highPass = Biquad.highPass(
            SAMPLE_RATE_HZ,
            SignalConditioner.DEFAULT_HIGH_PASS_HZ,
            Biquad.BUTTERWORTH_Q,
        )
        val notches = SignalConditioner.DEFAULT_NOTCH_FREQUENCIES_HZ.map {
            Biquad.notch(SAMPLE_RATE_HZ, it, SignalConditioner.DEFAULT_NOTCH_Q)
        }
        val sampleCount = (blockCount - 1) * STEP_SIZE + BUFFER_SIZE
        val filtered = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            var sample = highPass.process(sineAt(frequency, amplitude, i.toLong()).toDouble())
            for (notch in notches) {
                sample = notch.process(sample)
            }
            filtered[i] = sample.toFloat()
        }
        return filtered.copyOfRange(sampleCount - BUFFER_SIZE, sampleCount)
    }

    /** Sample [sampleIndex] of a sine of [frequency] that never restarts its phase. */
    private fun sineAt(frequency: Double, amplitude: Double, sampleIndex: Long): Float =
        (amplitude * sin(2.0 * PI * frequency * sampleIndex / SAMPLE_RATE_HZ)).toFloat()

    /** Asserts that [gainDb] sits inside the documented range, in dB. */
    private fun assertGainBetween(name: String, gainDb: Double, min: Double, max: Double) {
        assertTrue(
            "$name should be passed with a gain of $min..$max dB, but it was $gainDb dB",
            gainDb in min..max,
        )
    }

    /** Asserts that the conditioner removes almost all of [gainDb]. */
    private fun assertAttenuated(name: String, gainDb: Double) {
        assertTrue(
            "$name should be attenuated by at least $MIN_ATTENUATION_DB dB, but it was $gainDb dB",
            gainDb <= MIN_ATTENUATION_DB,
        )
    }

    /**
     * Stands in for [be.tarsos.dsp.AudioDispatcher], which re-uses one array for the whole stream
     * and copies the tail of each frame over the head of the next one.
     */
    private inner class FakeDispatcher(private val signal: (Long) -> Float) {

        private val event = AudioEvent(format)
        private val buffer = FloatArray(BUFFER_SIZE)
        private var blocks = 0

        /** Handles the next block over to [conditioner] and returns whether it is worth using. */
        fun feed(conditioner: SignalConditioner): Boolean {
            val overlap = if (blocks == 0) 0 else OVERLAP
            if (overlap > 0) {
                System.arraycopy(buffer, BUFFER_SIZE - overlap, buffer, 0, overlap)
            }
            val start = blocks.toLong() * STEP_SIZE
            for (i in overlap until BUFFER_SIZE) {
                buffer[i] = signal(start + i)
            }
            event.setFloatBuffer(buffer)
            event.setOverlap(overlap)
            blocks++
            return conditioner.process(event)
        }

        /** Handles [blockCount] blocks over to [conditioner]. */
        fun feed(conditioner: SignalConditioner, blockCount: Int) {
            repeat(blockCount) { feed(conditioner) }
        }

        /** Copy of the array the event holds, as the conditioner leaves it. */
        fun snapshot(): FloatArray = buffer.copyOf()

        /** Copy of the samples the source produces for block [blockIndex]. */
        fun rawWindow(blockIndex: Int): FloatArray =
            FloatArray(BUFFER_SIZE) { i -> signal(blockIndex.toLong() * STEP_SIZE + i) }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44100.0
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        const val BUFFER_SIZE = 4096
        const val OVERLAP = 2048
        const val STEP_SIZE = BUFFER_SIZE - OVERLAP

        /**
         * Frames fed before measuring, which lets the ring of the 50 Hz notch at Q = 25 (roughly
         * 320 ms) die out. With the six frames it takes to fill the analysis window the transient
         * still moves the measured gain by tenths of a dB.
         */
        const val MEASURE_BLOCKS = 32

        /** Frames compared against a single pass, long enough to cross many frame boundaries. */
        const val STREAMING_BLOCKS = 12

        const val DEFAULT_AMPLITUDE = 0.5
        const val DC_LEVEL = 0.3f
        const val E2_HZ = 82.41
        const val E1_HZ = 41.2
        const val B0_HZ = 30.87
        const val MAINS_50_HZ = 50.0
        const val MAINS_60_HZ = 60.0

        /** Attenuation the notches must reach, in dB, below the level of the frame received. */
        const val MIN_ATTENUATION_DB = -40.0
    }
}
