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

package com.rohankhayech.choona.lib.controller.metronome

import com.rohankhayech.choona.lib.model.metronome.MetronomeState
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pre-renders the metronome's two click voices, the accented downbeat ([accent]) and the ordinary
 * beat ([regular]), as fixed-point sample buffers.
 *
 * ## Why the click is synthesised
 *
 * The click is computed from a windowed sine burst instead of being loaded from an audio file, so
 * the app ships no audio asset, needs no decoding and no `res/raw` entry, and produces exactly the
 * same samples on every device and in every unit test. A click decoded from an asset would depend
 * on the decoder and on the asset being copied bit-exact and at the right rate; here the same
 * parameters always yield the same [ShortArray], which is what makes the renderer testable off the
 * device.
 *
 * The two buffers are rendered once, at construction, because the audio render thread must never
 * compute a sine or allocate: it only mixes pre-rendered frames into the output block. The
 * arithmetic therefore runs on the **render thread as well**, inside the engine's `prepareOutput`,
 * one step before the track is started: it is a one-off that happens before any sample is due —
 * before the first block is written and before `AudioTrack.play()`— so the render thread can
 * afford it once and never again. That placement is load-bearing and must not drift: moving
 * construction into the render *loop* would put 1764 sines and an allocation per click on the audio
 * thread, i.e. exactly the stall this class exists to keep off it. What is left on the loop is a
 * copy of pre-rendered [Short]s into the block.
 *
 * ## Waveform
 *
 * Each click lasts `n = durationMs * sampleRateHz / 1000` frames, i.e. 1764 frames at 44.1 kHz for
 * the default 40 ms. Frame `i` is `sin(2π * freq * t) * envelope * peak * Short.MAX_VALUE` with
 * `t = i / sampleRateHz`, rounded and clamped into the [Short] range, so the pitch is exact for the
 * rate the audio device actually uses and the click is not detuned on a device running at 48 kHz.
 *
 * The envelope is the product of three stages:
 *
 * * an attack ramping linearly from 0 to 1 over the first millisecond, so the waveform starts at
 *   exactly 0. A buffer that opened at full amplitude would present a DC step to the speaker, and
 *   that step is itself an audible click/pop, indistinguishable from the one the metronome means to
 *   produce;
 * * an exponential decay `exp(-5.0 * progress)` where `progress` runs from 0 to 1 over the
 *   buffer, leaving about 0.67 % of the peak at the end, which is what gives the short percussive
 *   "tick" rather than a beep;
 * * a linear taper over the last two milliseconds down to exactly 0 at the final frame, so the
 *   buffer can be truncated at any point without a discontinuity.
 *
 * The accent is both *higher in pitch* (by default 1660 Hz against 1100 Hz) and *louder* (0.9 of
 * full scale against 0.55), so the downbeat is distinguishable even on a phone speaker that
 * reproduces neither pitch nor level faithfully: losing the pitch difference still leaves a level
 * difference, and losing the level difference still leaves a pitch difference.
 *
 * ## Cross-class invariant
 *
 * `clickFrames`, i.e. [accent]`.size`, must stay smaller than the shortest beat interval the
 * metronome can ask for: [MetronomeState.MIN_BEAT_INTERVAL_MS] (200 ms, the interval at
 * [MetronomeState.MAX_BPM] = 300 BPM) worth of frames at the same sample rate. That is what lets
 * the renderer keep a **single** click voice alive at a time instead of mixing overlapping voices:
 * a click is always over before the next beat starts. The defaults leave a 5x margin (40 ms against
 * 200 ms, i.e. 1764 frames against 8820 at 44.1 kHz). Raising [MetronomeState.MAX_BPM], or making
 * a click longer than that margin — as constructing this class with a larger `durationMs` does —
 * would make consecutive clicks overlap and require a polyphonic renderer.
 *
 * ## Invariants
 *
 * * [accent]`.size == `[regular]`.size`, so the mixer can address both voices with one frame index
 *   and one click length.
 * * Both arrays start and end with a sample of exactly 0, and their first and last few frames carry
 *   almost no energy, so a click can be started or cut at any block boundary without a pop.
 * * Every sample is a valid [Short] and the default peaks stay well below full scale, so a click
 *   never clips on its own; keeping at most one click alive at a time (see above) is what keeps the
 *   mixed output from clipping too, since the two default peaks would sum past full scale.
 * * The contents are a pure function of the construction parameters: no randomness, no wall clock,
 *   no device state.
 *
 * ## What the engine asks for
 *
 * The engine passes only [sampleRateHz]: the tuning parameters below are currently unused by it,
 * because every session builds a fresh synthesiser and always wants the same click. They are kept
 * for a future session-reuse path, and the tests exercise them.
 *
 * ## Threading
 *
 * Construction is cheap but it does allocate and compute, so it must happen before playback rather
 * than during it: the engine does it on its own render thread, in `prepareOutput`, ahead of
 * `play()`. The constructed buffers are read-only in practice, exposed as plain [ShortArray]
 * because the mixer reads them frame by frame on the render thread and copying them per block would
 * allocate there — the renderer must therefore only ever read them, never write. Instances are safe
 * to share between threads once constructed.
 *
 * @param sampleRateHz Sample rate of the output audio, in frames per second. Must be positive; a
 *  buffer rendered for the wrong rate would be a click of the wrong length and pitch.
 * @param accentHz Frequency of the accented downbeat click, in Hz. Must be positive.
 * @param regularHz Frequency of the ordinary beat click, in Hz. Must be positive.
 * @param durationMs Length of each click, in milliseconds. Must be positive and long enough for
 *  four frames to be rendered, since the attack and the fade-out need room to taper.
 *  Values at or below [MetronomeState.MIN_BEAT_INTERVAL_MS] keep the single-voice invariant;
 *  longer ones would overlap at the fastest tempo.
 * @param accentPeak Amplitude of the downbeat click as a fraction of full scale, in `0.0..1.0`.
 * @param regularPeak Amplitude of the ordinary click as a fraction of full scale, in `0.0..1.0`.
 *
 * @author Rohan Khayech
 */
class ClickSynthesizer(
    sampleRateHz: Int,
    accentHz: Double = DEFAULT_ACCENT_HZ,
    regularHz: Double = DEFAULT_REGULAR_HZ,
    durationMs: Int = DEFAULT_DURATION_MS,
    accentPeak: Double = DEFAULT_ACCENT_PEAK,
    regularPeak: Double = DEFAULT_REGULAR_PEAK,
) {

    /**
     * Pre-rendered accented click, i.e. the downbeat, as a mono buffer of [Short] samples.
     *
     * Same length as [regular]. Higher in pitch and louder than [regular] so the start of the bar
     * is recognisable; read-only once constructed.
     */
    val accent: ShortArray

    /**
     * Pre-rendered ordinary click, i.e. every beat that is not the first of the bar, as a mono
     * buffer of [Short] samples.
     *
     * Same length as [accent]; read-only once constructed.
     */
    val regular: ShortArray

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(durationMs > 0) { "durationMs must be positive, was $durationMs" }
        require(accentHz > 0.0 && regularHz > 0.0) {
            "click frequencies must be positive, were accentHz=$accentHz, regularHz=$regularHz"
        }
        require(accentPeak in 0.0..1.0 && regularPeak in 0.0..1.0) {
            "click peaks must be within 0.0..1.0 of full scale, " +
                "were accentPeak=$accentPeak, regularPeak=$regularPeak"
        }

        val frames = durationMs * sampleRateHz / 1000
        require(frames >= MIN_FRAMES) {
            "a click must last at least $MIN_FRAMES frames at $sampleRateHz Hz, " +
                "but $durationMs ms is only $frames"
        }

        accent = render(sampleRateHz, accentHz, accentPeak, frames)
        regular = render(sampleRateHz, regularHz, regularPeak, frames)
    }

    private companion object {
        /**
         * Default frequency of the accented downbeat click, in Hz.
         *
         * High enough to stay clearly above the ordinary click and above the rumble a phone speaker
         * adds, and short enough at 40 ms to read as a tick rather than a note.
         */
        const val DEFAULT_ACCENT_HZ = 1660.0

        /**
         * Default frequency of the ordinary beat click, in Hz.
         *
         * Two thirds of the accent, so the two voices are an interval apart and never mistaken for
         * each other even when the level difference is lost.
         */
        const val DEFAULT_REGULAR_HZ = 1100.0

        /** Default length of a click in milliseconds. */
        const val DEFAULT_DURATION_MS = 40

        /** Default amplitude of the accented click as a fraction of full scale. */
        const val DEFAULT_ACCENT_PEAK = 0.9

        /** Default amplitude of the ordinary click as a fraction of full scale. */
        const val DEFAULT_REGULAR_PEAK = 0.55

        /** Length of the linear attack at the start of a click, in milliseconds. */
        const val ATTACK_MS = 1.0

        /** Length of the linear fade-out at the end of a click, in milliseconds. */
        const val FADE_OUT_MS = 2.0

        /** Rate of the exponential decay across the buffer, i.e. about 0.67 % left at the end. */
        const val DECAY_RATE = 5.0

        /** Fewest frames a click can be made of, so the attack and the fade-out both fit. */
        const val MIN_FRAMES = 4

        /** Full circle in radians, i.e. one period of the sine carrier. */
        const val TWO_PI = 2.0 * PI

        /** Attack length in seconds, i.e. [ATTACK_MS] as the time base of the render loop. */
        const val ATTACK_SECONDS = ATTACK_MS / 1000.0

        /**
         * Renders one click of the given frequency and peak into [frames] fixed-point samples.
         *
         * The envelope is attack * decay * fade-out, and the first and last samples come out as
         * exactly 0 by construction: the attack is 0 at the start, and the fade-out multiplier is 0
         * at the final frame.
         *
         * @param sampleRateHz Sample rate of the audio being rendered, in frames per second.
         * @param frequencyHz Frequency of the click, in Hz.
         * @param peak Amplitude as a fraction of full scale, in `0.0..1.0`.
         * @param frames Number of frames to render, at least four.
         * @return The rendered click, exactly [frames] samples long.
         */
        fun render(sampleRateHz: Int, frequencyHz: Double, peak: Double, frames: Int): ShortArray {
            val samples = ShortArray(frames)
            val lastFrame = frames - 1
            val fadeFrames = (sampleRateHz * FADE_OUT_MS / 1000.0)
                .roundToInt()
                .coerceIn(2, lastFrame)
            val fadeStart = frames - fadeFrames
            val fullScale = Short.MAX_VALUE.toDouble()

            for (i in 0 until frames) {
                val t = i / sampleRateHz.toDouble()
                val progress = i / lastFrame.toDouble()
                val attack = min(1.0, t / ATTACK_SECONDS)
                val decay = exp(-DECAY_RATE * progress)
                val fade = if (i >= fadeStart) (lastFrame - i).toDouble() / (fadeFrames - 1) else 1.0
                val value = sin(TWO_PI * frequencyHz * t) * attack * decay * fade * peak * fullScale
                samples[i] = value.roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return samples
        }
    }
}
