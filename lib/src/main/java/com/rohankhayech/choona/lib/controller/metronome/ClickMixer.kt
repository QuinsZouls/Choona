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

/**
 * Mixes the metronome's pre-rendered clicks into the blocks of audio the device renders, keeping
 * each started click alive across block boundaries until it has been played out in full.
 *
 * A click ([ClickSynthesizer.accent], [ClickSynthesizer.regular]) is about 40 ms, i.e. 1764 frames
 * at 44.1 kHz, while audio is rendered 256 frames at a time: a single click is therefore **about
 * seven blocks long** (1764 / 256 = 6.89). A renderer that only wrote the samples that fit in the
 * block in which the beat fired would cut the click to whatever is left of that block — between a
 * single frame (0.023 ms) and a whole block (5.8 ms) of its 40 ms, i.e. 14.5 % at best. That is
 * heard as a tick rather than a click, and worse, the amount heard *varies* from beat to beat
 * with where the beat landed in the block (a 256:1, ~48 dB spread), which turns an even click
 * track into a jittery one instead of merely a quiet one.
 *
 * ## Why per-voice cursors instead of a tail buffer
 *
 * The tempting fix is to write the samples that fit and carry "the rest" in a single tail buffer
 * for the next block. It cannot work: the remainder is up to 1508 frames, i.e. **six more
 * blocks**, so a single tail slot mixes at most 256 of them and silently drops the other 1252 —
 * 71 % of the click, and in the same variable way (the later the beat, the shorter the remainder,
 * so some beats keep more of the click than others). The mixer therefore holds, per voice, a
 * *cursor* into the click that survives as many blocks as the click needs: **[samples],
 * [positions] and [starts]** below are the whole state of a voice, and nothing is copied or
 * carried between blocks, the click is simply resumed where it stopped.
 *
 * ## Invariants
 *
 * * A voice is either free (`samples[voice] == null`) or playing, and a playing voice's cursor is
 *   in `0 until samples.size`. A voice whose cursor reaches `samples.size` is freed in the same
 *   pass, so a finished click never costs another block and [isIdle] is exact.
 * * The output of a voice is *contiguous*: the frame right after the click's start carries
 *   exactly sample `k`, and so does every frame after it until `k == size`, whatever the block
 *   boundaries. No sample is played twice, skipped or truncated.
 * * A start offset is only meaningful in the block in which the voice was scheduled: after every
 *   render pass [starts] is reset to 0, so a resumed voice begins at frame 0 of the block and a
 *   click scheduled for the current block is the only one that can begin mid-block.
 * * [render] always starts from silence: it zeroes the first `frameCount` entries of the output
 *   before mixing, so a caller never has to clear the buffer itself and a block that mixes
 *   nothing is silent rather than stale. The entries beyond `frameCount` are left untouched.
 * * The mix never clips in the supported configuration: a click is shorter than the shortest beat
 *   interval ([MetronomeState.MIN_BEAT_INTERVAL_MS] = 200 ms against a 40 ms click, a 5x margin),
 *   so at most one voice sounds at a time, and a single default click peaks at 0.55-0.9 of full
 *   scale. The saturating add in [render] is a **safety net, not the normal path** — it exists so
 *   that an unforeseen overlap degrades into a flat-topped, quiet-sounding click instead of an
 *   integer wraparound, which is heard as a hard buzz that sounds like a broken speaker.
 *   Subdivisions (two clicks per beat, 100 ms apart at the fastest tempo) or a tempo above
 *   [MetronomeState.MAX_BPM] would make clicks genuinely overlap and need a polyphonic mixer with
 *   headroom and per-voice gain; summing two full-scale clicks and clipping the result is not a
 *   substitute for mixing them at a level that fits.
 * * [render] allocates nothing and never blocks, because it runs on the audio thread: an
 *   allocation there can trigger a collection and a glitch, heard as a click that arrives late or
 *   not at all. The voices are preallocated at construction and the click buffers are only read.
 * * [maxVoices] bounds the polyphony, and scheduling a click with every voice busy throws instead
 *   of stealing one (see [scheduleClick]). That can only happen when the beat interval drops below
 *   the click length, which the supported tempo range forbids (see above), so the exception is a
 *   programming error rather than a runtime condition to handle.
 *
 * ## Threading
 *
 * This class is deliberately **not** thread-safe and uses no locks, no atomics and no
 * synchronisation: [scheduleClick], [render] and [reset] must all be called from the single
 * thread that renders audio. The owner of the mixer hands beats over to that thread — e.g. by
 * draining a queue of control events at the top of a block, or by scheduling from the [BeatScheduler]
 * callback that reports a beat inside the block being rendered — and never schedules from the UI
 * thread. A voice's cursor being moved by two threads at once would skip or repeat frames, which
 * is heard as a stutter in the middle of a click.
 *
 * @param accent Pre-rendered accented click, i.e. the downbeat. Must have the same length as
 *  [regular], since a voice addresses both with one cursor and one click length.
 * @param regular Pre-rendered ordinary click, i.e. every beat that is not the first of the bar.
 *  Must have the same length as [accent].
 * @param maxVoices Number of clicks that can be in flight at once. At least one. The default of
 *  two leaves room for one extra voice on top of the single voice the supported tempo range needs,
 *  so a beat arriving while the previous click is still finishing is mixed instead of throwing.
 *
 * @author Rohan Khayech
 */
class ClickMixer(
    private val accent: ShortArray,
    private val regular: ShortArray,
    private val maxVoices: Int = 2,
) {

    init {
        require(accent.size == regular.size) {
            "accent and regular must have the same length, were ${accent.size} and ${regular.size}"
        }
        require(maxVoices >= 1) { "maxVoices must be at least 1, was $maxVoices" }
        require(accent.isNotEmpty()) { "the click buffers must not be empty" }
    }

    private val samples = arrayOfNulls<ShortArray>(maxVoices)

    private val positions = IntArray(maxVoices)

    private val starts = IntArray(maxVoices)

    /**
     * Whether no click is currently in flight, i.e. every voice is free and the next [render] will
     * produce silence.
     *
     * Lets the owner stop pulling the audio track, or hand the render thread back, as soon as the
     * last click has been played out instead of guessing how long a click lasts; it becomes true
     * in the same render pass that plays a click's final frame, not one block later.
     *
     * Currently unused by the engine, because every session builds a fresh mixer and therefore
     * never has a click in flight to inspect. It is kept for a future session-reuse path, and the
     * tests exercise it.
     */
    val isIdle: Boolean
        get() {
            for (voice in 0 until maxVoices) {
                if (samples[voice] != null) {
                    return false
                }
            }
            return true
        }

    /**
     * Queues a click to begin at the given frame of the block that is about to be rendered,
     * taking the first free voice.
     *
     * The voice starts at the click's first sample and then keeps its own cursor, so the rest of
     * the click is played in the following blocks however many it needs (about seven for the
     * default 40 ms click at 44.1 kHz) without the caller tracking anything. The beat scheduler
     * reports the offset to use, so a beat that falls mid-block is heard on the sample it was
     * scheduled for rather than snapped to the start of the block, which would quantise the tempo
     * to the block rate (up to 5.8 ms of jitter at 256 frames, audible as an uneven pulse at fast
     * tempos).
     *
     * An offset at or beyond the block being rendered is not rejected, because the block length is
     * only known to [render]: the voice is deferred and begins at frame 0 of the next block, so a
     * beat reported after the caller has finished filling the current block still plays instead of
     * being dropped.
     *
     * @param atFrameInBlock Offset from the start of the block being rendered, in frames. Must not
     *  be negative.
     * @param accent Whether to play the accented downbeat click instead of the ordinary one, i.e.
     *  the buffer passed as [ClickMixer]'s `accent` rather than its `regular`.
     * @throws IllegalArgumentException If [atFrameInBlock] is negative.
     * @throws IllegalStateException If all [maxVoices] voices are busy, which the supported tempo
     *  range rules out: the click (40 ms) is five times shorter than the shortest beat interval
     *  ([MetronomeState.MIN_BEAT_INTERVAL_MS] = 200 ms), so a voice is always free again before
     *  the next beat is scheduled.
     */
    fun scheduleClick(atFrameInBlock: Int, accent: Boolean) {
        require(atFrameInBlock >= 0) {
            "atFrameInBlock must not be negative, was $atFrameInBlock"
        }
        var freeVoice = -1
        for (voice in 0 until maxVoices) {
            if (samples[voice] == null) {
                freeVoice = voice
                break
            }
        }
        check(freeVoice >= 0) {
            "all $maxVoices click voices are busy, which can only happen when the beat interval " +
                "drops below the click length; the supported tempo range keeps it at " +
                "${MetronomeState.MIN_BEAT_INTERVAL_MS} ms or longer"
        }
        samples[freeVoice] = if (accent) this.accent else regular
        positions[freeVoice] = 0
        starts[freeVoice] = atFrameInBlock
    }

    /**
     * Renders the given number of frames of mixed clicks into [buffer], starting from silence.
     *
     * The first [frameCount] entries are zeroed and then, for every voice in flight, the click's
     * samples are added one frame at a time from the voice's current cursor, beginning at the
     * start offset if the voice was scheduled for this block and at frame 0 otherwise. A voice
     * that reaches the end of its click in this pass is freed, so it is not rendered again; a voice
     * that does not is left exactly where it stopped, to be resumed by the next call. That is what
     * lets one click span the ~seven blocks it needs without being truncated to the block it
     * started in.
     *
     * Mixing is a saturating add: the sum of two samples is clamped into the [Short] range rather
     * than wrapped. Two [Short]s cannot overflow an [Int], so the sum is exact and the clamp is the
     * only approximation. It is a safety net for an overlap the tempo range makes impossible, not
     * a gain stage — see the class documentation for why a real polyphonic mix would need headroom
     * and per-voice gain.
     *
     * This method allocates nothing, takes no lock and calls nothing that can block, so it is safe
     * to call from the audio render thread with the device's block size.
     *
     * @param buffer Output buffer to mix into. Must hold at least [frameCount] frames; entries past
     *  [frameCount] are left untouched.
     * @param frameCount Number of frames to render, in `0..buffer.size`. Zero renders nothing and
     *  leaves the voices untouched.
     * @throws IllegalArgumentException If [frameCount] is negative or larger than [buffer].
     */
    fun render(buffer: ShortArray, frameCount: Int) {
        require(frameCount in 0..buffer.size) {
            "frameCount must be within 0..${buffer.size}, was $frameCount"
        }
        buffer.fill(0, 0, frameCount)

        for (voice in 0 until maxVoices) {
            val click = samples[voice] ?: continue
            var frame = starts[voice]
            var position = positions[voice]
            while (frame < frameCount && position < click.size) {
                buffer[frame] = saturate(buffer[frame].toInt() + click[position].toInt())
                frame++
                position++
            }
            if (position >= click.size) {
                samples[voice] = null
            } else {
                positions[voice] = position
            }
            starts[voice] = 0
        }
    }

    /**
     * Silences the mixer, dropping every click in flight and freeing all voices.
     *
     * Returns the mixer to exactly the state it was constructed in, so a stopped metronome starts
     * its next click from the beginning of the buffer instead of resuming a click that was cut
     * halfway (which would begin on a non-zero sample, i.e. with a pop, and leave the voice busy
     * for the next beat). Only the click buffers and [maxVoices] are kept, so resetting does not
     * change what the metronome sounds like. A freshly constructed mixer is already in this state,
     * so callers do not need to reset before starting.
     *
     * Currently unused by the engine, for the same reason as [isIdle]: a new mixer is constructed
     * per session, so there is never a voice left over to clear. It is kept for a future
     * session-reuse path, and the tests exercise it.
     */
    fun reset() {
        for (voice in 0 until maxVoices) {
            samples[voice] = null
            positions[voice] = 0
            starts[voice] = 0
        }
    }

    /**
     * Mixes one sample into another with a saturating add, i.e. clamps the sum into the [Short]
     * range instead of letting it wrap around.
     *
     * Both inputs are [Short]s, so their sum cannot overflow an [Int] and the clamp is the only
     * loss of precision: no temporary buffer and no wider accumulator is needed.
     *
     * @param sum Sum of the two samples, i.e. in `Short.MIN_VALUE * 2..Short.MAX_VALUE * 2`.
     * @return The clamped sum.
     */
    private fun saturate(sum: Int): Short =
        sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
