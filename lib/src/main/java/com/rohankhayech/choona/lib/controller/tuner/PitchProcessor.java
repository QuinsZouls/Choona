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

/*
 *      _______                       _____   _____ _____
 *     |__   __|                     |  __ \ / ____|  __ \
 *        | | __ _ _ __ ___  ___  ___| |  | | (___ | |__) |
 *        | |/ _` | '__/ __|/ _ \/ __| |  | |\___ \|  ___/
 *        | | (_| | |  \__ \ (_) \__ \ |__| |____) | |
 *        |_|\__,_|_|  |___/\___/|___/_____/|_____/|_|
 *
 * -------------------------------------------------------------
 *
 * TarsosDSP is developed by Joren Six at IPEM, University Ghent
 *
 * -------------------------------------------------------------
 *
 *  Info: http://0110.be/tag/TarsosDSP
 *  Github: https://github.com/JorenSix/TarsosDSP
 *  Releases: http://0110.be/releases/TarsosDSP/
 *
 *  TarsosDSP includes modified source code by various authors,
 *  for credits and info, see README.
 *
 */

package com.rohankhayech.choona.lib.controller.tuner;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchDetector;


/**
 * Modified copy of PitchProcessor from TarsosDSP allowing a pitch detector object to be passed in.
 * Is responsible to call a pitch estimation algorithm. It also calculates progress.
 * The underlying pitch detection algorithm must implement the {@link PitchDetector} interface.
 *
 * <p>The samples to analyse can either be taken from the {@link AudioEvent} of the current frame,
 * which is the default, or from a {@link FloatBufferSource} passed in on construction. The second
 * form lets the detector analyse the signal as it has already been conditioned by the previous
 * processor of the {@link be.tarsos.dsp.AudioDispatcher} chain, without the outcome depending on
 * the buffers TarsosDSP hands to the frame.
 *
 * <p>The array returned by the source is read only: it is the same array that the audio thread
 * reuses for every frame, so the detector must not modify it. A detector that needs to alter the
 * samples has to work on its own copy.
 *
 * @author Joren Six
 */
class PitchProcessor implements AudioProcessor {

    /**
     * The underlying pitch detector;
     */
    private final PitchDetector detector;

    private final PitchDetectionHandler handler;

    /**
     * Source of the samples to analyse, or {@code null} to analyse the buffer of the audio event.
     */
    private final FloatBufferSource bufferSource;

    public PitchProcessor(PitchDetector detector, PitchDetectionHandler handler) {
        this(detector, handler, null);
    }

    /**
     * Creates a pitch processor that analyses the samples served by the given source.
     *
     * @param detector The pitch detector to run on the samples.
     * @param handler The handler to report the detected pitch to.
     * @param bufferSource Source of the conditioned samples to analyse, or {@code null} to analyse
     *                     the buffer of the audio event. The array it returns is read only and is
     *                     reused between frames.
     */
    public PitchProcessor(PitchDetector detector, PitchDetectionHandler handler, FloatBufferSource bufferSource) {
        this.detector = detector;
        this.handler = handler;
        this.bufferSource = bufferSource;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        final float[] audioFloatBuffer =
            bufferSource == null ? audioEvent.getFloatBuffer() : bufferSource.getBuffer();

        PitchDetectionResult result = detector.getPitch(audioFloatBuffer);


        handler.handlePitch(result,audioEvent);
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
