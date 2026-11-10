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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Unit tests for [BeatScheduler].
 *
 * The expectations are absolute frame positions, so a test that passes pins both the beat grid
 * and the absence of cumulative drift, not just the number of beats emitted.
 *
 * @author Rohan Khayech
 */
class BeatSchedulerTest {

    /**
     * Verifies that a fresh scheduler emits its first beat at frame 0 of the first block, as the
     * downbeat, without the caller having to [BeatScheduler.reset] it first.
     */
    @Test
    fun firstBeatIsAtFrameZeroWithBeatInBarZero() {
        val scheduler = BeatScheduler(SAMPLE_RATE, bpm = 120, beatsPerBar = 4)

        val offsets = mutableListOf<Int>()
        val beatsInBar = mutableListOf<Int>()
        scheduler.advance(DEFAULT_BLOCK) { offset, beatInBar ->
            offsets += offset
            beatsInBar += beatInBar
        }

        assertEquals(listOf(0), offsets)
        assertEquals(listOf(0), beatsInBar)
        assertEquals(DEFAULT_BLOCK.toLong(), scheduler.framePosition)
    }

    /**
     * Verifies that at 60 BPM and 1 kHz the beats land on multiples of the 1000-sample beat
     * interval, and that the position in the bar counts 0,1,2,3,0 over blocks of 100 frames.
     *
     * The beat exactly 5000 frames in is *not* part of this run: the loop compares with a strict
     * `<`, so a beat falling on the end of the last block belongs to the next one.
     * [beatOnBlockBoundaryIsEmittedAtOffsetZeroOfTheNextBlock] pins that half of the rule.
     */
    @Test
    fun beatsAreSpacedBySamplesPerBeat() {
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60)

        val beats = mutableListOf<Pair<Int, Int>>()
        scheduler.run(5000, 100) { absFrame, beatInBar -> beats += absFrame to beatInBar }

        assertEquals(listOf(0, 1000, 2000, 3000, 4000), beats.map { it.first })
        assertEquals(listOf(0, 1, 2, 3, 0), beats.map { it.second })
    }

    /**
     * Verifies that a beat landing exactly on the end of a block is emitted at offset 0 of the
     * following block instead of at an offset equal to the block size, which would be one past
     * the last sample the caller is about to render.
     */
    @Test
    fun beatOnBlockBoundaryIsEmittedAtOffsetZeroOfTheNextBlock() {
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60)
        val beats = mutableListOf<Int>()
        scheduler.run(5000, 100) { absFrame, _ -> beats += absFrame }
        assertEquals(listOf(0, 1000, 2000, 3000, 4000), beats)

        val offsets = mutableListOf<Int>()
        scheduler.advance(1) { offset, _ -> offsets += offset }

        assertEquals(listOf(0), offsets)
        assertEquals(5001L, scheduler.framePosition)
    }

    /**
     * Verifies that a whole minute of audio keeps every beat exactly on the 22050-sample grid,
     * i.e. that the interval never accumulates an error.
     *
     * The 121st beat sits exactly on the 60 s mark, which is one past the last frame of the run,
     * so it is emitted at offset 0 of the next block, as the strict comparison dictates.
     */
    @Test
    fun noCumulativeDriftOverOneMinute() {
        val scheduler = BeatScheduler(SAMPLE_RATE, bpm = 120)

        val beats = scheduler.beatFrames(2_646_000, DEFAULT_BLOCK)

        assertEquals(120, beats.size)
        assertEquals(List(120) { it * 22050 }, beats)
        assertTrue(beats.zipWithNext { previous, next -> next - previous }.all { it == 22050 })

        assertEquals(listOf(2_646_000), scheduler.beatFrames(1, 1))
    }

    /**
     * Verifies that a tempo whose beat interval is not a whole number of samples stays on the
     * ideal grid over ten minutes of audio.
     *
     * At 137 BPM and 44.1 kHz the interval is 19313.8686... samples, so every beat rounds one way
     * or the other by less than a sample. Rounding the pending position to a frame on each beat
     * would instead lose that fraction every time: after the 1370 beats of this run a per-beat
     * rounding is about 1190 samples (27 ms) out, and the error keeps growing with the length of
     * the session. Here each beat must stay within one frame of its ideal position `k * interval`,
     * which is exactly the tolerance of the single truncation applied when reporting the offset.
     */
    @Test
    fun noDriftWithNonIntegerSamplesPerBeat() {
        val bpm = 137
        val samplesPerBeat = SECONDS_PER_MINUTE * SAMPLE_RATE / bpm
        val totalFrames = 26_460_000

        val scheduler = BeatScheduler(SAMPLE_RATE, bpm = bpm)
        val beats = scheduler.beatFrames(totalFrames)

        val idealBeatCount =
            generateSequence(0) { it + 1 }.takeWhile { it * samplesPerBeat < totalFrames }.count()
        assertEquals(idealBeatCount, beats.size)

        beats.forEachIndexed { index, frame ->
            val drift = abs(frame - index * samplesPerBeat)
            assertTrue("beat $index at frame $frame drifted $drift frames", drift <= FRAME_TOLERANCE)
        }

        val boundaryBeatIndex = floor(totalFrames / samplesPerBeat).toInt()
        assertEquals(1370, boundaryBeatIndex)
        assertEquals(boundaryBeatIndex - 1, beats.lastIndex)

        val boundaryBeats = scheduler.beatFrames(1, 1)
        assertEquals(listOf(totalFrames), boundaryBeats)
    }

    /**
     * Verifies that the gap between consecutive beats is always one of the two whole numbers
     * bracketing the fractional beat interval, so the sequencer never emits a beat a whole sample
     * early or late to make up for a previous one.
     */
    @Test
    fun gapsAreFloorOrCeilOfSamplesPerBeat() {
        val bpm = 137
        val samplesPerBeat = SECONDS_PER_MINUTE * SAMPLE_RATE / bpm
        val shortestGap = floor(samplesPerBeat).toInt()
        val longestGap = ceil(samplesPerBeat).toInt()
        assertEquals(19313, shortestGap)
        assertEquals(19314, longestGap)

        val beats = BeatScheduler(SAMPLE_RATE, bpm = bpm).beatFrames(600_000)

        val gaps = beats.zipWithNext { previous, next -> next - previous }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.all { it == shortestGap || it == longestGap })
    }

    /**
     * Verifies that the reported offset is always a valid index into the block being advanced,
     * i.e. in `0 until blockSize`, and that several beats inside one block are reported in
     * increasing offset order so the caller can trigger their clicks in the right order.
     *
     * A block of one second is included so that a block covering several beats is exercised, not
     * only the at-most-one-beat-per-block case.
     */
    @Test
    fun offsetsAreAlwaysWithinTheBlock() {
        listOf(1, 7, 64, 256, 1000, SAMPLE_RATE).forEach { blockSize ->
            val scheduler = BeatScheduler(SAMPLE_RATE, bpm = 137)
            var remainingFrames = 10_000
            while (remainingFrames > 0) {
                val frames = minOf(blockSize, remainingFrames)
                val offsets = mutableListOf<Int>()
                scheduler.advance(frames) { offset, _ -> offsets += offset }

                offsets.forEach { offset ->
                    assertTrue("offset $offset outside a block of $frames", offset in 0 until frames)
                }
                offsets.zipWithNext { previous, next ->
                    assertTrue("offsets not increasing: $offsets", next > previous)
                }

                remainingFrames -= frames
            }
            assertEquals(10_000L, scheduler.framePosition)
        }
    }

    /**
     * Verifies that the position in the bar counts up to the metre and wraps back to the
     * downbeat, and that a one-beat bar reports the downbeat on every beat.
     */
    @Test
    fun beatInBarWrapsEveryBar() {
        val fourFour = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60, beatsPerBar = 4)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), fourFour.beatsInBar(8000, 1000))

        val oneFour = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60, beatsPerBar = 1)
        assertEquals(List(5) { 0 }, oneFour.beatsInBar(5000, 1000))
    }

    /**
     * Verifies that changing the tempo mid-beat keeps the fraction of the interval already
     * played, so the rest of that beat is sped up or slowed down instead of the beat being
     * jump-started (one interval far too short) or played out in full at the old tempo.
     *
     * In both cases a quarter of the beat has already been played when the tempo changes, so the
     * next beat must land a quarter of the old interval plus three quarters of the new one after
     * the previous beat: at 875 frames instead of the 500 of the old grid or the 1000 of a beat
     * restarted at the new tempo, and at 500 frames instead of the 1000 of the old grid or the
     * 250 of a beat restarted at the new tempo, followed one new interval later.
     */
    @Test
    fun tempoChangePreservesPhase() {
        val slower = BeatScheduler(SAMPLE_RATE_HZ, bpm = 120)
        assertEquals(listOf(0), slower.beatFrames(125, 25))
        slower.bpm = 60
        assertEquals(listOf(875), slower.beatFrames(1000, 25))

        val faster = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60)
        assertEquals(listOf(0), faster.beatFrames(250, 50))
        faster.bpm = 180
        assertEquals(listOf(500, 833), faster.beatFrames(800, 25))
        assertEquals(500.0, 0.25 * 1000.0 + 0.75 * (1000.0 / 3.0), FRAME_TOLERANCE)
    }

    /**
     * Verifies that setting the tempo it already has is a no-op, so repeatedly publishing the
     * same tempo from a control (e.g. on every recomposition) cannot nudge the beat grid.
     */
    @Test
    fun tempoChangeToSameTempoDoesNotMoveTheBeat() {
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, bpm = 120)
        val untouched = BeatScheduler(SAMPLE_RATE_HZ, bpm = 120)
        scheduler.run(300, 100) { _, _ -> }
        untouched.run(300, 100) { _, _ -> }

        scheduler.bpm = 120
        scheduler.bpm = MetronomeState.coerceBpm(120)
        assertEquals(300L, scheduler.framePosition)

        assertEquals(untouched.beatFrames(10_000), scheduler.beatFrames(10_000))
    }

    /**
     * Verifies that shortening the bar while a beat beyond the new last beat is pending clamps
     * that beat into the new bar, so every reported index stays in `0 until beatsPerBar` and the
     * accent moves to the new downbeat from the next beat on.
     */
    @Test
    fun beatsPerBarShrinkKeepsNextBeatInRange() {
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60, beatsPerBar = 4)
        assertEquals(listOf(0, 1, 2), scheduler.beatsInBar(3000, 1000))

        scheduler.beatsPerBar = 2

        val beatsInBar = scheduler.beatsInBar(4000, 500)
        assertEquals(listOf(1, 0, 1, 0), beatsInBar)
        assertEquals(1, beatsInBar.first())
        assertTrue(beatsInBar.all { it in 0..1 })
    }

    /**
     * Verifies that lengthening the bar mid-bar keeps the pending beat where it is, so the beat
     * that was about to be the downbeat of a short bar becomes an inner beat of a longer one.
     */
    @Test
    fun beatsPerBarGrowthKeepsNextBeat() {
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, bpm = 60, beatsPerBar = 2)
        assertEquals(listOf(0), scheduler.beatsInBar(1000, 1000))

        scheduler.beatsPerBar = 6

        assertEquals(listOf(1, 2, 3, 4, 5, 0), scheduler.beatsInBar(6000, 1000))
    }

    /**
     * Verifies that [BeatScheduler.reset] restarts the schedule at frame 0 with the downbeat
     * pending, and that the resulting beats are the same as those of a fresh scheduler.
     */
    @Test
    fun resetRestartsAtFrameZero() {
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, bpm = 120, beatsPerBar = 4)
        scheduler.run(5000, 256) { _, _ -> }
        assertEquals(5000L, scheduler.framePosition)

        scheduler.reset()
        assertEquals(0L, scheduler.framePosition)
        assertEquals(
            BeatScheduler(SAMPLE_RATE_HZ, bpm = 120, beatsPerBar = 4).beatFrames(5000),
            scheduler.beatFrames(5000),
        )

        scheduler.reset()
        val offsets = mutableListOf<Int>()
        val beatsInBar = mutableListOf<Int>()
        scheduler.advance(1) { offset, beatInBar ->
            offsets += offset
            beatsInBar += beatInBar
        }
        assertEquals(listOf(0), offsets)
        assertEquals(listOf(0), beatsInBar)
    }

    /**
     * Verifies that a tempo outside the supported range is clamped on construction and on
     * assignment, so a scheduler never runs at a tempo the rest of the metronome cannot express.
     */
    @Test
    fun bpmIsCoerced() {
        val slowest = BeatScheduler(SAMPLE_RATE_HZ, bpm = 0)
        assertEquals(MetronomeState.MIN_BPM, slowest.bpm)
        assertEquals(BeatScheduler(SAMPLE_RATE_HZ, bpm = 30).beatFrames(20_000), slowest.beatFrames(20_000))

        val fastest = BeatScheduler(SAMPLE_RATE_HZ, bpm = 1000)
        assertEquals(MetronomeState.MAX_BPM, fastest.bpm)
        assertEquals(
            BeatScheduler(SAMPLE_RATE_HZ, bpm = 300).beatFrames(20_000),
            fastest.beatFrames(20_000),
        )

        val clamped = BeatScheduler(SAMPLE_RATE_HZ, bpm = 120)
        val reference = BeatScheduler(SAMPLE_RATE_HZ, bpm = 120)
        clamped.run(500, 500) { _, _ -> }
        reference.run(500, 500) { _, _ -> }

        clamped.bpm = 5
        reference.bpm = MetronomeState.MIN_BPM

        assertEquals(MetronomeState.MIN_BPM, clamped.bpm)
        assertEquals(reference.beatFrames(20_000), clamped.beatFrames(20_000))
    }

    /**
     * Verifies that advancing by zero or a negative number of frames is rejected instead of
     * silently doing nothing or moving the schedule backwards, and that the rejection leaves the
     * frame position untouched.
     */
    @Test
    fun advanceRejectsNonPositiveFrameCount() {
        val scheduler = BeatScheduler(SAMPLE_RATE)

        assertThrows(IllegalArgumentException::class.java) { scheduler.advance(0) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { scheduler.advance(-1) { _, _ -> } }

        assertEquals(0L, scheduler.framePosition)
    }

    /**
     * Verifies that a scheduler cannot be built for a sample rate that is not positive, which the
     * constructor documents as rejected: the beat interval is derived from the rate, so a rate of
     * zero would make it infinite and no beat would ever be reported, and a negative one would make
     * it negative, i.e. schedule beats into the past.
     */
    @Test
    fun constructionRejectsANonPositiveSampleRate() {
        assertThrows(IllegalArgumentException::class.java) { BeatScheduler(sampleRateHz = 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatScheduler(sampleRateHz = -44_100) }
    }

    /**
     * Verifies that a block containing several beats reports every one of them, once and in order,
     * at the offsets the documented rule prescribes.
     *
     * The derivation, at 137 BPM and 44.1 kHz: the beat interval is 60 * 44100 / 137 = 19313.8686...
     * frames. The pending beat is held as that exact fractional frame and is reported when it falls
     * inside the block (strictly before the end), at its offset from the start of the block
     * truncated to a whole frame, and the interval is then added without rounding. The first beat is
     * at 0, so a one-second block of 44100 frames reports 0, then 19313.8686... -> 19313, then
     * 38627.7372... -> 38627, and 57941.6058... is past the end of the block, so it belongs to the
     * next one. Three beats: a scheduler that emitted only the first beat of a block would report
     * [0], and one that rounded the pending frame on every beat would report 19314 and 38628.
     */
    @Test
    fun aBlockContainingSeveralBeatsReportsEachOfThemOnce() {
        val scheduler = BeatScheduler(SAMPLE_RATE, bpm = 137)

        val offsets = mutableListOf<Int>()
        val beatsInBar = mutableListOf<Int>()
        scheduler.advance(SAMPLE_RATE) { offset, beatInBar ->
            offsets += offset
            beatsInBar += beatInBar
        }

        assertEquals(listOf(0, 19313, 38627), offsets)
        assertEquals(listOf(0, 1, 2), beatsInBar)
        assertEquals(SAMPLE_RATE.toLong(), scheduler.framePosition)
    }

    /**
     * Verifies that blocks much shorter than a beat still emit each beat exactly once, at the
     * right frame, and never duplicate or skip one.
     *
     * At 300 BPM and 44.1 kHz the interval is exactly 8820 samples, i.e. 882 blocks of 10 frames, so
     * every gap between reported beats is exactly 8820 and the second alternative some earlier
     * version of this test allowed (8821) is unreachable: it would have tolerated a rounding error
     * that this tempo cannot produce. The 51st beat sits exactly on the 10 s mark, so it is reported
     * at offset 0 of the block after it: 50 beats fall inside the first 10 s and the last one closes
     * the interval.
     */
    @Test
    fun blockShorterThanOneBeatStillEmitsOnce() {
        val scheduler = BeatScheduler(SAMPLE_RATE, bpm = 300)

        val beats = scheduler.beatFrames(441_000, 10)

        assertEquals(50, beats.size)
        val gaps = beats.zipWithNext { previous, next -> next - previous }
        assertTrue("gaps are not exactly one beat: $gaps", gaps.all { it == 8820 })
        assertEquals(8820, gaps.first())

        val lastBeats = scheduler.beatFrames(10, 10)
        assertEquals(listOf(441_000), lastBeats)
        assertEquals(51, beats.size + lastBeats.size)
    }

    /**
     * Advances the scheduler in blocks of the given size, reporting every beat in absolute
     * frames, i.e. relative to the start of the run rather than to the block it fell in.
     *
     * @param frames Total number of frames to advance.
     * @param block Size of the blocks to advance by; the last block is truncated to fit.
     * @param sink Receives the absolute frame of each beat and its position in the bar.
     */
    private fun BeatScheduler.run(frames: Int, block: Int, sink: (absFrame: Int, beatInBar: Int) -> Unit) {
        var remainingFrames = frames
        while (remainingFrames > 0) {
            val blockFrames = minOf(block, remainingFrames)
            val blockStart = framePosition
            advance(blockFrames) { offset, beatInBar ->
                sink((blockStart + offset).toInt(), beatInBar)
            }
            remainingFrames -= blockFrames
        }
    }

    /**
     * Advances the scheduler over the given stretch of audio and returns the absolute frame of
     * each beat emitted in it.
     *
     * @param frames Total number of frames to advance.
     * @param block Size of the blocks to advance by.
     * @return Absolute frame of every beat emitted, in order.
     */
    private fun BeatScheduler.beatFrames(frames: Int, block: Int = DEFAULT_BLOCK): List<Int> {
        val beats = mutableListOf<Int>()
        run(frames, block) { absFrame, _ -> beats += absFrame }
        return beats
    }

    /**
     * Advances the scheduler over the given stretch of audio and returns the position in the bar
     * of each beat emitted in it.
     *
     * @param frames Total number of frames to advance.
     * @param block Size of the blocks to advance by.
     * @return Position in the bar of every beat emitted, in order.
     */
    private fun BeatScheduler.beatsInBar(frames: Int, block: Int = DEFAULT_BLOCK): List<Int> {
        val beats = mutableListOf<Int>()
        run(frames, block) { _, beatInBar -> beats += beatInBar }
        return beats
    }

    private companion object {
        /** Sample rate of the audio rendered by the app, in frames per second. */
        const val SAMPLE_RATE = 44100

        /** Low sample rate used where the block arithmetic is easier to read. */
        const val SAMPLE_RATE_HZ = 1000

        /** Seconds in a minute, i.e. the factor between frames per beat and beats per minute. */
        const val SECONDS_PER_MINUTE = 60.0

        /** Block size used by the tests that do not care about one. */
        const val DEFAULT_BLOCK = 256

        /** Tolerance in frames, i.e. the truncation applied to a fractional beat position. */
        const val FRAME_TOLERANCE = 1.0
    }
}
