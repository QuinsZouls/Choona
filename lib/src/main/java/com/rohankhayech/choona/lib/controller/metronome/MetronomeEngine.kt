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

import com.rohankhayech.choona.lib.model.error.MetronomeException
import com.rohankhayech.choona.lib.model.metronome.BeatPulse
import com.rohankhayech.choona.lib.model.metronome.MetronomeState

/**
 * Why the metronome stopped without being asked to.
 *
 * A stop reason is reported exactly once, through the `onStopped` callback of
 * [MetronomeEngine.start], and it always arrives after the engine has already stopped itself: the
 * receiver only has to settle the playing state, it never has to release the audio output, and
 * calling [MetronomeEngine.stop] from inside the callback is safe. A stop the app asks for itself
 * with [MetronomeEngine.stop] is not a reason and is never reported.
 *
 * @author Rohan Khayech
 */
enum class MetronomeStopReason {
    /**
     * Audio focus was taken by someone else, e.g. another player started or a notification sound
     * took over. The metronome gives way instead of mixing with what the user is listening to.
     */
    FOCUS_LOST,

    /**
     * The playback was cut short by the system, e.g. an incoming call, so it can be resumed when
     * the interruption is over rather than being treated as a failure.
     */
    INTERRUPTED,

    /**
     * The engine could not play: the output could not be acquired, the track could not be
     * created, or writing to it failed while playing.
     */
    FAILED,
}

/**
 * Plays the metronome: the audio side of it, behind a seam that the ViewModel owns.
 *
 * ## Why this is an interface
 *
 * The only implementation in the app is `MetronomePlayer`, which renders the click onto an
 * `AudioTrack` and therefore needs a device with an audio output. It cannot be created on the
 * host JVM, so the ViewModel that drives the transport would be untestable if it depended on it
 * directly. It depends on this interface instead, so the transport, the tempo controls and the
 * lifecycle of a session can be exercised by unit tests on the host JVM with the test fake, and
 * no Android class leaks into the seam: this file is what keeps the ViewModel host-testable.
 *
 * One engine instance drives one output. It is owned by the ViewModel and lives and dies with it,
 * so there is no shared global player and no [start] that outlives its owner.
 *
 * ## Starting
 *
 * [start] begins playback and returns immediately; it must not block the caller, because it is
 * called from the UI thread by a button press. The audio itself runs on the engine's own render
 * thread, and the callbacks below are delivered from there.
 *
 * Calling [start] while the engine is already playing is a **no-op**: the session that is
 * running carries on untouched and the arguments are ignored. Unlike `Tuner.start`, which throws
 * when it is started twice, this must not throw. The transport is a toggle, so a double tap —or
 * two taps racing, or a tap that arrives right after a lifecycle restore— is a normal thing for a
 * user to do; an exception here would surface as a crash from a button press, while the behaviour
 * the user asked for is simply "keep playing". A second tap on play is a request for the
 * metronome, not a programming error.
 *
 * A session that cannot start at all (no audio output, track not created) does not throw either:
 * it is reported once through `onStopped` as [MetronomeStopReason.FAILED], with the engine
 * already stopped, so there is a single place to handle every way playback can fail and a failure
 * surfaces as a message rather than a crash. The detail can be carried as a [MetronomeException].
 *
 * ## Beats
 *
 * `onPulse` is invoked on the engine's own render thread, once per beat, in order, for as long as
 * the session lasts. It must be cheap and non-blocking: it runs on the thread that also renders
 * the click, so allocating, locking or waiting there delays the click and can be heard. The
 * receiver must not touch the UI directly; it should hand the pulse over —e.g. publish it on a
 * state holder the UI observes— and let the UI thread do the drawing.
 *
 * [BeatPulse.count] is monotonically increasing for the whole `start`..`stop` session and starts
 * again from 0 on the next [start], so it identifies a beat within a session and never repeats
 * while the session runs.
 *
 * ## Stopping unexpectedly
 *
 * `onStopped` is invoked **once**, on the engine's own thread, and only when the engine stops for
 * a reason *other* than a [stop] call: focus lost, interrupted, or failed. It is always delivered
 * after the engine has already stopped itself, so the receiver can call [stop] again without
 * harm, and [isPlaying] is already `false` by the time it runs. A plain [stop] does not invoke it:
 * the caller already knows, and reporting it would make every tap on pause look like a failure.
 *
 * ## Configuration
 *
 * [setTempo], [setBeatsPerBar] and [setAccentFirstBeat] only ever affect a session that is already
 * playing, where they take effect from the next beat —immediately for the accent. Called while
 * stopped they do nothing, which is safe because [start] is handed the whole configuration and the
 * engine keeps none of it between sessions. They never throw for an out-of-range value: they clamp
 * with [MetronomeState.coerceBpm] and [MetronomeState.coerceBeatsPerBar], since the value comes
 * from a control or a stored preference and an invalid one is a normal thing to receive, not a bug
 * worth crashing the screen over.
 *
 * ## Threading summary
 *
 * [start], [stop] and the setters may be called from the UI thread, [stop] from any thread, and
 * [isPlaying] may be read from any thread. The callbacks arrive on the render thread. The engine
 * serialises all of that itself; callers need no lock.
 *
 * @author Rohan Khayech
 */
interface MetronomeEngine {

    /**
     * Whether the engine is currently playing.
     *
     * Safe to read from any thread. It is `false` before the first [start], after [stop] returns,
     * and from the moment the engine stops on its own, which is why a receiver of `onStopped` can
     * read it to confirm the session is over.
     */
    val isPlaying: Boolean

    /**
     * Starts playing the click at the given configuration and returns immediately.
     *
     * Does not block the caller: the audio is rendered on the engine's own thread, and this call
     * only has to set the session up. Returns right away even when the output takes a moment to
     * be acquired, so a button press never freezes the UI.
     *
     * Calling it while already playing is a no-op, and the arguments are ignored: see the note on
     * the interface about the transport being a toggle. Calling it after a session has ended
     * starts a new one, with [BeatPulse.count] starting again from 0.
     *
     * A session that cannot start reports [MetronomeStopReason.FAILED] once through [onStopped],
     * with the engine already stopped and [isPlaying] `false`. This call does not throw.
     *
     * @param bpm Initial tempo in beats per minute, clamped into
     *  [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM].
     * @param beatsPerBar Initial number of beats in a bar, clamped into
     *  [MetronomeState.MIN_BEATS_PER_BAR]..[MetronomeState.MAX_BEATS_PER_BAR].
     * @param accentFirstBeat Whether the first beat of the bar is played louder.
     * @param onPulse Invoked on the render thread for every beat of the session, in order, with
     *  the beat position in the bar and the running beat count. Must be cheap and non-blocking,
     *  and must not touch the UI.
     * @param onStopped Invoked once, on the engine's own thread, if the session ends for a reason
     *  other than [stop], after the engine has already stopped itself.
     */
    fun start(
        bpm: Int,
        beatsPerBar: Int,
        accentFirstBeat: Boolean,
        onPulse: (BeatPulse) -> Unit,
        onStopped: (MetronomeStopReason) -> Unit,
    )

    /**
     * Changes the tempo of the session that is playing.
     *
     * Safe to call while playing, where it applies from the next beat so the beat in progress is
     * not restarted. While stopped it does nothing at all, and that is safe precisely because the
     * engine keeps no configuration between sessions: [start] hands over the tempo, the metre and
     * the accent together, so the next session cannot begin from a value set in the gap. An
     * out-of-range value is clamped with [MetronomeState.coerceBpm], never rejected.
     *
     * @param bpm Tempo in beats per minute, possibly out of range.
     */
    fun setTempo(bpm: Int)

    /**
     * Changes the metre of the session that is playing.
     *
     * Safe to call while playing, where it applies from the next beat. While stopped it does
     * nothing, for the same reason as [setTempo]. An out-of-range value is clamped with
     * [MetronomeState.coerceBeatsPerBar], never rejected.
     *
     * @param beatsPerBar Number of beats in a bar, possibly out of range.
     */
    fun setBeatsPerBar(beatsPerBar: Int)

    /**
     * Turns the accent on the first beat of the bar on or off.
     *
     * Safe to call while playing, where it applies immediately. While stopped it does nothing, for
     * the same reason as [setTempo].
     *
     * @param accentFirstBeat Whether the first beat of the bar is played louder.
     */
    fun setAccentFirstBeat(accentFirstBeat: Boolean)

    /**
     * Stops playing, whether or not the engine is playing.
     *
     * Idempotent and callable from any thread. It blocks the caller for **at most 250 ms** —it
     * stops the render thread and waits for the output to be released— and when it returns
     * [isPlaying] is `false`. An honest stop returns in tens of milliseconds, because the render
     * thread can only ever be inside a `write` of one 46 ms buffer; the 250 ms ceiling is there for
     * the case that is not honest, a device that has wedged its own audio thread in that write, and
     * it exists so that a pause cannot leave the engine claiming an output forever. It does not
     * invoke `onStopped`: an asked-for stop is not a reason, and reporting it would make every
     * pause look like an interruption.
     */
    fun stop()
}
