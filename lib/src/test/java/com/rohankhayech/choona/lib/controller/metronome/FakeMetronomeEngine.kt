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

import com.rohankhayech.choona.lib.model.metronome.BeatPulse

/**
 * A [MetronomeEngine] that stands in for the audio output of the device, so that the metronome
 * ViewModel —the transport, the tempo controls and the handling of an unexpected stop— can be
 * exercised on the host JVM, where there is no `AudioTrack` to play on.
 *
 * It keeps no thread and no audio: it records what it was asked to do and lets the test drive the
 * two things only the real engine could otherwise produce, [emitBeat] and [stopUnexpectedly]. A
 * test therefore reads the recorded state after an interaction with the ViewModel instead of
 * waiting for a beat, which keeps the tests deterministic and instant.
 *
 * The recorded state is:
 *
 * - [isPlaying], and the arguments of the last [start] ([startBpm], [startBeatsPerBar],
 *   [startAccentFirstBeat]).
 * - How many times each call was made ([startCount], [stopCount]), which is how a test proves
 *   that a second tap on play was swallowed by the engine rather than reaching the output.
 * - Every value passed to the setters ([tempoChanges], [beatsPerBarChanges], [accentChanges]),
 *   recorded **verbatim**, not clamped: the clamp belongs to the engine, so a test asserts the
 *   request the ViewModel made and not what an engine would have made of it.
 *
 * The semantics of the two failure modes it can be asked to produce are documented on [failOnStart]
 * and on the two methods.
 *
 * Like the production engine it is not thread-safe: a test drives it from one thread, and the
 * callbacks it invokes are invoked on the thread that caused them rather than on a render thread
 * that does not exist here.
 *
 * @author Rohan Khayech
 */
class FakeMetronomeEngine : MetronomeEngine {

    private var playing = false

    private var pulseCallback: ((BeatPulse) -> Unit)? = null

    private var stoppedCallback: ((MetronomeStopReason) -> Unit)? = null

    private val tempoValues = mutableListOf<Int>()

    private val beatsPerBarValues = mutableListOf<Int>()

    private val accentValues = mutableListOf<Boolean>()

    /** Whether the fake is currently playing, as [MetronomeEngine.isPlaying] reports it. */
    override val isPlaying: Boolean
        get() = playing

    /**
     * Whether the next [start] should fail instead of playing.
     *
     * When `true`, [start] records its arguments and its call as usual, then reports
     * [MetronomeStopReason.FAILED] through the `onStopped` of that same call —synchronously, since
     * there is no render thread here—, leaves [isPlaying] `false` and retains no callbacks, so a
     * later [emitBeat] is a no-op. That is the failure the production engine reports when the
     * output cannot be acquired or the track cannot be created, which is a report rather than a
     * thrown exception so that a button press can never crash the screen.
     *
     * It is `false` by default and is not reset by [start], so a test that wants a failing start
     * followed by a working one sets it back to `false` in between.
     */
    var failOnStart: Boolean = false

    /** Tempo in beats per minute passed to the last [start], or `null` if it was never called. */
    var startBpm: Int? = null
        private set

    /**
     * Number of beats per bar passed to the last [start], or `null` if it was never called.
     */
    var startBeatsPerBar: Int? = null
        private set

    /**
     * Whether the first beat was accented in the last [start], or `null` if it was never called.
     */
    var startAccentFirstBeat: Boolean? = null
        private set

    /**
     * Number of times [start] was called, including the calls it ignored because playback was
     * already running and the calls that failed.
     */
    var startCount: Int = 0
        private set

    /** Number of times [stop] was called, including the calls that had nothing to stop. */
    var stopCount: Int = 0
        private set

    /** Every tempo passed to [setTempo], in order and verbatim. */
    val tempoChanges: List<Int>
        get() = tempoValues

    /** Every number of beats per bar passed to [setBeatsPerBar], in order and verbatim. */
    val beatsPerBarChanges: List<Int>
        get() = beatsPerBarValues

    /** Every accent flag passed to [setAccentFirstBeat], in order. */
    val accentChanges: List<Boolean>
        get() = accentValues

    /**
     * Records the call and, unless [isPlaying] already, starts the fake session.
     *
     * A call while already playing only increments [startCount] and the recorded arguments: the
     * running session keeps its callbacks, [failOnStart] is not consulted, and nothing about the
     * playback changes, which is what the real engine does with a second tap on a transport that is
     * a toggle.
     *
     * @param bpm Tempo in beats per minute, recorded verbatim in [startBpm].
     * @param beatsPerBar Number of beats in a bar, recorded verbatim in [startBeatsPerBar].
     * @param accentFirstBeat Whether the first beat of the bar is accented, recorded verbatim in
     *  [startAccentFirstBeat].
     * @param onPulse Callback invoked by [emitBeat] while the session lasts.
     * @param onStopped Callback invoked by [stopUnexpectedly], or by a start that fails because of
     *  [failOnStart].
     */
    override fun start(
        bpm: Int,
        beatsPerBar: Int,
        accentFirstBeat: Boolean,
        onPulse: (BeatPulse) -> Unit,
        onStopped: (MetronomeStopReason) -> Unit,
    ) {
        startCount++
        startBpm = bpm
        startBeatsPerBar = beatsPerBar
        startAccentFirstBeat = accentFirstBeat

        if (playing) return

        if (failOnStart) {
            endSession()
            onStopped(MetronomeStopReason.FAILED)
            return
        }

        pulseCallback = onPulse
        stoppedCallback = onStopped
        playing = true
    }

    /** Records the requested tempo, leaving [isPlaying] untouched. */
    override fun setTempo(bpm: Int) {
        tempoValues.add(bpm)
    }

    /** Records the requested number of beats per bar, leaving [isPlaying] untouched. */
    override fun setBeatsPerBar(beatsPerBar: Int) {
        beatsPerBarValues.add(beatsPerBar)
    }

    /** Records the requested accent flag, leaving [isPlaying] untouched. */
    override fun setAccentFirstBeat(accentFirstBeat: Boolean) {
        accentValues.add(accentFirstBeat)
    }

    /**
     * Ends the session without reporting it, as the real engine does with an asked-for stop.
     *
     * Increments [stopCount] and leaves [isPlaying] `false`. `onStopped` is deliberately not
     * invoked: a plain stop is not a reason, and a test asserting that the ViewModel settled to
     * paused would not catch a fake that reported it anyway.
     */
    override fun stop() {
        stopCount++
        endSession()
    }

    /**
     * Emits a beat to the session started by [start], as the render thread of the real engine
     * would.
     *
     * Before the first [start], after [stop], after [stopUnexpectedly] and after a start that
     * failed, there is no session to emit into and this is a **no-op**: no callback is invoked.
     * The choice is deliberate, since the production engine can never emit outside a session, so
     * a test that emits early is asking for nothing and should not be broken by an exception;
     * it also makes "nothing happens after the session ended" assertable by simply emitting.
     *
     * @param count Number of beats emitted since the session started, as the engine would count
     *  them, i.e. [BeatPulse.count].
     * @param beatInBar Position of the beat within its bar, zero-based, as [BeatPulse.beatInBar].
     */
    fun emitBeat(count: Long, beatInBar: Int) {
        pulseCallback?.invoke(BeatPulse(count, beatInBar))
    }

    /**
     * Stops the session as the real engine does when it stops itself, reporting [reason].
     *
     * Sets [isPlaying] `false` and *then* invokes the `onStopped` captured by [start], which is the
     * order the production engine guarantees, so a test can assert that the receiver sees a
     * stopped engine. The callbacks are released before the callback runs, so a receiver that
     * stops the engine again —or emits a beat— finds a session that is already over.
     *
     * Invoked while no session is running it is a no-op, mirroring an engine that has nothing
     * left to report: there is no `onStopped` to invoke and [isPlaying] is already `false`.
     *
     * @param reason Reason the session ended, as the platform reported it.
     */
    fun stopUnexpectedly(reason: MetronomeStopReason) {
        if (!playing) return
        val onStopped = stoppedCallback
        endSession()
        onStopped?.invoke(reason)
    }

    private fun endSession() {
        playing = false
        pulseCallback = null
        stoppedCallback = null
    }
}
