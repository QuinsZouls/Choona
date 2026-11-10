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

package com.rohankhayech.choona.lib.view.viewmodel

import androidx.lifecycle.ViewModel
import com.rohankhayech.choona.lib.controller.metronome.MetronomeEngine
import com.rohankhayech.choona.lib.controller.metronome.MetronomeStopReason
import com.rohankhayech.choona.lib.controller.tempo.TapTempoCalculator
import com.rohankhayech.choona.lib.model.error.MetronomeException
import com.rohankhayech.choona.lib.model.metronome.BeatPulse
import com.rohankhayech.choona.lib.model.metronome.MetronomeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * ViewModel for the metronome screen: the transport, the tempo and metre controls, the tap-tempo
 * input, and the lifetime of the audio session.
 *
 * ## State
 *
 * [state] carries the rarely changing part of the screen —tempo, metre, first-beat accent and
 * whether the metronome is running— and [pulses] carries the per-beat signal on its own, so a
 * beat up to [MetronomeState.MAX_BPM] times a minute does not recompose everything that merely
 * displays the tempo. [error] is the single place a session that could not play is reported.
 *
 * ## The engine and its lifetime
 *
 * The audio side is reached only through [MetronomeEngine], and the engine itself is built by
 * [engineFactory] the first time the metronome is actually started, never in the constructor:
 * creating the production engine opens an output stream, and a screen that is merely looked at —
 * or resumed into— must not take the device's audio output away from whatever the user is
 * listening to. The instance is kept for the rest of the ViewModel's life and reused by every
 * later session, so starting and stopping repeatedly never builds a second engine.
 *
 * The factory is a required parameter with no default so that no Android class can leak into this
 * class through a default argument: on the host JVM the only engine available is the test fake.
 *
 * Building the engine is the one step of a start that [MetronomeEngine] does not cover with its
 * promise never to throw —the production engine resolves the `AudioManager` and builds an
 * `AudioFocusRequest` in its constructor— so a factory that throws is caught here and settled as a
 * failed start: no engine is kept, [error] reports it, and the next press tries again.
 *
 * ## Threading
 *
 * Control calls ([start], [stop], [toggle], the setters, [tap]) arrive on the UI thread, while
 * the engine delivers `onPulse` and `onStopped` from its own audio render thread. Both sides only
 * ever touch the thread-safe `MutableStateFlow`s below —no view, no lock, nothing that has to be
 * posted— so a beat published from the render thread is safe to hand to the UI thread, and the
 * render thread is never made to wait on the UI. No coroutine is involved anywhere: nothing here
 * has to suspend, because the engine already owns the asynchronous part. That is also why the
 * class is trivially unit-testable: every interaction is a plain call whose effect is visible in
 * the flows as soon as it returns.
 *
 * ## Ordering when starting
 *
 * [start] publishes `playing = true` *before* it calls the engine. A start that fails synchronously
 * reports [MetronomeStopReason.FAILED] through `onStopped`, which sets the flag back to `false`; the
 * other order would let that report be overwritten by a later `playing = true` and leave the screen
 * claiming to play after the failure. The flag must therefore be set first — this is load-bearing
 * and is not a cleanup.
 *
 * @param engineFactory Builds the audio engine the first time the metronome is started. Invoked
 *  once unless it fails, in which case the next start tries again.
 * @param tapTempo Calculator that derives a tempo from a sequence of taps.
 * @param clock Source of the current time in milliseconds, from a monotonic clock. Injectable so
 *  that the tap timestamps can be controlled in tests.
 *
 * @author Rohan Khayech
 */
class MetronomeViewModel(
    private val engineFactory: () -> MetronomeEngine,
    private val tapTempo: TapTempoCalculator = TapTempoCalculator(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {

    private val _state = MutableStateFlow(MetronomeState())

    /** Current tempo, metre, accent and playing state of the metronome. */
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val _pulses = MutableStateFlow<BeatPulse?>(null)

    /**
     * The most recent beat of the running session, or `null` while nothing is playing.
     *
     * Published once per beat from the engine's render thread. Its identity never repeats within
     * a session, so an effect keyed on it re-fires on every beat and only on a beat.
     */
    val pulses: StateFlow<BeatPulse?> = _pulses.asStateFlow()

    private val _error = MutableStateFlow<MetronomeException?>(null)

    /** Why the last session could not play, or `null` if it did not fail. */
    val error: StateFlow<MetronomeException?> = _error.asStateFlow()

    private var engine: MetronomeEngine? = null

    private val onPulse: (BeatPulse) -> Unit = { pulse -> _pulses.value = pulse }

    private val onStopped: (MetronomeStopReason) -> Unit = { reason -> onEngineStopped(reason) }

    /**
     * Starts playing, if it is not already.
     *
     * Does nothing when the metronome is already running, so a second tap on play never reaches the
     * engine and cannot restart the session under the user's fingers. Creating the engine happens
     * here, on the first start, and not before.
     *
     * The current [state] is passed to the engine, so the session always begins with the tempo,
     * metre and accent the user had chosen while it was stopped.
     *
     * A factory that fails to build the engine is a failed start, exactly like an engine that
     * reported [MetronomeStopReason.FAILED]: the screen goes back to stopped and says why, and no
     * half-built engine is kept, so this can simply be called again.
     */
    fun start() {
        if (_state.value.playing) {
            return
        }

        _state.update { it.copy(playing = true) }
        _error.value = null
        // A beat from the render thread can land after stop() cleared it, so clear the pulse here too.
        _pulses.value = null

        val config = _state.value
        val current = ensureEngine()
        if (current == null) {
            _state.update { it.copy(playing = false) }
            return
        }

        current.start(
            bpm = config.bpm,
            beatsPerBar = config.beatsPerBar,
            accentFirstBeat = config.accentFirstBeat,
            onPulse = onPulse,
            onStopped = onStopped,
        )
    }

    /**
     * Stops playing, whether or not it was playing.
     *
     * Idempotent and safe before the engine exists, in which case it only settles the playing flag.
     * An asked-for stop is never an error, so this never touches [error].
     */
    fun stop() {
        engine?.stop()
        _state.update { it.copy(playing = false) }
        _pulses.value = null
    }

    /**
     * Starts playing if stopped, and stops if playing.
     *
     * Reads the transport from [state] rather than from the engine, so it behaves the same before
     * the engine has been created.
     */
    fun toggle() {
        if (_state.value.playing) {
            stop()
        } else {
            start()
        }
    }

    /**
     * Sets the tempo, clamped into [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM].
     *
     * A value that leaves the tempo unchanged is ignored altogether, so holding a repeat button
     * against a bound cannot emit the same state or call the engine over and over. The engine is
     * only told about the change while playing: it is handed the whole configuration on [start],
     * so forwarding while stopped would be a call it has nothing to do with.
     *
     * @param bpm Tempo in beats per minute, possibly out of range.
     */
    fun setBpm(bpm: Int) {
        val coerced = MetronomeState.coerceBpm(bpm)
        if (coerced == _state.value.bpm) {
            return
        }

        _state.update { it.copy(bpm = coerced) }
        if (_state.value.playing) {
            engine?.setTempo(coerced)
        }
    }

    /**
     * Changes the tempo by [delta] beats per minute, clamped to the supported range.
     *
     * The sum is taken in a [Long] so that a huge [delta] saturates into the supported range
     * instead of wrapping around [Int]: the clamp decides where a tempo ends up, never an overflow
     * that happens before it.
     *
     * @param delta Change in beats per minute, added to the current tempo.
     */
    fun adjustBpm(delta: Int) {
        val moved = _state.value.bpm.toLong() + delta
        setBpm(moved.coerceIn(MetronomeState.MIN_BPM.toLong(), MetronomeState.MAX_BPM.toLong()).toInt())
    }

    /**
     * Sets the number of beats in a bar, clamped into
     * [MetronomeState.MIN_BEATS_PER_BAR]..[MetronomeState.MAX_BEATS_PER_BAR].
     *
     * Like [setBpm], an unchanged value is ignored and the engine is only told while playing, since
     * [start] hands it the configuration anyway.
     *
     * @param beatsPerBar Number of beats in a bar, possibly out of range.
     */
    fun setBeatsPerBar(beatsPerBar: Int) {
        val coerced = MetronomeState.coerceBeatsPerBar(beatsPerBar)
        if (coerced == _state.value.beatsPerBar) {
            return
        }

        _state.update { it.copy(beatsPerBar = coerced) }
        if (_state.value.playing) {
            engine?.setBeatsPerBar(coerced)
        }
    }

    /**
     * Turns the accent on the first beat of the bar on or off.
     *
     * Like [setBpm], an unchanged value is ignored and the engine is only told while playing, since
     * [start] hands it the configuration anyway.
     *
     * @param accentFirstBeat Whether the first beat of the bar is played louder.
     */
    fun setAccentFirstBeat(accentFirstBeat: Boolean) {
        if (accentFirstBeat == _state.value.accentFirstBeat) {
            return
        }

        _state.update { it.copy(accentFirstBeat = accentFirstBeat) }
        if (_state.value.playing) {
            engine?.setAccentFirstBeat(accentFirstBeat)
        }
    }

    /**
     * Registers a tap and adopts the tempo detected from the tap sequence.
     *
     * Tapping only sets the tempo: it never starts playback, so a player can find a tempo before
     * the first beat, and it never restarts the engine, so the beat keeps running while they tap.
     * The detected tempo is clamped by [setBpm], which is what keeps a very fast tap pattern inside
     * the supported range.
     */
    fun tap() {
        val displayBpm = tapTempo.tap(clock()).displayBpm ?: return
        setBpm(displayBpm.roundToInt())
    }

    /** Clears the tap sequence, including any tempo multiplier applied to it. */
    fun resetTaps() {
        tapTempo.reset()
    }

    /**
     * Stops the metronome and releases the audio output when the ViewModel is cleared, i.e. when
     * the metronome destination is popped or the activity finishes.
     *
     * This is *not* the path a backgrounded app takes: merely leaving the screen —or putting the app
     * in the background while it is showing— does not clear the ViewModel, so the stop that frees
     * the output there is the `ON_STOP` lifecycle effect in `MainLayout`. Both are needed, and
     * neither covers the other.
     */
    override fun onCleared() {
        stop()
    }

    /**
     * The engine, built by [engineFactory] on first use, or `null` if the factory failed.
     *
     * A failure reports itself as [error] and keeps nothing, so the next start builds the engine
     * again instead of the screen being stuck with a metronome that can never play.
     */
    private fun ensureEngine(): MetronomeEngine? {
        engine?.let { return it }
        return try {
            engineFactory().also { engine = it }
        } catch (e: Exception) {
            _error.value = MetronomeException(FAILURE_MESSAGE, e)
            null
        }
    }

    private fun onEngineStopped(reason: MetronomeStopReason) {
        _state.update { it.copy(playing = false) }
        _pulses.value = null
        if (reason == MetronomeStopReason.FAILED) {
            _error.value = MetronomeException(FAILURE_MESSAGE, null)
        }
    }

    companion object {
        /**
         * Message reported when the engine could not play, i.e. when the audio output was
         * unavailable, the track could not be created or writing to it failed.
         */
        const val FAILURE_MESSAGE = "Audio output is unavailable."
    }
}
