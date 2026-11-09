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

package com.rohankhayech.choona.lib.controller.tuner;

/**
 * An audio processor that publishes the samples it produces in a buffer of its own.
 *
 * A processor that has to change the samples of a frame cannot do so in the frame of the
 * {@link be.tarsos.dsp.AudioEvent}: the {@link be.tarsos.dsp.AudioDispatcher} re-uses a single
 * array for the whole stream and copies the tail of each frame over the head of the next one, so
 * samples written into it come back as input on the following frame. A conditioner therefore
 * filters into its own array and exposes it through this interface, and the processors placed after
 * it read the conditioned signal from {@link #getBuffer()} instead of from the event.
 *
 * The array belongs to the producer and is re-used for every frame, so a consumer must treat it as
 * read-only and must finish reading it before the producer processes the next frame.
 *
 * @author Rohan Khayech
 */
public interface FloatBufferSource {

    /**
     * Returns the samples produced for the frame processed last.
     *
     * The caller must not modify the returned array, and must not keep it beyond the time the next
     * frame takes to arrive.
     *
     * @return The buffer of conditioned samples, of the size of the frames being processed.
     */
    float[] getBuffer();
}
