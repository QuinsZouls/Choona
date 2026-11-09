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
import kotlin.math.cos
import kotlin.math.sin

/**
 * A second order recursive section, the building block of the filters that condition the signal of
 * the tuner.
 *
 * The section is driven one sample at a time, in the order the samples arrive, and it remembers the
 * last two samples it saw, so an instance must not be shared between streams and must not be
 * written from several threads at once.
 *
 * ### Direct form I
 * Every sample is evaluated as `y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]`,
 * which needs only the two previous inputs and the two previous outputs and no allocation per
 * sample.
 *
 * ### Why the coefficients and the state are `Double`
 * The hum notches are very narrow and sit very low: the 50 Hz notch at Q = 25 puts its poles at a
 * radius of roughly 0.99986 at 44.1 kHz. The coefficient rounding error of a `Float` (~1e-7) is of
 * the same order as the width of the notch, so it both detunes and shallows the null. `Double`
 * keeps the coefficients exact to ~1e-16, which leaves the depth of the notch to the arithmetic of
 * the equations above and not to the storage type. Samples are converted to `Double` by the caller
 * and back to `Float` once they are stored, so this class never sees a `Float`.
 *
 * @param b0 Feed forward coefficient of the current input sample.
 * @param b1 Feed forward coefficient of the previous input sample.
 * @param b2 Feed forward coefficient of the input sample before that.
 * @param a1 Feedback coefficient of the previous output sample, which is subtracted.
 * @param a2 Feedback coefficient of the output sample before that, which is subtracted.
 *
 * @author Rohan Khayech
 */
internal class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {

    /** Input sample of two steps back, `x[n-2]`. */
    private var input2 = 0.0

    /** Input sample of the last step, `x[n-1]`. */
    private var input1 = 0.0

    /** Output sample of two steps back, `y[n-2]`. */
    private var output2 = 0.0

    /** Output sample of the last step, `y[n-1]`. */
    private var output1 = 0.0

    /**
     * Filters [x], the next input sample of the stream, and returns the matching output sample.
     *
     * @param x The next input sample, already converted to `Double`.
     * @return The output sample of the section for this step.
     */
    fun process(x: Double): Double {
        val y = b0 * x + b1 * input1 + b2 * input2 - a1 * output1 - a2 * output2
        input2 = input1
        input1 = x
        output2 = output1
        output1 = y
        return y
    }

    /** Forgets every sample the section has seen, as if it had just been created. */
    fun reset() {
        input1 = 0.0
        input2 = 0.0
        output1 = 0.0
        output2 = 0.0
    }

    companion object {

        /** Quality factor of a second order Butterworth response, `1 / sqrt(2)`. */
        const val BUTTERWORTH_Q = 0.7071067811865476

        /**
         * Creates a second order high-pass section from the Audio EQ Cookbook of Robert
         * Bristow-Johnson, with `w0 = 2·π·f0/fs`, `alpha = sin(w0)/(2·Q)` and `a0 = 1 + alpha`:
         *
         * ```
         * b0 = (1 + cos(w0)) / 2     b1 = -(1 + cos(w0))     b2 = (1 + cos(w0)) / 2
         * a0 = 1 + alpha             a1 = -2·cos(w0)          a2 = 1 - alpha
         * ```
         *
         * @param sampleRateHz Rate of the stream the section will filter, in Hz.
         * @param freqHz Cut-off frequency of the section, in Hz.
         * @param q Quality factor of the section. [BUTTERWORTH_Q] gives the flattest passband.
         * @return A section with the coefficients normalised by `a0` and a cleared state.
         * @throws IllegalArgumentException If the sample rate is not positive, if the frequency is
         * not between 0 and the Nyquist frequency, or if the quality factor is not positive.
         */
        fun highPass(sampleRateHz: Double, freqHz: Double, q: Double): Biquad {
            validateArguments(sampleRateHz, freqHz, q)
            val w0 = 2.0 * PI * freqHz / sampleRateHz
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 + cosW0) / 2.0 / a0,
                b1 = -(1.0 + cosW0) / a0,
                b2 = (1.0 + cosW0) / 2.0 / a0,
                a1 = -2.0 * cosW0 / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        /**
         * Creates a second order notch section from the Audio EQ Cookbook of Robert
         * Bristow-Johnson, with `w0 = 2·π·f0/fs`, `alpha = sin(w0)/(2·Q)` and `a0 = 1 + alpha`:
         *
         * ```
         * b0 = 1                     b1 = -2·cos(w0)            b2 = 1
         * a0 = 1 + alpha             a1 = -2·cos(w0)            a2 = 1 - alpha
         * ```
         *
         * @param sampleRateHz Rate of the stream the section will filter, in Hz.
         * @param freqHz Centre frequency of the notch, in Hz.
         * @param q Quality factor of the section. Higher values give a narrower notch.
         * @return A section with the coefficients normalised by `a0` and a cleared state.
         * @throws IllegalArgumentException If the sample rate is not positive, if the frequency is
         * not between 0 and the Nyquist frequency, or if the quality factor is not positive.
         */
        fun notch(sampleRateHz: Double, freqHz: Double, q: Double): Biquad {
            validateArguments(sampleRateHz, freqHz, q)
            val w0 = 2.0 * PI * freqHz / sampleRateHz
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = 1.0 / a0,
                b1 = -2.0 * cosW0 / a0,
                b2 = 1.0 / a0,
                a1 = -2.0 * cosW0 / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        /** Rejects the arguments every second order section of the cookbook needs to be valid. */
        private fun validateArguments(sampleRateHz: Double, freqHz: Double, q: Double) {
            require(sampleRateHz > 0.0) { "Sample rate must be positive, was $sampleRateHz Hz" }
            require(freqHz > 0.0 && freqHz < sampleRateHz / 2.0) {
                "Frequency $freqHz Hz must be between 0 and the Nyquist frequency " +
                    "${sampleRateHz / 2.0} Hz"
            }
            require(q > 0.0) { "Quality factor must be positive, was $q" }
        }
    }
}
