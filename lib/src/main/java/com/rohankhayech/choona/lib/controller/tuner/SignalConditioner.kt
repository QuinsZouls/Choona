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

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor

/**
 * Conditions the microphone signal before it reaches the pitch detector, removing the rumble and
 * the mains hum that push the detector away from the note actually being played.
 *
 * The conditioned samples are published in a buffer of its own, exposed by [getBuffer], and the
 * frame of the [AudioEvent] is only read. The [be.tarsos.dsp.AudioDispatcher] re-uses one array for
 * the whole stream and copies the tail of each frame over the head of the next one, so writing the
 * filtered samples back into the frame made those samples come back as input and be filtered a
 * second time. Keeping the output apart is what allows the filters to be continuous across frames
 * without filtering the overlap twice.
 *
 * [process] runs on the audio thread once per frame and allocates nothing: it walks the new samples
 * of the frame with a single loop over the re-used sections.
 *
 * ### High-pass at 30 Hz
 * Removes the DC offset, the stage rumble and the handling noise of the microphone, which are all
 * below any playable note. The cut-off sits just under the lowest note the tuner can detect, the D1
 * of a bass at 36.71 Hz, which is therefore the case that limits the design: measured with the
 * coefficients of the section at 44100 Hz, it costs the D1 1.60 dB, the E1 (41.2 Hz) 1.08 dB, the
 * E2 (82.41 Hz) 0.08 dB, the A2 (110 Hz) 0.02 dB and less than 0.01 dB from the D3 (146.83 Hz) up.
 * A cut-off below the lowest detectable note is what bounds the loss of the whole range to those
 * 1.60 dB.
 *
 * The low B of a five string bass (30.87 Hz) coincides with the cut-off itself and loses 2.77 dB,
 * but it sits below [com.rohankhayech.choona.lib.controller.tuner.Tuner.LOWEST_NOTE]: the tuner
 * does not detect it, so the section is not lowered for it, which would cost the notes that are
 * actually played.
 *
 * ### Notches at 50 Hz and 60 Hz
 * Removes the mains hum picked up by the microphone cable and the electronics of the instrument.
 * Hum matters because in 50 Hz regions the fundamental of the mains falls inside the detection
 * range of the tuner, which starts at D1 (36.7 Hz), and would otherwise be detected as a note being
 * played. Both the European and the American mains frequencies are notched because the device can
 * be used anywhere, and each notch only costs a fraction of a dB outside its own band.
 *
 * ### No software gain
 * The signal is deliberately not amplified. The buffer is already in floating point, so the 16 bit
 * quantisation of the hardware ADC cannot be undone by scaling it here: amplifying only moves the
 * samples towards the clipping point of the rest of the chain without adding any usable
 * resolution.
 *
 * ### Warm up
 * A notch at Q = 25 rings for roughly 150 ms when the stream starts, which is longer than the two
 * frames the tuner takes to fill its analysis window. The first [warmupBlocks] frames are therefore
 * reported as not worth analysing, which keeps the pitch detector from reporting the transient as a
 * note being played.
 *
 * Instances are stateful and not thread safe: [process] runs on the audio thread while the reported
 * level is read from wherever the tuner collects it.
 *
 * @param sampleRateHz Sample rate of the incoming audio in Hz.
 * @param bufferSize Size of the frames the dispatcher hands over, in samples.
 * @param highPassHz Cut-off frequency of the high-pass filter in Hz.
 * @param notchFreqsHz Centre frequencies of the hum notches in Hz, applied in order.
 * @param notchQ Quality factor of the hum notches. Higher values give narrower notches.
 * @param warmupBlocks Number of frames skipped before the output is worth analysing.
 *
 * @author Rohan Khayech
 */
internal class SignalConditioner(
    private val sampleRateHz: Int,
    private val bufferSize: Int,
    private val highPassHz: Double = DEFAULT_HIGH_PASS_HZ,
    private val notchFreqsHz: DoubleArray = DEFAULT_NOTCH_FREQUENCIES_HZ,
    private val notchQ: Double = DEFAULT_NOTCH_Q,
    private val warmupBlocks: Int = DEFAULT_WARMUP_BLOCKS,
) : AudioProcessor, FloatBufferSource {

    init {
        require(bufferSize > 1) { "Buffer size must hold more than one sample, was $bufferSize" }
        require(warmupBlocks >= 0) { "Warm up block count must not be negative, was $warmupBlocks" }
    }

    /** Conditioned samples of the frame processed last. */
    private val output = FloatArray(bufferSize)

    /** High-pass section, which carries the DC and the rumble away. */
    private val highPass =
        Biquad.highPass(sampleRateHz.toDouble(), highPassHz, Biquad.BUTTERWORTH_Q)

    /** Hum notches, all fed in series after [highPass]. */
    private val notches =
        notchFreqsHz.map { Biquad.notch(sampleRateHz.toDouble(), it, notchQ) }.toTypedArray()

    /** Number of frames conditioned since the last [reset]. */
    private var blocksProcessed = 0

    /**
     * Root mean square level of the frame as received, before filtering, [Float.NaN] until the
     * first frame is processed.
     */
    @Volatile
    var inputRms: Float = Float.NaN
        private set

    /**
     * Conditions the samples of [event] into [output].
     *
     * The tail of the previous frame is carried over from the previous output so the filters see a
     * continuous stream, and only the samples the dispatcher has not handed over yet are filtered.
     *
     * @param event The frame of audio received from the dispatcher. It is read, never written.
     * @return Whether the conditioned frame is worth analysing, which is false while the filters
     * are still warming up or when the frame does not have the expected size.
     */
    override fun process(event: AudioEvent): Boolean {
        val input = event.floatBuffer
        if (input.size != bufferSize) {
            return false
        }

        inputRms = event.rms.toFloat()

        val overlap = event.overlap.coerceIn(0, bufferSize - 1)
        if (overlap > 0) {
            // The dispatcher has already copied the tail of the previous frame over the head of
            // this one, and those samples were filtered back then, so the head of the output is
            // refilled from the filtered tail instead of being filtered a second time.
            System.arraycopy(output, bufferSize - overlap, output, 0, overlap)
        }

        // The frame is never written: the dispatcher recycles one array and copies the tail of each
        // frame over the head of the next one, so filtering in place filtered those samples twice,
        // which cost 9 cents of pitch on the low E and put 0.31 jumps in the level every 46 ms.
        for (i in overlap until bufferSize) {
            var sample = highPass.process(input[i].toDouble())
            for (notch in notches) {
                sample = notch.process(sample)
            }
            output[i] = sample.toFloat()
        }

        blocksProcessed++
        return blocksProcessed > warmupBlocks
    }

    /**
     * Called once the audio stream ends. The state of the filters is kept until [reset] is called.
     */
    override fun processingFinished() = Unit

    /**
     * Returns the conditioned samples of the frame processed last.
     *
     * The array is re-used by [process], so it must be read before the next frame arrives and must
     * not be modified by the consumer.
     *
     * @return The buffer of conditioned samples, [bufferSize] samples long.
     */
    override fun getBuffer(): FloatArray = output

    /** Clears the state of every filter section and forgets the output and the reported level. */
    fun reset() {
        highPass.reset()
        for (notch in notches) {
            notch.reset()
        }
        output.fill(0.0f)
        blocksProcessed = 0
        inputRms = Float.NaN
    }

    companion object {

        /** Default cut-off of the high-pass in Hz. */
        internal const val DEFAULT_HIGH_PASS_HZ = 30.0

        /** Default notch frequencies in Hz, covering the 50 Hz and the 60 Hz mains. */
        internal val DEFAULT_NOTCH_FREQUENCIES_HZ = doubleArrayOf(50.0, 60.0)

        /** Default quality factor of the hum notches, which gives a band a few Hz wide. */
        internal const val DEFAULT_NOTCH_Q = 25.0

        /** Default number of frames skipped while the narrow notches ring. */
        internal const val DEFAULT_WARMUP_BLOCKS = 2
    }
}
