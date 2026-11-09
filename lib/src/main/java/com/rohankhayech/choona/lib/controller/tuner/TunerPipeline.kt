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
import be.tarsos.dsp.pitch.FastYin
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult

/**
 * Builds the analysis chain of the tuner: the stages that turn microphone samples into a pitch
 * estimate, in the order they must run.
 *
 * The chain is built here, and only here. [Tuner.start] takes it as it is and the tests build the
 * same one, so the order the [AudioDispatcher] walks cannot drift into one that no test covers: the
 * dispatcher keeps its processors private and walks them in the order they were added, on the audio
 * thread, which leaves the order unobservable from the outside except through the values the stages
 * publish.
 *
 * The parameters of the analysis live here as well, for the same reason: a window, an overlap or a
 * detector threshold configured in two places is a configuration that can disagree with itself.
 *
 * @author Rohan Khayech
 */
internal object TunerPipeline {

    /**
     * Microphone sample rate, in Hz.
     *
     * High enough that every harmonic of the highest detectable note stays far from the Nyquist
     * frequency, and supported by the capture of every device the tuner runs on.
     */
    const val SAMPLE_RATE = 44100

    /** Audio buffer size, in samples, which is the window the detector analyses. */
    const val BUFFER_SIZE = 4096

    /**
     * Samples shared between consecutive analysis windows.
     *
     * Halves the latency without shortening the window, which is what lets the detector resolve the
     * low strings: 4096 samples are roughly 93 ms, enough for several periods of the low E.
     */
    const val BUFFER_OVERLAP = 2048

    /**
     * YIN absolute threshold: lower values reject more borderline frames.
     *
     * It is the floor of the clarity the detector can report, and therefore the only confidence
     * filter of the chain: a lag is only accepted when its normalised difference stays under this
     * threshold, its clarity is published as `1 - difference`, and the frame is flagged as pitched
     * only then. The detector never publishes a probability below `1 - YIN_THRESHOLD` (0.85) and
     * reports no pitch at all below it, which is why [PitchTracker] carries no clarity gate of its
     * own: one above that floor throws away frames the detector accepted, and one below it can never
     * fire.
     *
     * The value is measured, not chosen: on a low E at 10 dB SNR —the weak, low note the whole chain
     * exists to resolve— the detector reports 29 of 29 frames at 0.15. Raising it to 0.20 still
     * reports all 29 of that signal, but takes their clarity from `0.85..0.96` down to `0.80..0.89`,
     * so the pair of a raised threshold and a clarity gate above it showed none of the 29 frames.
     * The threshold is not a knob to raise for cleanliness: it is what decides how close to the
     * noise floor the tuner still hears a string.
     */
    const val YIN_THRESHOLD = 0.15

    /**
     * Builds the analysis chain. The conditioner MUST be added first: processors run in insertion
     * order on the audio thread, so the RMS it publishes belongs to the frame the detector
     * analyses next.
     *
     * @param source Stream of audio to analyse, in production the microphone and in the tests a
     *               synthetic signal. The dispatcher takes ownership of it and closes it when the
     *               stream ends or when it is stopped.
     * @param notchFreqsHz Centre frequencies of the hum notches of the conditioner, in Hz. The
     *                     defaults of the conditioner are the ones of the product; the parameter
     *                     exists so that a caller can drive the same chain without a notch, which is
     *                     how the conditioner is shown to be what removes the mains hum.
     * @param onFrame Receives every frame the detector reports, with the level the conditioner
     *                published for it, which is the level of the frame as received.
     * @return The dispatcher of the chain, ready to be run or wrapped in a thread.
     */
    fun createDispatcher(
        source: TarsosDSPAudioInputStream,
        notchFreqsHz: DoubleArray = SignalConditioner.DEFAULT_NOTCH_FREQUENCIES_HZ,
        onFrame: (PitchDetectionResult, Float) -> Unit,
    ): AudioDispatcher {
        val dispatcher = AudioDispatcher(source, BUFFER_SIZE, BUFFER_OVERLAP)

        // The conditioner must be added first: processors run in insertion order on the audio
        // thread, so the RMS it publishes belongs to the frame the detector is about to analyse.
        val conditioner = SignalConditioner(SAMPLE_RATE, BUFFER_SIZE, notchFreqsHz = notchFreqsHz)
        dispatcher.addAudioProcessor(conditioner)

        val handler = PitchDetectionHandler { result, _ -> onFrame(result, conditioner.inputRms) }
        val detector = FastYin(SAMPLE_RATE.toFloat(), BUFFER_SIZE, YIN_THRESHOLD)
        dispatcher.addAudioProcessor(PitchProcessor(detector, handler, conditioner))

        return dispatcher
    }
}
