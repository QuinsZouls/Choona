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

package com.rohankhayech.choona.lib.controller.tempo

import com.rohankhayech.choona.lib.model.tempo.TapTempoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TapTempoCalculator].
 *
 * @author Rohan Khayech
 */
class TapTempoCalculatorTest {

    /**
     * Verifies that a new calculator has no detected tempo and no applied multiplier.
     */
    @Test
    fun initialState() {
        val state = TapTempoCalculator().state
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertEquals(0, state.tapCount)
        assertFalse(state.steady)
        assertEquals(TapTempoState.DEFAULT_MULTIPLIER, state.multiplier, DELTA)
        assertNull(state.displayBpm)
    }

    /**
     * Verifies that a single tap is counted but does not produce a tempo.
     */
    @Test
    fun singleTapHasNoTempo() {
        val state = TapTempoCalculator().tap(0)
        assertEquals(1, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertFalse(state.steady)
    }

    /**
     * Verifies that two taps 500 milliseconds apart detect a tempo of 120 BPM.
     */
    @Test
    fun twoTapsAtHalfSecond() {
        val state = TapTempoCalculator().tapAll(0, 500)
        assertEquals(2, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
        assertEquals(500.0, state.intervalMs!!, DELTA)
        assertFalse(state.steady)
    }

    /**
     * Verifies that a regular sequence of taps detects a steady tempo.
     */
    @Test
    fun regularTapsAreSteady() {
        val state = TapTempoCalculator().tapAll(0, 500, 1000, 1500, 2000, 2500)
        assertEquals(6, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
        assertEquals(500.0, state.intervalMs!!, DELTA)
        assertTrue(state.steady)
    }

    /**
     * Verifies that taps with irregular intervals do not produce a steady tempo.
     */
    @Test
    fun irregularTapsAreNotSteady() {
        val state = TapTempoCalculator().tapAll(0, 600, 1500, 2100, 3000)
        assertEquals(5, state.tapCount)
        assertNotNull(state.bpm)
        assertFalse(state.steady)
    }

    /**
     * Verifies that a gap longer than the reset timeout starts a new tap sequence.
     */
    @Test
    fun gapBeyondTimeoutStartsNewSequence() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500, 1000)
        assertEquals(3, calculator.state.tapCount)

        val state = calculator.tap(3500)
        assertEquals(1, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertFalse(state.steady)
    }

    /**
     * Verifies that a gap exactly as long as the reset timeout keeps the tap sequence.
     */
    @Test
    fun gapEqualToTimeoutIsKept() {
        val state = TapTempoCalculator().tapAll(0, 500, 2500)
        assertEquals(3, state.tapCount)
        assertNotNull(state.bpm)
    }

    /**
     * Verifies that a tap that is not later than the previous one is ignored.
     */
    @Test
    fun nonMonotonicTapIsIgnored() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500)
        val state = calculator.tap(400)
        assertEquals(2, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that a tap with the same timestamp as the previous one is ignored.
     */
    @Test
    fun duplicateTapIsIgnored() {
        val state = TapTempoCalculator().tapAll(0, 500, 500)
        assertEquals(2, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that only the most recent [TapTempoCalculator.DEFAULT_MAX_TAPS] taps are retained.
     */
    @Test
    fun tapsAreLimitedToDefaultMaxTaps() {
        val state = TapTempoCalculator()
            .tapAll(0, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500)
        assertEquals(TapTempoCalculator.DEFAULT_MAX_TAPS, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
        assertTrue(state.steady)
    }

    /**
     * Verifies that a custom maximum number of taps is respected.
     */
    @Test
    fun tapsAreLimitedToCustomMaxTaps() {
        val state = TapTempoCalculator(maxTaps = 3).tapAll(0, 500, 1000, 1500)
        assertEquals(3, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that a mistimed interval is trimmed instead of skewing the detected tempo.
     */
    @Test
    fun outlierIntervalIsTrimmed() {
        val state = TapTempoCalculator().tapAll(0, 500, 1000, 1500, 3000)
        assertEquals(5, state.tapCount)
        assertEquals(120.0, state.bpm!!, OUTLIER_DELTA)
        assertEquals(500.0, state.intervalMs!!, DELTA)
    }

    /**
     * Verifies that widely alternating taps are not reported as steady, and that the real
     * median keeps the detected tempo on the average of the two alternating intervals
     * instead of flipping between them with the parity of the tap count.
     */
    @Test
    fun widelyAlternatingTapsAreNotSteadyEvenWhenTrimmed() {
        val state = TapTempoCalculator().tapAll(0, 400, 1300, 1700, 2600)
        assertEquals(5, state.tapCount)
        assertEquals(650.0, state.intervalMs!!, DELTA)
        assertEquals(60_000.0 / 650.0, state.bpm!!, DELTA)
        assertFalse(state.steady)
    }

    /**
     * Verifies that the median used to trim outliers is the real median, i.e. the average of
     * the two central values when the number of intervals is even, instead of the upper
     * central value.
     *
     * Intervals 300, 400, 1000, 1000 ms have a real median of 700 ms, which keeps the 400 ms
     * and the two 1000 ms intervals (the 300 ms one is more than half a median away) and
     * detects 75 BPM. Taking the upper central value as the median (1000 ms) would discard the
     * 400 ms interval too and detect a tempo a quarter too slow (60 BPM).
     */
    @Test
    fun trimUsesRealMedianForEvenNumberOfIntervals() {
        val state = TapTempoCalculator().tapAll(0, 300, 700, 1700, 2700)
        assertEquals(5, state.tapCount)
        assertEquals(800.0, state.intervalMs!!, DELTA)
        assertEquals(75.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that the trimmed mean keeps the surviving intervals even when they are a
     * minority, so a discarded outlier is never reintroduced through the mean of the whole set.
     *
     * Intervals 400, 900, 400, 900, 400 ms have a median of 400 ms, which only retains the
     * three 400 ms intervals. Reporting the mean of all five (600 ms) would put the two 900 ms
     * outliers back into the detected tempo, i.e. it would defeat the trimming exactly when the
     * taps are sloppiest, so the survivors are averaged and the sustained tempo of 150 BPM is
     * reported instead of 100 BPM.
     */
    @Test
    fun trimmedMeanKeepsMinoritySurvivors() {
        val state = TapTempoCalculator().tapAll(0, 400, 1300, 1700, 2600, 3000)
        assertEquals(6, state.tapCount)
        assertEquals(400.0, state.intervalMs!!, DELTA)
        assertEquals(150.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that a couple of mistimed taps do not drag the detected tempo away from the
     * beat, even when the clean intervals are only two thirds of the retained window.
     *
     * Intervals 500, 500, 500, 500, 1400, 1400 ms have a median of 500 ms, which retains the
     * four clean 500 ms intervals, i.e. exactly two thirds of them. Averaging the whole set
     * would detect 75 BPM, a third slower than the 120 BPM actually tapped, so the survivors
     * are averaged instead.
     */
    @Test
    fun trimmedMeanKeepsCleanIntervalsWhenLaterTapsAreMistimed() {
        val state = TapTempoCalculator().tapAll(0, 500, 1000, 1500, 2000, 3400, 4800)
        assertEquals(7, state.tapCount)
        assertEquals(500.0, state.intervalMs!!, DELTA)
        assertEquals(120.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that the retained intervals are averaged even when they account for exactly two
     * thirds of them, i.e. that no share of survivors is considered too small to be trusted.
     *
     * Intervals 1000, 1000, 1000, 1000, 480, 1600 ms have a real median of 1000 ms, with an
     * outlier tolerance of half a median, so only the intervals within [500, 1500] ms are
     * retained: the four 1000 ms intervals. Four survivors out of six intervals are exactly
     * two thirds, so the detected interval is the mean of the survivors (1000 ms) and the
     * detected tempo is 60 BPM rather than the 59.21 BPM that averaging all six would give.
     */
    @Test
    fun trimmedMeanKeepsSurvivorsAccountingForTwoThirds() {
        val state = TapTempoCalculator().tapAll(0, 1000, 2000, 3000, 4000, 4480, 6080)
        assertEquals(7, state.tapCount)
        assertEquals(1000.0, state.intervalMs!!, DELTA)
        assertEquals(60.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that the whole set is averaged when no interval survives the trimming, so the
     * detected tempo is a real number instead of NaN.
     *
     * Intervals 100, 100, 1000, 1000 ms are an even number of values with a real median of
     * 550 ms, which is halfway between the two values the taps alternate between: neither the
     * 100 ms nor the 1000 ms intervals are within half a median of it, so nothing is retained.
     * The mean of all four intervals (550 ms) is then reported, i.e. 109.09 BPM.
     */
    @Test
    fun trimmedMeanAveragesAllIntervalsWhenNoIntervalSurvives() {
        val state = TapTempoCalculator().tapAll(0, 100, 200, 1200, 2200)
        assertEquals(5, state.tapCount)
        assertEquals(550.0, state.intervalMs!!, DELTA)
        assertEquals(60_000.0 / 550.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that steadiness is only assessed once at least three intervals are available.
     */
    @Test
    fun steadyRequiresAtLeastThreeIntervals() {
        val calculator = TapTempoCalculator()

        val twoIntervals = calculator.tapAll(0, 500, 1000)
        assertEquals(3, twoIntervals.tapCount)
        assertEquals(120.0, twoIntervals.bpm!!, DELTA)
        assertFalse(twoIntervals.steady)

        val threeIntervals = calculator.tap(1500)
        assertEquals(4, threeIntervals.tapCount)
        assertEquals(120.0, threeIntervals.bpm!!, DELTA)
        assertTrue(threeIntervals.steady)
    }

    /**
     * Verifies that a single mistimed tap makes the taps unsteady, even though the detected
     * tempo still ignores it.
     */
    @Test
    fun singleOffTapMakesTapsUnsteady() {
        val state = TapTempoCalculator().tapAll(0, 500, 1000, 1500, 3000)
        assertEquals(5, state.tapCount)
        assertEquals(120.0, state.bpm!!, OUTLIER_DELTA)
        assertEquals(500.0, state.intervalMs!!, DELTA)
        assertFalse(state.steady)
    }

    /**
     * Verifies that [TapTempoCalculator.doubleTempo] doubles the multiplier up to
     * [TapTempoCalculator.MAX_MULTIPLIER] and that [TapTempoState.displayBpm] reflects it.
     */
    @Test
    fun doubleTempo() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500)

        val doubled = calculator.doubleTempo()
        assertEquals(2.0, doubled.multiplier, DELTA)
        assertEquals(240.0, doubled.displayBpm!!, DELTA)

        assertEquals(4.0, calculator.doubleTempo().multiplier, DELTA)
        assertEquals(TapTempoCalculator.MAX_MULTIPLIER, calculator.doubleTempo().multiplier, DELTA)
    }

    /**
     * Verifies that [TapTempoCalculator.halveTempo] halves the multiplier down to
     * [TapTempoCalculator.MIN_MULTIPLIER].
     */
    @Test
    fun halveTempo() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500)

        val halved = calculator.halveTempo()
        assertEquals(0.5, halved.multiplier, DELTA)
        assertEquals(60.0, halved.displayBpm!!, DELTA)

        assertEquals(0.25, calculator.halveTempo().multiplier, DELTA)
        assertEquals(TapTempoCalculator.MIN_MULTIPLIER, calculator.halveTempo().multiplier, DELTA)
    }

    /**
     * Verifies that [TapTempoCalculator.reset] clears the tap sequence and the multiplier.
     */
    @Test
    fun resetClearsTapsAndMultiplier() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500, 1000)
        calculator.doubleTempo()

        val state = calculator.reset()
        assertEquals(0, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertFalse(state.steady)
        assertEquals(TapTempoState.DEFAULT_MULTIPLIER, state.multiplier, DELTA)
    }

    /**
     * Verifies that starting a new sequence after the reset timeout keeps the multiplier, so
     * a manual tempo adjustment is not silently lost after a pause.
     */
    @Test
    fun newSequenceKeepsMultiplier() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500)
        assertEquals(2.0, calculator.doubleTempo().multiplier, DELTA)

        val restarted = calculator.tap(3500)
        assertEquals(1, restarted.tapCount)
        assertEquals(2.0, restarted.multiplier, DELTA)
        assertNull(restarted.bpm)

        val state = calculator.tap(4000)
        assertEquals(2, state.tapCount)
        assertEquals(2.0, state.multiplier, DELTA)
        assertEquals(120.0, state.bpm!!, DELTA)
        assertEquals(240.0, state.displayBpm!!, DELTA)
    }

    /**
     * Verifies that the retained tap window slides over the most recent taps instead of
     * freezing once it is full, so the detected tempo follows a change of tempo.
     */
    @Test
    fun slidingWindowFollowsChangingTempo() {
        val calculator = TapTempoCalculator()
        (0..7).forEach { index ->
            val state = calculator.tap(index * 500L)
            assertEquals(index + 1, state.tapCount)
            if (index > 0) {
                assertEquals(120.0, state.bpm!!, DELTA)
            }
        }

        val fastTaps = listOf(3900L, 4300L, 4700L, 5100L, 5500L, 5900L, 6300L, 6700L)
        val observed = fastTaps.map { calculator.tap(it) }

        observed.forEach { assertTrue(it.tapCount <= TapTempoCalculator.DEFAULT_MAX_TAPS) }
        assertTrue(observed[2].bpm!! > 120.0)
        assertTrue(observed[2].bpm!! < 150.0)
        assertEquals(150.0, observed.last().bpm!!, DELTA)
        assertTrue(observed.last().steady)
    }

    /**
     * Verifies the steadiness threshold on both sides of
     * [TapTempoCalculator.STEADY_TOLERANCE].
     *
     * The intervals alternating between 500 and 520 ms have a coefficient of variation of
     * 10 / 510 = 0.0196, well within the tolerance, so the tempo is steady. The intervals
     * alternating between 460 and 540 ms have a coefficient of variation of 40 / 500 =
     * 0.08, above the tolerance, so the tempo is not steady.
     */
    @Test
    fun steadyToleranceBoundary() {
        val within = TapTempoCalculator().tapAll(0, 500, 1020, 1520, 2040)
        assertEquals(5, within.tapCount)
        assertTrue(within.steady)

        val beyond = TapTempoCalculator().tapAll(0, 460, 1000, 1460, 2000)
        assertEquals(5, beyond.tapCount)
        assertFalse(beyond.steady)
    }

    /**
     * Verifies that a tap registered less than [TapTempoCalculator.MIN_INTERVAL_MS] after the
     * previous one is ignored, which guards against accidental double taps.
     */
    @Test
    fun doubleTapWithinMinIntervalIsIgnored() {
        val doubleTap = TapTempoCalculator()
        doubleTap.tap(0)
        val ignored = doubleTap.tap(50)
        assertEquals(1, ignored.tapCount)
        assertNull(ignored.bpm)

        val state = TapTempoCalculator().tapAll(0, 500, 540)
        assertEquals(2, state.tapCount)
        assertEquals(120.0, state.bpm!!, DELTA)
        assertEquals(500.0, state.intervalMs!!, DELTA)
    }

    /**
     * Verifies that a tap registered exactly [TapTempoCalculator.MIN_INTERVAL_MS] after the
     * previous one is accepted.
     */
    @Test
    fun tapAtMinimumIntervalIsAccepted() {
        val state = TapTempoCalculator().tapAll(0, TapTempoCalculator.MIN_INTERVAL_MS)
        assertEquals(2, state.tapCount)
        assertEquals(600.0, state.bpm!!, DELTA)
    }

    /**
     * Verifies that a tap after [TapTempoCalculator.reset] starts a fresh sequence with the
     * default multiplier.
     */
    @Test
    fun tapAfterResetStartsFreshSequence() {
        val calculator = TapTempoCalculator()
        calculator.tapAll(0, 500, 1000)
        calculator.reset()

        val state = calculator.tap(2000)
        assertEquals(1, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertEquals(TapTempoState.DEFAULT_MULTIPLIER, state.multiplier, DELTA)
    }

    /**
     * Verifies that the state returned by [TapTempoCalculator.tap] matches the one exposed by
     * [TapTempoCalculator.state].
     */
    @Test
    fun tapReturnsUpdatedStateEqualToStateGetter() {
        val calculator = TapTempoCalculator()
        listOf(0L, 500L, 1000L, 3500L).forEach { timestampMs ->
            assertEquals(calculator.tap(timestampMs), calculator.state)
        }
    }

    /**
     * Verifies that a custom reset timeout is used instead of the default.
     */
    @Test
    fun customResetTimeout() {
        val custom = TapTempoCalculator(resetTimeoutMs = 500)
        assertNotNull(custom.tapAll(0, 500).bpm)
        assertEquals(1, custom.tap(1001).tapCount)

        val default = TapTempoCalculator()
        default.tapAll(0, 500)
        assertEquals(3, default.tap(1001).tapCount)
    }

    /**
     * Taps each of the given timestamps in order.
     * @param timestampsMs Timestamps to tap, in milliseconds.
     * @return The state after the last tap.
     */
    private fun TapTempoCalculator.tapAll(vararg timestampsMs: Long): TapTempoState =
        timestampsMs.fold(state) { _, timestampMs -> tap(timestampMs) }

    private companion object {
        /** Tolerance for exact floating point comparisons. */
        const val DELTA = 1e-6

        /** Tolerance for comparisons affected by the trimmed mean. */
        const val OUTLIER_DELTA = 5.0
    }
}
