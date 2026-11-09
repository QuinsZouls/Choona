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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Calculates a tempo in beats per minute from a sequence of taps.
 *
 * Each tap is registered with a monotonically increasing timestamp in milliseconds
 * and appended to the current sequence. A tap that arrives more than [resetTimeoutMs]
 * after the previous one starts a new sequence, discarding any previously registered
 * taps; the tempo [TapTempoState.multiplier] is only restored by [reset], so a manual
 * tempo adjustment survives a pause between sequences. Taps that are not strictly later
 * than the last registered tap, and taps closer than [MIN_INTERVAL_MS] to it, are
 * ignored. The sequence only retains the last [maxTaps] taps, so the detected tempo
 * follows the most recent playing.
 *
 * The tempo is derived from the intervals between consecutive taps as follows. Up to two
 * intervals the plain mean is used. From [MIN_TRIMMED_INTERVALS] intervals onwards the
 * median interval is computed and the intervals further than [OUTLIER_TOLERANCE]
 * (relative to the median) away from it are discarded; the median is the real one, i.e.
 * the average of the two central values when the number of intervals is even, so a tap
 * pattern that keeps alternating between two intervals does not flip the detected tempo
 * with the parity of the tap count. The retained intervals are then averaged, so the
 * detected tempo follows the intervals the taps actually sustain: an interval that is
 * discarded as an outlier is never reintroduced through the mean of the whole set, which
 * would let a couple of mistimed taps drag the tempo away from the beat being tapped.
 * Only when nothing survives the trimming, which an even number of intervals can cause
 * because the median then sits halfway between two values, the mean of all intervals is
 * used, so the detected tempo is never NaN.
 *
 * The detected tempo is reported as [TapTempoState.steady] when at least
 * [MIN_INTERVALS_FOR_STEADY] intervals are available and the coefficient of variation of
 * all registered intervals is within [STEADY_TOLERANCE]: steadiness is assessed over every
 * registered interval, so it reflects the actual regularity of the taps, while trimming
 * only applies to the detected tempo to discard an atypical interval. A regularly
 * alternating pattern is therefore not reported as steady, even when trimming narrows the
 * detected tempo down to a single interval value.
 *
 * @param resetTimeoutMs Maximum interval in milliseconds between two taps before the
 *  tap sequence is restarted.
 * @param maxTaps Maximum number of taps retained in the current sequence.
 *
 * @author Rohan Khayech
 */
class TapTempoCalculator(
    private val resetTimeoutMs: Long = DEFAULT_RESET_TIMEOUT_MS,
    private val maxTaps: Int = DEFAULT_MAX_TAPS,
) {

    private val taps = ArrayDeque<Long>()
    private var multiplier = TapTempoState.DEFAULT_MULTIPLIER

    /**
     * Snapshot of the current tap sequence, recomputed on each access.
     * @return The detected tempo, trimmed mean interval, tap count, steady flag and multiplier.
     */
    val state: TapTempoState
        get() {
            val intervals = intervals()
            if (intervals.isEmpty()) {
                return TapTempoState(tapCount = taps.size, multiplier = multiplier)
            }

            val mean = trimmedMean(intervals)
            val steady = isSteady(intervals)

            return TapTempoState(
                bpm = 60_000.0 / mean,
                intervalMs = mean,
                tapCount = taps.size,
                steady = steady,
                multiplier = multiplier,
            )
        }

    /**
     * Registers a tap at the given timestamp.
     *
     * The tap restarts the sequence if it arrives after [resetTimeoutMs] has elapsed
     * since the previous tap, keeping the tempo multiplier. Otherwise the tap is ignored
     * if it is not strictly later than the previous tap or if it arrives less than
     * [MIN_INTERVAL_MS] after it, which guards against accidental double taps.
     *
     * @param timestampMs Time of the tap in milliseconds, from a monotonic clock.
     * @return The state of the tap sequence after registering the tap.
     */
    fun tap(timestampMs: Long): TapTempoState {
        val lastTap = taps.lastOrNull()
        if (lastTap != null && timestampMs - lastTap > resetTimeoutMs) {
            taps.clear()
        } else if (lastTap != null &&
            (timestampMs <= lastTap || timestampMs - lastTap < MIN_INTERVAL_MS)
        ) {
            return state
        }

        taps.addLast(timestampMs)
        while (taps.size > maxTaps) {
            taps.removeFirst()
        }

        return state
    }

    /**
     * Clears the tap sequence and resets the tempo multiplier to
     * [TapTempoState.DEFAULT_MULTIPLIER].
     *
     * This is the only operation that resets the multiplier: starting a new sequence after
     * the reset timeout keeps it.
     *
     * @return The state of the cleared tap sequence.
     */
    fun reset(): TapTempoState {
        taps.clear()
        multiplier = TapTempoState.DEFAULT_MULTIPLIER
        return state
    }

    /**
     * Doubles the tempo multiplier, up to [MAX_MULTIPLIER].
     * @return The state with the updated multiplier.
     */
    fun doubleTempo(): TapTempoState {
        multiplier = (multiplier * 2.0).coerceAtMost(MAX_MULTIPLIER)
        return state
    }

    /**
     * Halves the tempo multiplier, down to [MIN_MULTIPLIER].
     * @return The state with the updated multiplier.
     */
    fun halveTempo(): TapTempoState {
        multiplier = (multiplier / 2.0).coerceAtLeast(MIN_MULTIPLIER)
        return state
    }

    private fun intervals(): List<Double> = taps.zipWithNext { previous, next ->
        (next - previous).toDouble()
    }

    /**
     * Assesses whether the taps are regular enough for the detected tempo to be steady.
     *
     * The coefficient of variation is computed over every registered interval, so trimmed
     * intervals still count towards the regularity of the taps.
     *
     * @param intervals All intervals between consecutive taps, in milliseconds.
     * @return True if the taps are steady, false otherwise.
     */
    private fun isSteady(intervals: List<Double>): Boolean {
        if (intervals.size < MIN_INTERVALS_FOR_STEADY) {
            return false
        }

        val mean = intervals.average()
        return mean > 0.0 && standardDeviation(intervals, mean) / mean <= STEADY_TOLERANCE
    }

    private fun standardDeviation(samples: List<Double>, mean: Double): Double =
        sqrt(samples.sumOf { (it - mean).pow(2) } / samples.size)

    /**
     * Averages the intervals, discarding the ones that are too far from the median.
     *
     * Averaging the survivors, rather than every interval, is what keeps a mistimed tap from
     * dragging the detected tempo away from the beat: once an interval is discarded as
     * atypical, letting it back in through a fallback would defeat the trimming exactly when
     * the taps are sloppiest. An even number of intervals can therefore leave no survivor at
     * all, as the median then sits halfway between two values, so the whole set is averaged in
     * that case to keep the detected tempo a real number.
     *
     * @param intervals All intervals between consecutive taps, in milliseconds.
     * @return The trimmed mean interval in milliseconds.
     */
    private fun trimmedMean(intervals: List<Double>): Double {
        if (intervals.size < MIN_TRIMMED_INTERVALS) {
            return intervals.average()
        }

        val sorted = intervals.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
        val retained = intervals.filter { abs(it - median) <= median * OUTLIER_TOLERANCE }
        return if (retained.isEmpty()) intervals.average() else retained.average()
    }

    companion object {
        /** Default maximum interval between taps before the sequence is restarted. */
        const val DEFAULT_RESET_TIMEOUT_MS = 2000L

        /** Default maximum number of taps retained in a sequence. */
        const val DEFAULT_MAX_TAPS = 8

        /** Maximum relative distance from the median interval for an interval to be retained. */
        const val OUTLIER_TOLERANCE = 0.5

        /** Maximum coefficient of variation of the intervals for a tempo to be considered steady. */
        const val STEADY_TOLERANCE = 0.05

        /** Lowest tempo multiplier that can be applied. */
        const val MIN_MULTIPLIER = 0.25

        /** Highest tempo multiplier that can be applied. */
        const val MAX_MULTIPLIER = 4.0

        /**
         * Minimum interval between two taps in milliseconds, which guards against accidental
         * double taps and caps the detected tempo at 600 BPM.
         */
        const val MIN_INTERVAL_MS = 100L

        /** Minimum number of intervals required to trim outliers. */
        private const val MIN_TRIMMED_INTERVALS = 3

        /** Minimum number of intervals required to assess the steadiness of the taps. */
        private const val MIN_INTERVALS_FOR_STEADY = 3
    }
}
