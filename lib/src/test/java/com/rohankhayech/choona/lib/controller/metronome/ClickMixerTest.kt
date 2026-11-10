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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [ClickMixer].
 *
 * The expected output is written out frame by frame on purpose: the mixer's whole job is to put
 * exactly the right sample at exactly the right frame, and every bug it can have — a click that
 * starts one frame early, a tail that is truncated at the block boundary, a voice that loops
 * instead of ending, a mix that wraps around instead of saturating — shows up as a wrong value at
 * a known index rather than as a wrong feeling about the sound.
 *
 * Most tests use a six-frame click made of `1, 2, 3, ...` so the expected frames can be read off
 * the index; [clickTailSurvivesIntoTheFollowingBlocks] uses the real [ClickSynthesizer] because
 * the cross-block behaviour is exactly what a click of one block or less cannot exercise.
 *
 * @author Rohan Khayech
 */
class ClickMixerTest {

    /**
     * Verifies that rendering with no click scheduled produces silence and leaves the mixer idle,
     * even when the buffer arrives full of garbage from the previous block.
     *
     * Starting from silence is the mixer's contract, so a caller never has to clear the buffer
     * itself; without it, a block with no click would replay whatever the device buffer happened to
     * hold, i.e. the tail of a previous click repeated at every silent beat.
     */
    @Test
    fun idleRenderProducesSilence() {
        val mixer = ClickMixer(click(), click())
        val buffer = ShortArray(BLOCK) { Short.MAX_VALUE }

        assertTrue("a fresh mixer is not idle", mixer.isIdle)

        mixer.render(buffer, BLOCK)

        assertArrayEquals(ShortArray(BLOCK), buffer)
        assertTrue("an idle mixer is not idle after rendering", mixer.isIdle)
    }

    /**
     * Verifies that a click scheduled at or beyond the end of the block being rendered is deferred
     * to the next one, where it begins at frame 0, instead of being dropped or written past the end
     * of the buffer.
     *
     * This is the branch of [ClickMixer.scheduleClick] that exists because the block length is only
     * known to [ClickMixer.render]: a beat reported after the caller has finished filling the block
     * is still a beat the player must hear, so it plays from the first frame of the next block
     * rather than being lost. Without the deferral the voice would either be silent forever or
     * write into the frames the caller is no longer filling.
     */
    @Test
    fun aClickScheduledBeyondTheBlockBeginsAtTheNextBlock() {
        val click = click()
        val mixer = ClickMixer(accent = click, regular = click)
        val buffer = ShortArray(CLICK_FRAMES)

        mixer.scheduleClick(CLICK_FRAMES, accent = false)
        mixer.render(buffer, CLICK_FRAMES)
        assertSilent(buffer, "the block the click was scheduled past")

        mixer.render(buffer, CLICK_FRAMES)
        assertRenders(buffer, click, "the click deferred to the next block")
        assertTrue("the deferred click was not played out", mixer.isIdle)
    }

    /**
     * Verifies that a click scheduled for a frame inside the block begins on exactly that frame,
     * with its first sample, so the beat is heard where the scheduler placed it instead of being
     * snapped to the start of the block.
     *
     * Snapping would quantise the tempo to the block rate: up to 255 frames, i.e. 5.8 ms at
     * 44.1 kHz, of error on every beat, which at 300 BPM is 2.9 % of the beat interval and is heard
     * as an uneven pulse.
     */
    @Test
    fun clickStartsAtTheScheduledFrame() {
        val click = click()
        val mixer = ClickMixer(accent = click, regular = click)
        val buffer = ShortArray(WIDE_BLOCK)

        mixer.scheduleClick(START_FRAME, accent = false)
        mixer.render(buffer, WIDE_BLOCK)

        for (frame in 0 until START_FRAME) {
            assertEquals("frame $frame before the click", 0, buffer[frame].toInt())
        }
        assertEquals("first sample of the click", click[0].toInt(), buffer[START_FRAME].toInt())
        assertEquals("second sample of the click", click[1].toInt(), buffer[START_FRAME + 1].toInt())
        assertEquals("third sample of the click", click[2].toInt(), buffer[START_FRAME + 2].toInt())
        for (frame in START_FRAME + click.size until WIDE_BLOCK) {
            assertEquals("frame $frame after the click", 0, buffer[frame].toInt())
        }
    }

    /**
     * Verifies the behaviour this whole class exists for: a click scheduled one frame before the
     * end of a 256-frame block keeps playing, sample by sample and without a gap, into the
     * following blocks, and stays audible for at least five more of them.
     *
     * This is the regression test for the truncated-click bug. Audio is rendered 256 frames at a
     * time and a click is 1764 frames, i.e. about seven blocks, so a renderer that only wrote the
     * samples that fit in the current block would cut this click to a single frame — a 0.023 ms
     * tick instead of a 40 ms click, and a *different* fraction for every beat depending on where
     * it landed in its block. A "carry the remainder in one tail buffer" renderer gets the first
     * following block right and then silently drops the rest of the remainder, which is six blocks
     * long. Both fail here: the second block is checked against the click's samples 1..256, so a
     * voice that restarted from its first sample instead of resuming its cursor mismatches on the
     * very first frame.
     */
    @Test
    fun clickTailSurvivesIntoTheFollowingBlocks() {
        val synth = ClickSynthesizer(SAMPLE_RATE)
        val click = synth.accent
        val mixer = ClickMixer(accent = synth.accent, regular = synth.regular)
        val buffer = ShortArray(BLOCK)

        mixer.scheduleClick(BLOCK - 1, accent = true)
        mixer.render(buffer, BLOCK)
        assertSilent(buffer, "the block the click starts in")

        mixer.render(buffer, BLOCK)
        for (frame in 0 until BLOCK) {
            assertEquals(
                "frame $frame of the second block, i.e. click sample ${frame + 1}",
                click[frame + 1].toInt(),
                buffer[frame].toInt(),
            )
        }

        var soundingBlocks = 1
        while (!mixer.isIdle && soundingBlocks < MAX_BLOCKS) {
            mixer.render(buffer, BLOCK)
            assertTrue(
                "block $soundingBlocks after the click start is silent",
                buffer.any { it.toInt() != 0 },
            )
            soundingBlocks++
        }

        assertTrue(
            "the click only survived $soundingBlocks blocks, i.e. fewer than the " +
                "$MIN_TAIL_BLOCKS a 1764-frame click needs after its first block",
            soundingBlocks >= MIN_TAIL_BLOCKS,
        )
        assertTrue("the click was never freed", mixer.isIdle)
    }

    /**
     * Verifies that a click is rendered exactly once and in full: the total absolute energy of the
     * output until the mixer goes idle is the total absolute energy of the click, and it takes
     * exactly as many frames as the click is long to get there.
     *
     * Both halves matter. Less energy means samples were dropped (the truncation bug); more energy
     * means samples were played twice or the click restarted; and a frame count above the click
     * length means the voice kept sounding after the click ended, i.e. silence would be mixed with
     * a stale cursor. Rendering one frame per block also pins the cursor to one sample per frame,
     * independently of the block size.
     */
    @Test
    fun totalEnergyMatchesTheClickLength() {
        val click = click()
        val mixer = ClickMixer(accent = click, regular = click)
        val buffer = ShortArray(1)

        mixer.scheduleClick(0, accent = false)

        var renderedFrames = 0
        var energy = 0L
        while (!mixer.isIdle && renderedFrames < MAX_BLOCKS) {
            mixer.render(buffer, 1)
            energy += abs(buffer[0].toInt()).toLong()
            renderedFrames++
        }

        val expectedEnergy = click.sumOf { abs(it.toInt()).toLong() }
        assertEquals("total absolute energy of the output", expectedEnergy, energy)
        assertEquals("frames rendered before the click ended", click.size, renderedFrames)
        assertTrue("the mixer is not idle once the click ended", mixer.isIdle)
    }

    /**
     * Verifies that each scheduled click plays the buffer it asked for, so the accented downbeat
     * and the ordinary beat are distinguishable, and that a second click is mixed after the first
     * has finished rather than being ignored or mixed into the remains of the first.
     */
    @Test
    fun accentAndRegularAreUsedPerScheduledClick() {
        val accent = ShortArray(CLICK_FRAMES) { ACCENT_SAMPLE.toShort() }
        val regular = ShortArray(CLICK_FRAMES) { REGULAR_SAMPLE.toShort() }
        val mixer = ClickMixer(accent = accent, regular = regular)
        val buffer = ShortArray(BLOCK)

        mixer.scheduleClick(0, accent = true)
        mixer.render(buffer, BLOCK)
        assertRenders(buffer, accent, "the accented click")

        mixer.scheduleClick(0, accent = false)
        mixer.render(buffer, BLOCK)
        assertRenders(buffer, regular, "the ordinary click")

        mixer.scheduleClick(0, accent = true)
        mixer.render(buffer, BLOCK)
        assertRenders(buffer, accent, "the accented click again")
    }

    /**
     * Verifies that two clicks sounding on the same frame are summed with saturation instead of
     * integer overflow: the output of two full-scale clicks is exactly [Short.MAX_VALUE], never a
     * negative value.
     *
     * The supported tempo range never makes clicks overlap (a 40 ms click against a 200 ms
     * interval), so this is the safety net rather than the normal path — but a wrap-around would
     * turn the loudest possible overlap into the quietest possible output, a hard buzz that sounds
     * like a broken speaker and is far more objectionable than a clipped peak. Both saturating
     * directions are checked, since a clamp written for one bound only is a classic half-fix.
     */
    @Test
    fun overlappingVoicesSaturateInsteadOfWrapping() {
        val fullScale = ShortArray(CLICK_FRAMES) { Short.MAX_VALUE }
        val mixer = ClickMixer(accent = fullScale, regular = fullScale, maxVoices = 2)
        val buffer = ShortArray(BLOCK)

        mixer.scheduleClick(0, accent = true)
        mixer.scheduleClick(0, accent = true)
        mixer.render(buffer, BLOCK)

        assertPlays(buffer, Short.MAX_VALUE.toInt(), "two full-scale clicks")
        assertTrue("the mix wrapped negative: ${buffer.toList()}", buffer.all { it >= 0 })

        val bottomScale = ShortArray(CLICK_FRAMES) { Short.MIN_VALUE }
        val silent = ClickMixer(accent = bottomScale, regular = bottomScale, maxVoices = 2)

        silent.scheduleClick(0, accent = true)
        silent.scheduleClick(0, accent = true)
        silent.render(buffer, BLOCK)

        assertPlays(buffer, Short.MIN_VALUE.toInt(), "two bottom-scale clicks")
        assertTrue("the mix wrapped positive: ${buffer.toList()}", buffer.all { it <= 0 })
    }

    /**
     * Verifies that scheduling a click when every voice is busy is reported as a programming error
     * rather than silently dropping the beat or evicting the click that is still playing.
     *
     * [MetronomeState.MIN_BEAT_INTERVAL_MS] (200 ms) against a 40 ms click makes this impossible in
     * practice, so the only way to reach it is a configuration the mixer cannot mix — and a beat
     * that is dropped in silence would be much harder to diagnose than an exception on the render
     * thread.
     */
    @Test
    fun schedulingBeyondCapacityFails() {
        val mixer = ClickMixer(accent = click(), regular = click(), maxVoices = 1)

        mixer.scheduleClick(0, accent = true)
        assertFalse("the single voice was not taken", mixer.isIdle)

        assertThrows(IllegalStateException::class.java) {
            mixer.scheduleClick(0, accent = true)
        }
    }

    /**
     * Verifies that a negative start frame is rejected instead of being used as a buffer index,
     * which would either throw an unhelpful [ArrayIndexOutOfBoundsException] from inside the render
     * loop or silently wrap the click into the last frame of the block.
     */
    @Test
    fun scheduleRejectsNegativeFrame() {
        val mixer = ClickMixer(accent = click(), regular = click())

        assertThrows(IllegalArgumentException::class.java) {
            mixer.scheduleClick(-1, accent = true)
        }
        assertTrue("a rejected schedule took a voice", mixer.isIdle)
    }

    /**
     * Verifies that [ClickMixer.reset] drops a click that is halfway through, so the rest of it is
     * not heard after the metronome was stopped and the voice is free for the next beat.
     *
     * Stopping mid-click is exactly what a pause does, and resuming that click on the next start
     * would begin on a non-zero sample — a pop, which reads as a fault in the app rather than as
     * the transport being restarted.
     */
    @Test
    fun resetSilencesAPartiallyRenderedClick() {
        val click = click()
        val mixer = ClickMixer(accent = click, regular = click)
        val buffer = ShortArray(HALF_CLICK_FRAMES)

        mixer.scheduleClick(0, accent = false)
        mixer.render(buffer, HALF_CLICK_FRAMES)
        for (frame in 0 until HALF_CLICK_FRAMES) {
            assertEquals(
                "frame $frame of the half-played click",
                click[frame].toInt(),
                buffer[frame].toInt(),
            )
        }
        assertFalse("the click did not survive the first render", mixer.isIdle)

        mixer.reset()

        assertTrue("reset left a voice active", mixer.isIdle)
        val silent = ShortArray(BLOCK) { Short.MAX_VALUE }
        mixer.render(silent, BLOCK)
        assertSilent(silent, "the block after a reset")
        assertTrue("the reset click resurfaced", mixer.isIdle)
    }

    /**
     * Verifies that a finished click is freed instead of looping: two clicks four blocks apart are
     * separated by three blocks of true silence.
     *
     * A voice that was not freed, or whose cursor was not reset, would repeat its last samples (or
     * the whole click) at every block in between. Silence is therefore the proof that the voice
     * really ended, and the second click proves that the voice is available again rather than lost.
     */
    @Test
    fun silenceIsRenderedBetweenClicks() {
        val click = click()
        val mixer = ClickMixer(accent = click, regular = click)
        val buffer = ShortArray(BLOCK) { Short.MAX_VALUE }

        mixer.scheduleClick(0, accent = false)
        mixer.render(buffer, BLOCK)
        assertRenders(buffer, click, "the first click")
        assertTrue("the short click was not freed in the block it played in", mixer.isIdle)

        repeat(SILENT_BLOCKS_BETWEEN_CLICKS) { block ->
            buffer.fill(Short.MAX_VALUE)
            mixer.render(buffer, BLOCK)
            assertSilent(buffer, "block $block between the two clicks")
        }

        mixer.scheduleClick(0, accent = false)
        mixer.render(buffer, BLOCK)
        assertRenders(buffer, click, "the second click")
    }

    /**
     * Verifies that a frame count outside the buffer is rejected instead of throwing an
     * [ArrayIndexOutOfBoundsException] from the middle of the mix, i.e. after some voices have
     * already been advanced, which would leave the cursors in a state the caller cannot reason
     * about.
     *
     * A frame count of zero is accepted and renders nothing at all, which is what a device that
     * asks for an empty block needs.
     */
    @Test
    fun renderRejectsFrameCountBeyondTheBuffer() {
        val mixer = ClickMixer(accent = click(), regular = click())
        val buffer = ShortArray(WIDE_BLOCK) { Short.MAX_VALUE }

        assertThrows(IllegalArgumentException::class.java) {
            mixer.render(buffer, WIDE_BLOCK + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mixer.render(buffer, -1)
        }

        mixer.render(buffer, 0)
        assertArrayEquals(ShortArray(WIDE_BLOCK) { Short.MAX_VALUE }, buffer)
    }

    /**
     * Verifies that the constructor rejects the click buffers it cannot mix.
     *
     * The two voices are addressed with one cursor and one click length, so buffers of different
     * lengths would either cut one voice short or read past its end, depending on which one was
     * scheduled — an intermittent fault that would depend on the beat's position in the bar.
     * A mixer with no voices could not schedule anything, and empty click buffers would divide the
     * usable length by zero, so both are rejected up front.
     */
    @Test
    fun constructorRejectsMismatchedClicks() {
        assertThrows(IllegalArgumentException::class.java) {
            ClickMixer(accent = ShortArray(CLICK_FRAMES), regular = ShortArray(CLICK_FRAMES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickMixer(accent = ShortArray(0), regular = ShortArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickMixer(accent = ShortArray(CLICK_FRAMES), regular = ShortArray(CLICK_FRAMES), maxVoices = 0)
        }
    }

    /**
     * Asserts that the buffer holds the given click from frame 0 and silence after it, i.e. that
     * exactly one click was mixed and that it ended where the click buffer ends.
     *
     * @param buffer Buffer that was rendered.
     * @param click Click expected on the matching frames of the buffer.
     * @param what Description of the click being checked, used in failure messages.
     */
    private fun assertRenders(buffer: ShortArray, click: ShortArray, what: String) {
        for (frame in click.indices) {
            assertEquals("frame $frame of $what", click[frame].toInt(), buffer[frame].toInt())
        }
        for (frame in click.size until buffer.size) {
            assertEquals("frame $frame after $what", 0, buffer[frame].toInt())
        }
    }

    /**
     * Asserts that the buffer holds the given sample on its first [CLICK_FRAMES] frames and nothing
     * else, i.e. that one click of a uniform test buffer was mixed.
     *
     * @param buffer Buffer that was rendered.
     * @param sample Expected sample value on every frame of the click.
     * @param what Description of the click being checked, used in failure messages.
     */
    private fun assertPlays(buffer: ShortArray, sample: Int, what: String) {
        for (frame in 0 until CLICK_FRAMES) {
            assertEquals("frame $frame of $what", sample, buffer[frame].toInt())
        }
        for (frame in CLICK_FRAMES until buffer.size) {
            assertEquals("frame $frame after $what", 0, buffer[frame].toInt())
        }
    }

    /**
     * Asserts that the buffer is entirely silent.
     *
     * @param buffer Buffer that was rendered.
     * @param what Description of the block being checked, used in failure messages.
     */
    private fun assertSilent(buffer: ShortArray, what: String) {
        for (frame in buffer.indices) {
            assertEquals("frame $frame of $what", 0, buffer[frame].toInt())
        }
    }

    /**
     * Builds the click used by most tests: six frames of `1, 2, 3, 4, 5, 6`.
     *
     * Each sample is distinct and none is zero, so the frame a sample lands on and the direction
     * the cursor moves are both readable straight off the output.
     *
     * @return A test click of [CLICK_FRAMES] frames.
     */
    private fun click(): ShortArray = ShortArray(CLICK_FRAMES) { (it + 1).toShort() }

    private companion object {
        /** Sample rate of the audio rendered by the app, in frames per second. */
        const val SAMPLE_RATE = 44100

        /** Size of the audio blocks the device renders, in frames. */
        const val BLOCK = 256

        /** Block size that leaves room for a click that starts and ends inside one block. */
        const val WIDE_BLOCK = 16

        /** Frame of [WIDE_BLOCK] at which the placement tests schedule their click. */
        const val START_FRAME = 4

        /** Number of frames in the short test click. */
        const val CLICK_FRAMES = 6

        /** Number of frames of the test click played by the reset test, i.e. half of it. */
        const val HALF_CLICK_FRAMES = CLICK_FRAMES / 2

        /** Sample value of the accented test click. */
        const val ACCENT_SAMPLE = 1000

        /** Sample value of the ordinary test click. */
        const val REGULAR_SAMPLE = 2000

        /** Blocks of silence expected between two clicks scheduled far apart. */
        const val SILENT_BLOCKS_BETWEEN_CLICKS = 3

        /**
         * Fewest blocks a 1764-frame click must still sound in after the block it starts in,
         * i.e. the "about seven blocks" of the class documentation.
         */
        const val MIN_TAIL_BLOCKS = 5

        /**
         * Upper bound on the number of render passes a test will perform, so a voice that never
         * ends fails an assertion instead of hanging the build.
         */
        const val MAX_BLOCKS = 64
    }
}
