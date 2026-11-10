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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Unit tests for [ClickSynthesizer].
 *
 * The click is the only audio the metronome produces, so these tests pin its *shape*, not just its
 * length: the length in frames (so the pitch and the duration follow the device sample rate), the
 * envelope (attack, decay and fade-out) and the pitch separation between the two voices. A click
 * that is merely non-empty would still be audible, but a click that is the wrong number of frames
 * long is detuned, and one that starts or ends on a non-zero sample pops.
 *
 * @author Rohan Khayech
 */
class ClickSynthesizerTest {

    /**
     * Verifies that the click length in frames follows the sample rate it was rendered for, i.e.
     * 40 ms is 1764 frames at 44.1 kHz and 1920 frames at 48 kHz.
     *
     * This is the regression test for the sample-rate bug: a buffer hardcoded to 44.1 kHz played on
     * a 48 kHz track would give 1764 frames where 1920 are due, shortening every click by 8.8 %,
     * i.e. playing it too fast and 8.8 % too high in pitch, which the player hears as a thin,
     * slightly sharp tick that does not line up with the beat.
     */
    @Test
    fun lengthFollowsSampleRate() {
        val at44100 = ClickSynthesizer(SAMPLE_RATE)
        val at48000 = ClickSynthesizer(SAMPLE_RATE_48K)

        assertEquals(1764, at44100.accent.size)
        assertEquals(1764, at44100.regular.size)

        assertEquals(1920, at48000.accent.size)
        assertEquals(1920, at48000.regular.size)
    }

    /**
     * Verifies that the two voices of one synthesiser have the same number of frames, which the
     * mixer relies on to address both buffers with a single click index and a single click length.
     */
    @Test
    fun accentAndRegularHaveTheSameLength() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(rate)
            assertEquals(
                "voices differ in length at $rate Hz",
                synth.accent.size,
                synth.regular.size,
            )
        }
    }

    /**
     * Verifies that both buffers start and finish on a sample of exactly 0, so a click can be
     * started or cut at any block boundary without the discontinuity that would be heard as a pop
     * on top of the intended tick.
     */
    @Test
    fun firstAndLastSamplesAreExactlyZero() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(rate)
            listOf("accent" to synth.accent, "regular" to synth.regular).forEach { (name, samples) ->
                assertEquals("$name starts on a non-zero sample at $rate Hz", 0, samples.first().toInt())
                assertEquals("$name ends on a non-zero sample at $rate Hz", 0, samples.last().toInt())
            }
        }
    }

    /**
     * Verifies that the accented click is louder than the ordinary one, so the downbeat is still
     * recognisable on a speaker that reproduces level but not pitch.
     */
    @Test
    fun accentIsLouderThanRegular() {
        val synth = ClickSynthesizer(SAMPLE_RATE)
        assertTrue(
            "accent peak ${synth.accent.peak()} is not above regular peak ${synth.regular.peak()}",
            synth.accent.peak() > synth.regular.peak(),
        )
    }

    /**
     * Verifies that a default click stays below full scale, so the mixer still has headroom and a
     * single voice can never clip on its own.
     *
     * The rendered peak is the configured peak times the sine value sampled at the envelope
     * maximum, and that sample is only rarely exactly 1, so the buffer reaches a little under the
     * configured amplitude: about 87 % of `peak * Short.MAX_VALUE` for the defaults. The assertion
     * is therefore a ceiling with a small tolerance, plus a floor proving the click is actually
     * rendered at the intended level rather than attenuated by accident.
     */
    @Test
    fun samplesNeverLeaveTheShortRange() {
        val synth = ClickSynthesizer(SAMPLE_RATE)
        val fullScale = Short.MAX_VALUE.toDouble()

        listOf(
            Triple("accent", synth.accent, DEFAULT_ACCENT_PEAK),
            Triple("regular", synth.regular, DEFAULT_REGULAR_PEAK),
        ).forEach { (name, samples, configuredPeak) ->
            val peak = samples.peak()
            val ceiling = (configuredPeak * fullScale * 1.02).roundToInt()
            val floor = (configuredPeak * fullScale * 0.8).roundToInt()

            assertTrue("$name peak $peak exceeds full scale", peak <= Short.MAX_VALUE)
            assertTrue("$name peak $peak exceeds the configured $ceiling", peak <= ceiling)
            assertTrue("$name peak $peak falls short of the configured $floor", peak >= floor)
        }
    }

    /**
     * Verifies that the first and last window of samples carries almost no energy, which is what the
     * attack ramp and the fade-out are for: a buffer that jumped from silence to a large sample (or
     * back) would produce a step in the waveform, and a step is an audible click/pop that cannot be
     * told apart from the intended tick.
     *
     * The head of the buffer is held to a fifth of the peak because the attack takes a full
     * millisecond to open up, i.e. about 44 frames at 44.1 kHz. The tail is held to a thousandth
     * because the last [EDGE_WINDOW] frames sit inside the fade-out, where the taper is by then at
     * 8/87 of an envelope the decay has already left at 0.67 % of the peak, i.e. under 0.06 % of the
     * peak between them. That barrier depends on the taper being there: without it the decay alone
     * leaves 0.67 % of the peak *per frame*, ~5 % of it over the window, which fails here. The shape
     * of the taper itself is pinned by [fadeOutTapersTheTailToZeroInTheLastTwoMilliseconds].
     */
    @Test
    fun waveformStartsAndEndsSilent() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(rate)
            listOf("accent" to synth.accent, "regular" to synth.regular).forEach { (name, samples) ->
                val peak = samples.peak()
                val head = samples.take(EDGE_WINDOW).sumOf { abs(it.toInt()) }
                val tail = samples.takeLast(EDGE_WINDOW).sumOf { abs(it.toInt()) }

                assertTrue(
                    "$name head energy $head over $EDGE_WINDOW frames is not silent at $rate Hz",
                    head <= EDGE_WINDOW * peak * HEAD_FRACTION,
                )
                assertTrue(
                    "$name tail energy $tail over $EDGE_WINDOW frames is not silent at $rate Hz",
                    tail <= EDGE_WINDOW * peak * TAIL_FRACTION,
                )
            }
        }
    }

    /**
     * Verifies that the fade-out is what the class documents: the last [FADE_OUT_MS] of the click
     * are a linear taper from the envelope level at the start of the window down to exactly 0.
     *
     * This is the part [waveformStartsAndEndsSilent] cannot see, because with the default carriers
     * the exponential decay has already silenced the tail on its own. The tape is also not
     * measurable sample by sample there: consecutive samples of an 1100 Hz click are a quarter of a
     * period apart, so their magnitudes rise and fall with the carrier rather than with the
     * envelope. This test therefore renders a carrier at a quarter of the sample rate, which turns
     * over every four frames and leaves the magnitude of every odd frame equal to the envelope —
     * taper included — so the taper can be read straight off the buffer.
     *
     * Odd frame `k` past the start of the window must then be at
     * `A * (fadeFrames - 1 - k) / (fadeFrames - 1)`, with `A` the level measured at the first odd
     * frame of the window: that is the documented linear taper from `A` to 0. The decay keeps
     * running underneath, multiplying the ramp by a factor that falls to 0.78 over the window, so a
     * tenth of `A` is tolerated: enough for that tilt and for rounding to whole samples, and far too
     * little for any other taper. A taper that is missing (a constant), a smoothstep, a quadratic or
     * an exponential one all deviate by 12 % of `A` or more; the constant also fails the last level
     * not being 0, and the three curves fail the monotonicity check as well.
     *
     * The window is also required to be *where* the taper starts. Under the decay alone the level
     * falls by exp(-5 * 2 / 40) = 0.78 over the two milliseconds of the window, so the level at its
     * start must still be most of the level one window earlier: 0.65 of it, which is below the 0.77
     * the decay produces and above the 0.51 of a three-millisecond taper and the 0.39 of a
     * four-millisecond one. A taper *shorter* than the documented two milliseconds is not
     * distinguished here; it is only bounded, loosely, by the tail energy of
     * [waveformStartsAndEndsSilent].
     */
    @Test
    fun fadeOutTapersTheTailToZeroInTheLastTwoMilliseconds() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(
                sampleRateHz = rate,
                accentHz = (rate / QUARTER_RATE_DIVISOR).toDouble(),
                regularHz = (rate / QUARTER_RATE_DIVISOR).toDouble(),
            )
            listOf("accent" to synth.accent, "regular" to synth.regular).forEach { (name, samples) ->
                val fadeFrames = rate * FADE_OUT_MS / 1000
                val fadeStart = samples.size - fadeFrames
                val levels = (0 until fadeFrames)
                    .filter { it % 2 == 1 }
                    .map { k -> abs(samples[fadeStart + k].toInt()) }
                val windowStart = levels.first()
                val previousWindowStart = abs(samples[fadeStart - fadeFrames + 1].toInt())

                assertEquals("$name does not end on silence at $rate Hz", 0, levels.last())
                levels.zipWithNext { previous, next ->
                    assertTrue("$name fade is not a taper: $levels at $rate Hz", next < previous)
                }
                assertTrue(
                    "$name fade of $windowStart starts before the last ${FADE_OUT_MS}ms at " +
                        "$rate Hz, where the decay alone would leave $previousWindowStart",
                    windowStart > previousWindowStart * FADE_START_RATIO,
                )

                levels.forEachIndexed { index, level ->
                    val k = index * 2 + 1
                    val expected = windowStart * (fadeFrames - 1 - k) / (fadeFrames - 1)
                    assertTrue(
                        "$name frame $k of the fade is $level, expected about $expected at $rate Hz",
                        abs(level - expected) <= windowStart * FADE_LEVEL_TOLERANCE,
                    )
                }
            }
        }
    }

    /**
     * Verifies that the envelope decays monotonically after the attack: the running peak of
     * successive [DECAY_WINDOW_MS] ms windows falls strictly, by roughly the factor the exponential
     * decay prescribes (about 0.54 per 5 ms with a decay rate of 5.0 over a 40 ms click).
     *
     * A window is more than eight carrier periods long, so its peak is set by the envelope rather
     * than by where the sine happens to be sampled, which is what makes the comparison meaningful:
     * a constant-amplitude (beeping) click would report equal window peaks and fail here.
     */
    @Test
    fun envelopeDecaysMonotonicallyAfterTheAttack() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(rate)
            listOf("accent" to synth.accent, "regular" to synth.regular).forEach { (name, samples) ->
                val windowFrames = rate * DECAY_WINDOW_MS / 1000
                val windowPeaks = samples.toList()
                    .chunked(windowFrames)
                    .filter { it.size == windowFrames }
                    .map { window -> window.maxOf { abs(it.toInt()) } }

                assertEquals("unexpected window count at $rate Hz", 8, windowPeaks.size)
                windowPeaks.zipWithNext { previous, next ->
                    assertTrue("$name window peaks not decreasing: $windowPeaks", next < previous)
                }
                assertEquals("$name starts below its peak: $windowPeaks", samples.peak(), windowPeaks.first())
            }
        }
    }

    /**
     * Verifies that the accent is pitched above the ordinary click, by counting the positive-going
     * zero crossings of each buffer: 40 ms of 1660 Hz crosses about 66 times and 40 ms of 1100 Hz
     * about 44 times, i.e. a ratio of 1.51.
     *
     * The pitch difference is what makes the downbeat recognisable on a speaker that flattens the
     * level difference, and a 10 % tolerance still catches a wrong frequency: a swap of the two
     * voices or a buffer rendered for the wrong sample rate falls far outside it.
     */
    @Test
    fun accentHasAHigherFrequencyThanRegular() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(rate)
            val accentCrossings = synth.accent.risingZeroCrossings()
            val regularCrossings = synth.regular.risingZeroCrossings()

            assertEquals("unexpected accent pitch at $rate Hz", 66, accentCrossings)
            assertEquals("unexpected regular pitch at $rate Hz", 44, regularCrossings)

            val ratio = accentCrossings.toDouble() / regularCrossings
            val expected = DEFAULT_ACCENT_HZ / DEFAULT_REGULAR_HZ
            assertEquals(expected, ratio, expected * PITCH_TOLERANCE)
        }
    }

    /**
     * Verifies that every parameter is validated at construction instead of producing a silent,
     * inaudible or out-of-range buffer: a non-positive sample rate (which would make the frame count
     * and the pitch meaningless), a non-positive duration, a non-positive frequency, a peak above
     * full scale, and a duration so short that fewer than [MIN_FRAMES] frames are rendered, which
     * would leave no room for the attack and the fade-out to taper.
     */
    @Test
    fun parametersAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = SAMPLE_RATE, durationMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = SAMPLE_RATE, accentHz = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = SAMPLE_RATE, accentPeak = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = SHORT_RATE, durationMs = 30)
        }

        assertThrows(IllegalArgumentException::class.java) { ClickSynthesizer(sampleRateHz = -44_100) }
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = SAMPLE_RATE, regularHz = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickSynthesizer(sampleRateHz = SAMPLE_RATE, regularPeak = -0.5)
        }
    }

    /**
     * Verifies that rendering is a pure function of the construction parameters: two synthesisers
     * built with the same arguments produce byte-identical buffers.
     *
     * Determinism is what makes the click testable off the device and what stops the app from
     * shipping a different-sounding metronome on a different run of the same build.
     */
    @Test
    fun renderingIsDeterministic() {
        val first = ClickSynthesizer(SAMPLE_RATE)
        val second = ClickSynthesizer(SAMPLE_RATE)

        assertArrayEquals(first.accent, second.accent)
        assertArrayEquals(first.regular, second.regular)

        val first48k = ClickSynthesizer(SAMPLE_RATE_48K)
        val second48k = ClickSynthesizer(SAMPLE_RATE_48K)

        assertArrayEquals(first48k.accent, second48k.accent)
        assertArrayEquals(first48k.regular, second48k.regular)
    }

    /**
     * Verifies the cross-class invariant this buffer participates in: a click lasts less than the
     * shortest beat interval the metronome can request, i.e.
     * [MetronomeState.MIN_BEAT_INTERVAL_MS] worth of frames at the same rate.
     *
     * That is what lets the renderer keep a single click voice alive at a time, so the default 40 ms
     * click has a 5x margin against the 200 ms interval at [MetronomeState.MAX_BPM]. Raising the
     * click length or the tempo past that margin would make consecutive clicks overlap and require a
     * polyphonic renderer instead.
     */
    @Test
    fun clickIsShorterThanTheShortestBeatInterval() {
        listOf(SAMPLE_RATE, SAMPLE_RATE_48K).forEach { rate ->
            val synth = ClickSynthesizer(rate)
            val shortestIntervalFrames = rate * MetronomeState.MIN_BEAT_INTERVAL_MS / 1000

            assertTrue(
                "click of ${synth.accent.size} frames does not fit in $shortestIntervalFrames frames",
                synth.accent.size < shortestIntervalFrames,
            )
            assertEquals("unexpected margin at $rate Hz", 5, shortestIntervalFrames / synth.accent.size)
        }
    }

    /** @return The largest absolute sample in this buffer, i.e. its peak amplitude. */
    private fun ShortArray.peak(): Int = maxOf { abs(it.toInt()) }

    /**
     * Counts the zero crossings in the positive direction, i.e. the transitions from a negative
     * sample to a zero or positive one, which in a windowed sine is one per period and a direct
     * measure of its frequency.
     *
     * @return The number of rising zero crossings in the buffer.
     */
    private fun ShortArray.risingZeroCrossings(): Int =
        indices.count { i -> i > 0 && this[i - 1] < 0 && this[i] >= 0 }

    private companion object {
        /** Sample rate of a CD-quality audio track, in frames per second. */
        const val SAMPLE_RATE = 44100

        /** Sample rate of the audio most Android devices render at, in frames per second. */
        const val SAMPLE_RATE_48K = 48000

        /** Low sample rate used where a click must be too short to render. */
        const val SHORT_RATE = 100

        /** Amplitude of the accented click used by the default construction. */
        const val DEFAULT_ACCENT_PEAK = 0.9

        /** Amplitude of the ordinary click used by the default construction. */
        const val DEFAULT_REGULAR_PEAK = 0.55

        /** Frequency of the accented click used by the default construction, in Hz. */
        const val DEFAULT_ACCENT_HZ = 1660.0

        /** Frequency of the ordinary click used by the default construction, in Hz. */
        const val DEFAULT_REGULAR_HZ = 1100.0

        /** Number of leading and trailing frames examined by the silence check. */
        const val EDGE_WINDOW = 8

        /** Most of the peak the leading [EDGE_WINDOW] frames may average, set by the 1 ms attack. */
        const val HEAD_FRACTION = 0.2

        /** Most of the peak the trailing [EDGE_WINDOW] frames may average, set by the 2 ms fade-out. */
        const val TAIL_FRACTION = 0.001

        /** Length of the linear fade-out at the end of a click, in milliseconds. */
        const val FADE_OUT_MS = 2

        /** Divisor of the sample rate giving a carrier that turns over every four frames. */
        const val QUARTER_RATE_DIVISOR = 4

        /** Most of the fade's starting level a frame of the taper may deviate by. */
        const val FADE_LEVEL_TOLERANCE = 0.1

        /**
         * Least of the previous window's level the fade may start at: the decay alone falls to 0.77
         * over the two milliseconds of the window, and a taper that started earlier would multiply
         * that by its own factor.
         */
        const val FADE_START_RATIO = 0.65

        /** Length of the windows the decay is measured over, in milliseconds. */
        const val DECAY_WINDOW_MS = 5

        /** Tolerance on the pitch ratio between the two voices. */
        const val PITCH_TOLERANCE = 0.1
    }
}
