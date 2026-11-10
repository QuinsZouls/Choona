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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import com.rohankhayech.choona.lib.model.metronome.BeatPulse
import com.rohankhayech.choona.lib.model.metronome.MetronomeState
import kotlin.math.max

/**
 * The production [MetronomeEngine]: renders the click of [ClickSynthesizer] through [ClickMixer]
 * and writes it to an [AudioTrack], i.e. to the audio output of the device.
 *
 * ## Shape
 *
 * [start] acquires audio focus, builds one [RenderSession] and hands playback over to the thread of
 * that session; it then returns, so a tap on the transport never waits for the output. Everything
 * that follows — [setTempo], [setBeatsPerBar], [setAccentFirstBeat], [stop] — only publishes a
 * `@Volatile` value or interrupts that thread, so the control side never touches the track and
 * never has to wait for a lock held by the audio side.
 *
 * ## One thread owns the track (do not "simplify" this into a shared track)
 *
 * After [start], the [AudioTrack] belongs to the render thread alone: `play` and `write` happen in
 * its render loop and `stop` and `release` in that session's own `finally`. The only cross-thread
 * signals are the `@Volatile` fields of the session (the running flag, the stop reason and the
 * pending tempo, metre and accent) plus [Thread.interrupt]. This is not tidiness, it is what makes
 * [MetronomeEngine.stop] safe: a `stop` that called `release()` while the render thread was inside
 * a blocking `write()` would make that `write` raise `IllegalStateException` on a released track,
 * from the audio thread, and that has crashed apps on several OEM builds. With the ownership rule
 * the render thread is the only one that can be inside `write`, so it is the only one that releases
 * it, and the worst a `stop` can do is ask it to finish the block it is on.
 *
 * ## The numbers this class is built around
 *
 * * A block is [BLOCK_FRAMES] = 256 frames: **5.8 ms** at 44.1 kHz, i.e. ~172 blocks per second.
 * * The track is given at least [MIN_BUFFER_FRAMES] = 2048 frames, i.e. **46 ms**, which is eight
 *   blocks of slack: a delay of a few milliseconds on the render thread —a collection, a scheduler
 *   hiccup— drains that slack instead of being heard as a dropout.
 * * A click is 40 ms (1764 frames at 44.1 kHz), and the shortest beat interval the supported tempo
 *   range allows is [MetronomeState.MIN_BEAT_INTERVAL_MS] = 200 ms (8820 frames, **34 blocks**).
 *   That 5x margin is what lets [ClickMixer] keep one voice per click and what bounds the pending
 *   beats below.
 *
 * ## Publishing the pulse when it is heard, not when it is written
 *
 * The render thread runs **ahead** of the loudspeaker by roughly one buffer, at least the 2048
 * frames above (46 ms) and often more once the device adds its own buffering (50-100 ms is
 * typical). Publishing a pulse from the block that contains it would therefore highlight the beat
 * indicator 46-100 ms *before* the click is audible, with the error varying per device: on a fast
 * device the dot would lead the click slightly, on a slow one it would visibly pre-empt it, and no
 * single constant could correct it. So the pulse is published from the play head instead: after
 * every write, [AudioTrack.playbackHeadPosition] is read and the beats at or before it are
 * published, in order.
 *
 * That is a strict improvement rather than a solution, and the difference matters. The play head is
 * the frame the **mixer** has already consumed, not the frame at the speaker: everything written
 * after it is still in the HAL buffer and the DSP pipeline, which is the very 50-100 ms the
 * paragraph above prices. So the pulse still **leads** the click, by one block **plus the device's
 * output latency** —typically 10-40 ms on the fast path, more over A2DP or USB. What the play head
 * removes is the part this class can control: the app's own buffering ([MIN_BUFFER_FRAMES] and
 * whatever the write side piles up while the render thread is late). The fixed part of the error is
 * therefore device-independent and the remainder belongs to the device itself. The ~10 ms at which
 * a listener can see and hear a metronome disagree is consequently *approached* on a good device
 * and exceeded on a slow one; it is not met, and claiming otherwise is how a "sync" number in a
 * comment stops matching the audio. Correcting the remainder would need a constant tuned per
 * device, which is precisely what the play head replaced.
 *
 * Two consequences worth knowing before changing this:
 *
 * * The very first beat, at frame 0, is published by the same rule on the first pass through the
 *   loop, because the play head is already at 0. No special case is needed, and none must be added:
 *   a "publish the first beat immediately in [start]" would fire before the track is even playing.
 * * [AudioTrack.getTimestamp] is deliberately **not** used, even though it would describe the
 *   device's output latency —the residual above— and not just the app's buffering: it returns
 *   `false` on a large part of the hardware in use, so the code would need the play-head path
 *   anyway, and a correction that only runs on some devices is a correction that is only tested on
 *   some devices. The residual is therefore accepted on every device instead of being corrected on
 *   a lucky subset of them, which is the choice this class makes on purpose.
 *
 * ## Nothing is allocated per block on the render thread
 *
 * Allocating in the render loop —even a lambda— risks a collection on the audio thread, which is
 * heard as a click that arrives late or not at all. So: the PCM block is one preallocated
 * [ShortArray]; the clicks are pre-rendered once by [ClickSynthesizer]; [ClickMixer] mixes from
 * cursors; the beats waiting to be published live in a preallocated ring ([MAX_PENDING_BEATS]); and
 * the `onBeat` callback handed to [BeatScheduler.advance] is a **single bound reference created
 * once**, in the session, rather than a lambda that captures the loop's variables — the latter
 * would allocate one closure per block, i.e. ~172 objects per second of garbage on the audio
 * thread, which is exactly the kind of allocation a maintainer does not notice they added. The
 * same rule covers the callbacks: nothing at all is allocated per *block*, and the only allocations
 * on the render path happen on a beat — a [BeatPulse], built in
 * [MetronomePlayer.RenderSession.publishOldest], plus the two beat offsets that
 * [BeatScheduler.advance] auto-boxes when it calls the bound `onBeat` (a `(Int, Int) -> Unit` takes
 * `Integer`s, and [Integer.valueOf] caches only −128..127, so the beat in the bar is always cached
 * and the offset within the block is boxed for roughly half of the beats). That is a couple of
 * small cached or uncached `Integer`s, at most a handful per second —only on a beat, never per
 * block, up to 5 beats per second at [MetronomeState.MAX_BPM]— and the [BeatPulse] is unavoidable:
 * it is the interface's message to the receiver.
 *
 * ## Audio focus: give way, never resume
 *
 * The engine asks for [AudioManager.AUDIOFOCUS_GAIN] because practising to a metronome is a
 * primary media activity rather than a notification. On loss —full or transient, i.e. another
 * player taking over or a phone call— the session stops for good and reports
 * [MetronomeStopReason.FOCUS_LOST]; it never resumes on [AudioManager.AUDIOFOCUS_GAIN]. That is
 * deliberate: a metronome that started clicking again on its own in the middle of a call, or after
 * the user paused their music, would be worse than one that stops and waits to be asked. Ducking
 * ([AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK]) is ignored: turning a 40 ms click down does
 * not resolve the conflict, and the user asked for the click to be heard.
 *
 * [MetronomeStopReason.INTERRUPTED] is therefore never produced here: both ways the system cuts a
 * playback short —another app taking the output, and a call— arrive as a loss of focus, and
 * reporting them as such is what describes what happened. The reason stays in the enum for an
 * engine that can be *paused* rather than stopped, which would need a background service.
 *
 * The listener runs on the main looper and only publishes the stop reason and interrupts the render
 * thread; it never joins it (that would block the main thread on the audio thread) and never
 * publishes a pulse. The report itself is made by the render thread's `finally`, which keeps
 * `onStopped` on the engine's own thread as [MetronomeEngine] requires and keeps it a single report
 * however the session ended.
 *
 * The focus request itself is API-dependent: [AudioFocusRequest] from API 26, the deprecated
 * `requestAudioFocus(listener, STREAM_MUSIC, AUDIOFOCUS_GAIN)` on API 24 and 25, which is the
 * `minSdk` floor of the library. Abandoning uses the matching overload.
 *
 * ## Threading summary
 *
 * [start] and the setters are called from the UI thread, [stop] from any thread, [isPlaying] is
 * readable from any thread, and the callbacks arrive on the render thread. The two sides meet only
 * through `@Volatile` fields, so no caller ever waits for a lock held by the audio thread; the one
 * wait in the whole class is the bounded join of [stop], capped at [STOP_JOIN_TIMEOUT_MS].
 *
 * ## When it cannot play
 *
 * Every failure —focus denied, a session that cannot be created, no usable output, a track that
 * cannot be created, a write that is refused, a receiver that throws— ends the session by itself
 * and is reported once through the `onStopped` of [start] as [MetronomeStopReason.FAILED], never as
 * an exception: [start] is called from a button, and a crash there would be far worse than a
 * metronome that says it cannot play.
 * The detail of the failure is not carried anywhere, because the interface reports a reason and
 * nothing else; the screen turns FAILED into the message a player can act on, which is why
 * `MetronomeException` is not thrown by this class.
 *
 * ## Non-goals
 *
 * This is an in-app engine and nothing more: it declares no permission, starts no foreground
 * service, posts no notification and adds no manifest entry. It plays only while the app is in
 * front of the user and stops when the screen it belongs to goes away (through
 * `MetronomeViewModel.onCleared`), which is why it needs none of those things. A background
 * metronome would be a different class, with a service, a wakelock and a notification.
 *
 * @param context Any context: only [Context.getApplicationContext] is retained, so an engine that
 *  outlives the activity that created it —the ViewModel keeps one for the whole screen— cannot
 *  leak that activity.
 *
 * @author Rohan Khayech
 */
class MetronomePlayer(context: Context) : MetronomeEngine {

    /**
     * Application context, taken once in the constructor.
     *
     * The [Context] passed in is deliberately not kept as a property: the engine lives in the
     * ViewModel, so it can outlive the activity that built it, and holding an activity from here
     * would leak it (and its whole view hierarchy) for as long as the screen's state survives.
     *
     * [Context.getApplicationContext] is a platform type, so a null would otherwise be assigned to
     * a non-null property and surface as a bare `NullPointerException` —from the constructor, with
     * no stack frame naming what was null. It cannot be null on any real device; the explicit
     * failure is here so that a stub or a test double fails saying so.
     */
    private val appContext: Context =
        context.applicationContext ?: error("The context has no application context")

    /** Audio service, used for the sample rate hint and for audio focus. */
    private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)
        ?: error("The application context has no AudioManager service")

    /**
     * Attributes shared by the track and the focus request: the metronome is media.
     *
     * [AudioAttributes.USAGE_MEDIA] with [AudioAttributes.CONTENT_TYPE_MUSIC] makes the click obey
     * the media volume and be routed like music —to the headphones, to the car, to the speaker the
     * user is listening to— instead of being treated like a notification: a metronome that ignored
     * the media volume, or that played out of the phone speaker while the user is on headphones,
     * would be unusable exactly when it is needed.
     */
    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * Single focus listener for the lifetime of the engine.
     *
     * It signals whichever session is current instead of being created per session, because the
     * platform compares listeners by identity when focus is abandoned, and one instance means a
     * listener can never outlive the request it was registered with. It only publishes the stop
     * reason and interrupts the render thread: no join (the main thread must never wait for the
     * audio thread) and no pulse (the callback contract keeps `onPulse` on the render thread).
     *
     * A gain change is deliberately ignored: this engine only ever asks for
     * [AudioManager.AUDIOFOCUS_GAIN], never for a transient one, so there is no ducking to undo and
     * nothing to restart. Every other change is ignored for the same reason, which leaves the
     * listener with exactly one behaviour: a loss stops the session and reports it.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                session?.stopUnexpectedly(MetronomeStopReason.FOCUS_LOST)
            }

            else -> Unit
        }
    }

    /**
     * Focus request, built once for API 26 and above, or `null` on API 24 and 25 where the
     * deprecated stream-based call is used instead.
     *
     * It is built in the constructor rather than at the first [start] so that a badly formed
     * request (which would be a programming error, not a device failure) surfaces at construction
     * and not as a metronome that silently refuses to play.
     */
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) buildFocusRequest() else null

    /**
     * Guards the lifetime of [session]: the check-and-create of [start] and the detach that ends a
     * session.
     *
     * It is held only for those two operations and never while waiting for the render thread:
     * holding it across the join of [stop] would block a [start] for up to
     * [STOP_JOIN_TIMEOUT_MS] and give the audio thread a reason to wait for the UI thread, which is
     * exactly what the threading model of this class avoids.
     */
    private val sessionLock = Any()

    /** The session whose thread currently owns the track, or `null` while nothing is playing. */
    @Volatile
    private var session: RenderSession? = null

    /**
     * Whether a session is running, read from the flag the session itself maintains.
     *
     * Deriving it from [Thread.isAlive] would be wrong twice: a thread is alive from `start()`
     * until its body returns, which includes the whole teardown of the track, and it is alive
     * before the first block has been rendered. The flag is published by the session and, like
     * [AudioTrack], only ever cleared before the output is released, so a `false` here means the
     * session has given up the output.
     */
    override val isPlaying: Boolean
        get() = session?.running == true

    /**
     * Starts a session, or does nothing if one is already running.
     *
     * Audio focus is acquired here, on the calling thread, before the session exists: a denied
     * request is reported straight away through [onStopped] as [MetronomeStopReason.FAILED], with
     * no session created and nothing to release, which is the one failure of this class that is
     * reported synchronously (building the track happens on the render thread, so that failure is
     * always reported asynchronously).
     *
     * The guard is [RenderSession.running], not "a session exists": a session that has attached but
     * is no longer running is treated as absent, because that is exactly the state of the session
     * being torn down —[RenderSession.running] is already `false` for the whole
     * `releaseOutput` → `abandonAudioFocus` → `detach` window— and refusing a start there would
     * leave [isPlaying] `false` while the transport believes it is playing, i.e. a silence nobody
     * reports. Two starts that both pass the guard cannot overlap: the check and the assignment of
     * [session] are in the same [sessionLock] critical section, and the identity check in [detach]
     * is what keeps the old session's teardown from clearing the new one.
     *
     * The session is created inside [sessionLock] so that two taps racing, or a tap racing with a
     * lifecycle restore, cannot create two sessions writing to two tracks at once; the arguments of
     * the losing call are dropped, as the interface specifies for a start while playing.
     *
     * Creating the session and starting its thread happens before any lock is released, and it can
     * fail: the allocation and `Thread.start()` both raise [OutOfMemoryError] on a device that is
     * out of memory or out of threads. That failure must not escape —[start] is a Play button, and
     * the class promises a metronome that says it cannot play rather than a crash— and it must not
     * leak the focus either, since the `finally` that abandons it belongs to a thread that never
     * ran: other apps would stay paused for the lifetime of the engine. So the request itself and
     * the create-and-start after it are both inside the `try` —an `AudioManager` that throws from
     * the request would otherwise escape past this method, which is the one way "`start()` never
     * throws" could still be broken— and the catch: abandons the focus this call acquired, and only
     * if the request returned granted, so a refusal cannot turn into a double abandon (itself
     * wrapped, so that a second failure there cannot escape either); clears [session] only if it is
     * still the session this call created, so a failure while an older session is winding down
     * cannot drop its reference and let the next start request focus before that one abandons it;
     * and reports [MetronomeStopReason.FAILED] from here, which is the same report a denied focus
     * gets and the only one the receiver sees. The report is wrapped as well, for the same reason
     * it is wrapped in [RenderSession.finish]: a receiver that throws must not turn a failed start
     * into a crash from the Play button.
     *
     * @param bpm Initial tempo in beats per minute, clamped into
     *  [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM].
     * @param beatsPerBar Initial number of beats in a bar, clamped into
     *  [MetronomeState.MIN_BEATS_PER_BAR]..[MetronomeState.MAX_BEATS_PER_BAR].
     * @param accentFirstBeat Whether the first beat of the bar is accented.
     * @param onPulse Invoked on the render thread for every beat, when the loudspeaker reaches it.
     * @param onStopped Invoked once, on the render thread, if the session ends by itself.
     */
    override fun start(
        bpm: Int,
        beatsPerBar: Int,
        accentFirstBeat: Boolean,
        onPulse: (BeatPulse) -> Unit,
        onStopped: (MetronomeStopReason) -> Unit,
    ) {
        val failed = synchronized(sessionLock) {
            if (session?.running == true) return
            var focusAcquired = false
            var created: RenderSession? = null

            try {
                if (!requestAudioFocus()) return@synchronized true
                focusAcquired = true
                created = RenderSession(
                    bpm = MetronomeState.coerceBpm(bpm),
                    beatsPerBar = MetronomeState.coerceBeatsPerBar(beatsPerBar),
                    accentFirstBeat = accentFirstBeat,
                    onPulse = onPulse,
                    onStopped = onStopped,
                )
                session = created
                created.start()
                false
            } catch (e: Throwable) {
                if (session === created) session = null
                if (focusAcquired) runCatching { abandonAudioFocus() }
                true
            }
        }
        if (failed) {
            runCatching { onStopped(MetronomeStopReason.FAILED) }
        }
    }

    /**
     * Publishes the tempo the render thread applies at the top of its next block.
     *
     * A no-op while stopped: [start] is handed the whole configuration, so the engine keeps no
     * state between sessions and cannot drift out of step with the ViewModel that owns the value.
     *
     * @param bpm Tempo in beats per minute, clamped into
     *  [MetronomeState.MIN_BPM]..[MetronomeState.MAX_BPM].
     */
    override fun setTempo(bpm: Int) {
        session?.pendingBpm = MetronomeState.coerceBpm(bpm)
    }

    /**
     * Publishes the metre the render thread applies at the top of its next block.
     *
     * A no-op while stopped, for the same reason as [setTempo].
     *
     * @param beatsPerBar Number of beats in a bar, clamped into
     *  [MetronomeState.MIN_BEATS_PER_BAR]..[MetronomeState.MAX_BEATS_PER_BAR].
     */
    override fun setBeatsPerBar(beatsPerBar: Int) {
        session?.pendingBeatsPerBar = MetronomeState.coerceBeatsPerBar(beatsPerBar)
    }

    /**
     * Publishes the accent the render thread applies at the top of its next block, i.e. from the
     * next beat it schedules.
     *
     * A no-op while stopped, for the same reason as [setTempo].
     *
     * @param accentFirstBeat Whether the first beat of the bar is accented.
     */
    override fun setAccentFirstBeat(accentFirstBeat: Boolean) {
        session?.pendingAccentFirstBeat = accentFirstBeat
    }

    /**
     * Asks the running session to stop and waits for it to release the output.
     *
     * Idempotent and safe from any thread: with no session it returns at once, and asking twice
     * only sets a flag that is already clear. Clearing [RenderSession.running] first, and the stop
     * reason after it, is what makes an asked-for stop invisible to `onStopped` —a focus loss
     * delivered in between sees an already-stopped session, so it cannot turn the pause into a
     * report— and a session that had already failed at the same moment loses its report, which is
     * the right trade —the user has already done what the report would have asked them to do.
     *
     * The join is bounded by [STOP_JOIN_TIMEOUT_MS], which is enough because the only blocking call
     * of the render thread is a `write` of one block. After it, the session is detached even if its
     * thread has not finished, so a device that wedges the audio thread in `write` leaves the
     * engine able to start a new session instead of permanently claiming to play.
     */
    override fun stop() {
        val current = session ?: return
        current.requestStop()
        current.join()
        detach(current)
    }

    /**
     * Forgets [target], if it is still the current session.
     *
     * The single place the session field is cleared, which makes a session's lifetime exactly "from
     * the assignment in [start] until it is detached here". It is called by the render thread at
     * the very end of its `finally` —after the track is released, the focus abandoned and the
     * running flag cleared, so a receiver of `onStopped` finds a stopped and detached engine and
     * may call [start] or [stop] from inside the callback— and by [stop] after the join, so that a
     * render thread a device has wedged in `write` cannot wedge the engine with it.
     *
     * The identity check is what keeps a late detach from clearing the session that replaced it.
     *
     * @param target The session that ended.
     */
    private fun detach(target: RenderSession) {
        synchronized(sessionLock) {
            if (session === target) {
                session = null
            }
        }
    }

    /**
     * Requests [AudioManager.AUDIOFOCUS_GAIN], by whichever API the device has.
     *
     * @return Whether the focus was granted, i.e. whether the session may play.
     */
    private fun requestAudioFocus(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestFocusModern()
        } else {
            requestFocusLegacy()
        }

    /**
     * Requests focus with an [AudioFocusRequest], on API 26 and above.
     *
     * The listener is registered with the main looper explicitly: without a handler the change is
     * dispatched on the thread that requested the focus, which may not have a looper.
     *
     * @return Whether the focus was granted.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestFocusModern(): Boolean {
        val request = focusRequest ?: return false
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Requests focus with the stream-based call, on API 24 and 25.
     *
     * `STREAM_MUSIC` is the stream the track plays on, since [mediaAttributes] says `USAGE_MEDIA`;
     * both are deprecated on API 26, where [requestFocusModern] takes over.
     *
     * @return Whether the focus was granted.
     */
    @Suppress("DEPRECATION")
    private fun requestFocusLegacy(): Boolean =
        audioManager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    /** Abandons the focus acquired by [requestAudioFocus], by whichever API the device has. */
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            abandonFocusModern()
        } else {
            abandonFocusLegacy()
        }
    }

    /** Abandons the focus request, on API 26 and above. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun abandonFocusModern() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /**
     * Abandons the focus with the stream-based call, on API 24 and 25.
     *
     * The matching overload matters: a listener that requested with the deprecated call is only
     * released by the deprecated abandon.
     */
    @Suppress("DEPRECATION")
    private fun abandonFocusLegacy() {
        audioManager.abandonAudioFocus(focusListener)
    }

    /**
     * Builds the single [AudioFocusRequest] of this engine.
     *
     * @return The request, granting [AudioManager.AUDIOFOCUS_GAIN] to media playback.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttributes)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()

    /**
     * Creates the track, retrying once without the low-latency request if it is refused.
     *
     * The retry is a fallback, not an optimisation. [AudioTrack.PERFORMANCE_MODE_LOW_LATENCY] asks
     * the device for its fast mixer, and that request is refusable and, on some devices,
     * exclusive: the platform may decline it because another client holds the fast path, because
     * the OEM build does not support the mode, or because the buffer it asks for cannot be served
     * at that performance mode. A refusal does not come back as a flag —it surfaces as a runtime
     * exception thrown from `build()`— and a metronome with a bigger buffer is strictly better than
     * a metronome that does not play at all, so every refusal is answered by building the same
     * track once more without the low-latency request. The failure is caught as [RuntimeException]
     * because that is the family every refusal belongs to: catching only the two exceptions one
     * device was seen to throw would let any other refusal skip the retry, and the session would
     * end as [MetronomeStopReason.FAILED] with an "audio unavailable" message —exactly the symptom
     * the retry exists to prevent. The cost of the wider catch is one extra track construction on
     * the failure path only; the fast path is unchanged.
     *
     * @param format Format of the PCM to play.
     * @param bufferSizeBytes Size of the track's buffer in bytes.
     * @return The created track, which is not checked for being initialised here.
     */
    private fun buildTrack(format: AudioFormat, bufferSizeBytes: Int): AudioTrack =
        try {
            newTrackBuilder(format, bufferSizeBytes, lowLatency = true).build()
        } catch (e: RuntimeException) {
            newTrackBuilder(format, bufferSizeBytes, lowLatency = false).build()
        }

    /**
     * Builds the [AudioTrack.Builder] of [buildTrack].
     *
     * [AudioTrack.PERFORMANCE_MODE_LOW_LATENCY] only exists from API 26 and the library supports
     * API 24, so the branch is guarded by the SDK level and, on 24 and 25, simply not taken: the
     * default mode is what those devices have.
     *
     * @param format Format of the PCM to play.
     * @param bufferSizeBytes Size of the track's buffer in bytes.
     * @param lowLatency Whether to ask for the low-latency path.
     * @return The builder, ready to be built.
     */
    private fun newTrackBuilder(
        format: AudioFormat,
        bufferSizeBytes: Int,
        lowLatency: Boolean,
    ): AudioTrack.Builder {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(mediaAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSizeBytes)
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder
    }

    /**
     * One run of the metronome: the state, the DSP and the [AudioTrack] of a single
     * `start`..`stop` period.
     *
     * A new instance is created for every [start], so nothing survives a session that should not:
     * the beat count restarts from 0, the scheduler starts on the downbeat and a failed session
     * cannot leave a stale flag behind. The object is reached from both sides, and the fields are
     * split accordingly — see the two groups below.
     *
     * @param bpm Initial tempo in beats per minute, already clamped.
     * @param beatsPerBar Initial number of beats in a bar, already clamped.
     * @param accentFirstBeat Whether the first beat of the bar is accented.
     * @param onPulse Receiver of the beats, invoked on the render thread.
     * @param onStopped Receiver of an unexpected stop, invoked on the render thread.
     */
    private inner class RenderSession(
        bpm: Int,
        beatsPerBar: Int,
        accentFirstBeat: Boolean,
        private val onPulse: (BeatPulse) -> Unit,
        private val onStopped: (MetronomeStopReason) -> Unit,
    ) {

        /**
         * Whether the session is running: the loop condition of the render thread, the flag
         * [isPlaying] reports, and the request a stop makes.
         *
         * Written by [requestStop] and [stopUnexpectedly] on any thread, and by the render thread
         * itself when it fails or finishes. It is cleared before the track is released, so an
         * observer that sees `false` is never looking at a session that still holds the output.
         */
        @Volatile
        var running = true

        /**
         * Why the session is ending, or `null` while it is being stopped on purpose.
         *
         * Set by the render thread when it fails and by the focus listener when focus is lost;
         * cleared by [requestStop]. The render thread reports it in [finish], which is the only way
         * `onStopped` is invoked, so a session reports at most once and always from its own thread.
         */
        @Volatile
        private var stopReason: MetronomeStopReason? = null

        /**
         * Tempo requested by [setTempo], moved into the scheduler at the top of the next block.
         *
         * The values are handed over, not applied, because [BeatScheduler], [ClickMixer] and the
         * render loop share mutable state with no lock: applying them at a block boundary on the
         * thread that owns that state is what keeps the whole render path single-threaded and
         * lock-free, at the cost of up to one block (5.8 ms) of delay, which is not perceivable.
         */
        @Volatile
        var pendingBpm: Int = bpm

        /** Metre requested by [setBeatsPerBar], moved into the scheduler at the top of a block. */
        @Volatile
        var pendingBeatsPerBar: Int = beatsPerBar

        /** Whether the accent set by [setAccentFirstBeat] is pending, for the next block. */
        @Volatile
        var pendingAccentFirstBeat: Boolean = accentFirstBeat

        /**
         * The thread that renders the click: the owner of the track for this session.
         *
         * Daemon, so a session that is somehow never stopped cannot keep the process alive, and
         * named, so an audio thread is identifiable in a trace or a bug report. Its priority is
         * raised on the thread itself in [runSession], since only that thread can set it.
         */
        private val thread = Thread({ runSession() }, THREAD_NAME).apply { isDaemon = true }

        /**
         * Whether the first beat of the bar is accented as far as the render thread is concerned.
         *
         * Owned by the render thread (copied from [pendingAccentFirstBeat] at the top of a block),
         * because it is read from [queueBeat] for every beat and a `@Volatile` read per beat per
         * block would be a needless cross-thread access on the audio path.
         */
        private var accentedFirstBeat = accentFirstBeat

        /**
         * Absolute frame of the first frame of the block being rendered, i.e. the value of
         * [BeatScheduler.framePosition] before it is advanced.
         *
         * It is a field rather than a loop variable because [onBeat] is a field: a beat reported by
         * the scheduler is only described by its offset *within* the block it was found in, and the
         * publication rule below needs the file position it starts at. It is also the frame the
         * track was written from, which is what makes the play head comparable to it.
         */
        private var blockStartFrame = 0L

        /**
         * The `onBeat` callback of [BeatScheduler.advance], created once for the session.
         *
         * A bound reference to [queueBeat], not a lambda over the loop's variables: a lambda that
         * captures anything per block allocates a fresh closure for every one of the ~172 blocks a
         * second, i.e. it hands the audio thread its own stream of garbage. As a field it is one
         * object for the whole session and it captures nothing but `this`.
         */
        private val onBeat: (offsetInBlock: Int, beatInBar: Int) -> Unit = ::queueBeat

        /**
         * The block of PCM handed to the track: mono, 16 bit, [BLOCK_FRAMES] frames.
         *
         * Preallocated once and reused for every block. [ClickMixer.render] clears and fills it, so
         * nothing has to be reset between blocks.
         */
        private val pcm = ShortArray(BLOCK_FRAMES)

        /**
         * Beats that have been rendered but whose frame the loudspeaker has not reached yet.
         *
         * The render thread runs ahead of the output, so a beat is queued when it is scheduled and
         * published a buffer later. The queue is a ring over two parallel arrays —all preallocated,
         * nothing allocated per beat— because the pending beats have to keep their order across
         * blocks. Capacity [MAX_PENDING_BEATS] is a wide margin: at most one beat can be in flight
         * at the minimum beat interval of 200 ms against a buffer of 46 ms, and eight slots cover a
         * track up to 1.6 s ahead of the speaker at the fastest tempo, i.e. far beyond any hardware
         * buffer.
         */
        private val pendingFrames = LongArray(MAX_PENDING_BEATS)

        /** Position in the bar of the beat in the matching [pendingFrames] slot. */
        private val pendingBars = IntArray(MAX_PENDING_BEATS)

        /** Index of the oldest pending beat. */
        private var pendingHead = 0

        /** Number of beats waiting to be published, in `0..MAX_PENDING_BEATS`. */
        private var pendingCount = 0

        /** Number of beats published since the session started, i.e. the next [BeatPulse.count]. */
        private var pulseCount = 0L

        /**
         * Frames of play head counted by previous wraps of the unsigned 32 bit counter.
         *
         * [AudioTrack.playbackHeadPosition] is an unsigned 32 bit frame count that wraps every
         * ~27 hours at 44.1 kHz; the base is what turns it back into a monotonic frame index that
         * can be compared with [pendingFrames].
         */
        private var playHeadBase = 0L

        /** Last raw play head seen, to detect a wrap of [AudioTrack.playbackHeadPosition]. */
        private var lastRawPlayHead = 0L

        /** The output of the session. Owned by the render thread from here on. */
        private var track: AudioTrack? = null

        /** Beat clock, built for the rate the track really runs at. Created before the loop. */
        private lateinit var scheduler: BeatScheduler

        /** Click mixer, built for the rate the track really runs at. Created before the loop. */
        private lateinit var mixer: ClickMixer

        /** Starts the render thread. Called once, from [MetronomePlayer.start]. */
        fun start() {
            thread.start()
        }

        /**
         * Asks the render thread to stop, on purpose.
         *
         * Called from [MetronomePlayer.stop] on any thread. The flag is cleared **before** the
         * reason, and that order is the whole point: [stopUnexpectedly] decides on `if (running)`,
         * so a focus callback delivered between the two writes finds a session that has already
         * stopped and leaves it alone. Clearing the reason first —the obvious order— let that
         * callback see `running == true` and write [MetronomeStopReason.FOCUS_LOST] for a stop the
         * user asked for, which the contract says is never a reason.
         *
         * The price is the mirror case, and it is the cheaper one: a genuine focus loss that lands
         * in the same two-write window is cleared along with the pause and not reported. The reason
         * field only ever feeds the report of a session that has already stopped, and a user who
         * has just asked for silence does not need to be told that something else also wanted the
         * output —while a pause reported as `FOCUS_LOST` would show an error for something the user
         * did themselves.
         *
         * The interrupt is a best-effort extra: the loop only checks [running], and the blocking
         * `write` it may be inside does not honour an interrupt, but a future blocking step that
         * does would otherwise delay the stop by its own timeout.
         */
        fun requestStop() {
            running = false
            stopReason = null
            thread.interrupt()
        }

        /**
         * Asks the render thread to stop because of [reason], on any thread.
         *
         * Used by the focus listener. It never joins the render thread and never publishes from
         * here: the render thread's `finally` is what reports, so `onStopped` stays on the engine's
         * own thread. A session that has already been asked to stop on purpose is left alone, so a
         * loss that arrives after a pause does not turn a pause into a report.
         *
         * @param reason Why the session is ending.
         */
        fun stopUnexpectedly(reason: MetronomeStopReason) {
            if (running) {
                stopReason = reason
                running = false
            }
            thread.interrupt()
        }

        /**
         * Waits for the render thread to release the track, for at most [STOP_JOIN_TIMEOUT_MS].
         *
         * A join from the render thread itself is skipped: the render thread cannot wait for its
         * own `finally` to run, so a receiver of `onStopped` that stops the engine from inside the
         * callback is served by the flag alone.
         */
        fun join() {
            if (Thread.currentThread() === thread) return
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /**
         * The body of the render thread: acquire, render, release, and never throw.
         *
         * Everything runs inside the `try`, so a failure anywhere —the thread priority, the track,
         * a write that returns an error, even a receiver that throws— ends in [stopWithReason] and
         * the `finally`, and no exception ever escapes: an uncaught exception here would kill the
         * whole process from a background thread, and a metronome is not worth a crash.
         *
         * The `finally` is guarded too: abandoning the focus is the one call there that belongs to
         * the platform, and an `AudioManager` that throws from it on an OEM build would skip
         * [finish], which is the call that reports the session. The user would then see a
         * transport stuck on "playing" over silence, and the ViewModel's no-op-while-running guard
         * would refuse to restart it until the screen was recreated. With the abandon wrapped,
         * [releaseOutput] → abandon → [finish] always completes, whatever the platform does.
         *
         * The catch is [Throwable] and not [Exception] because a receiver's `onPulse` can raise an
         * [Error]—`StackOverflowError` from a receiver that recurses, `OutOfMemoryError` from one
         * that allocates. With the narrower catch that error skipped [stopWithReason] altogether:
         * the `finally` ran with [stopReason] still `null`, nothing was reported, and the error
         * escaped the thread, leaving the transport showing "playing" over silence with no message
         * to explain it — the one outcome the report exists to prevent.
         *
         * An [Error] is reported and then **not** rethrown. There is nothing left to unwind by the
         * time [stopWithReason] returns —the `finally` has released the output, abandoned the focus
         * and detached the session— so rethrowing would only convert a beat into a process crash,
         * and a crash from a background thread is precisely what this class promises never to
         * cause. The thread terminates either way; what differs is whether the user sees "audio
         * unavailable" or a crash dialog.
         */
        private fun runSession() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            try {
                prepareOutput()
                renderLoop()
            } catch (e: Throwable) {
                stopWithReason(MetronomeStopReason.FAILED)
            } finally {
                releaseOutput()
                runCatching { abandonAudioFocus() }
                finish()
            }
        }

        /**
         * Opens the output: the track, the click voices and the beat clock for the rate it runs at.
         *
         * ### Why the sample rate is read back
         *
         * [AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE] is only a *hint*: it describes the rate of the
         * fast mixer of the device, and the track can still end up at another one (48 kHz is
         * common). Everything that turns seconds into samples is built from the rate the track
         * reports afterwards, never from the hint: if the click were rendered for 44.1 kHz and the
         * track ran at 48 kHz, every click would be 8.8 % short —1764 frames played at 48000 Hz are
         * 36.75 ms instead of 40— and detuned the same 8.8 %, and the beat interval computed from
         * 44100 would no longer match the wall clock, so the metronome would run 8.8 % fast.
         *
         * ### Buffer size
         *
         * The track is given at least [MIN_BUFFER_FRAMES] = 2048 frames (46 ms) even when the
         * device would accept less, because a device that accepts 512 frames also accepts a
         * collection on the render thread of 5 ms and clicks on the resulting gap.
         * [AudioTrack.getMinBufferSize] returns **bytes**, and mono 16 bit is two bytes per frame.
         *
         * ### Starting the track
         *
         * The track is deliberately left **paused** here: [renderLoop] writes the first block and
         * only then calls `play`, so the device never starts on an empty buffer. Starting it here
         * would be one step too early and the first click would be the one to suffer.
         *
         * @throws IllegalStateException If the output cannot accept the format or the track cannot
         *  be initialised; the caller turns that into a [MetronomeStopReason.FAILED] report.
         */
        private fun prepareOutput() {
            val sampleRateHint = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_SAMPLE_RATE_HZ

            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRateHint,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                throw IllegalStateException(
                    "The output does not accept mono 16 bit at $sampleRateHint Hz: " +
                        "getMinBufferSize returned $minBufferBytes",
                )
            }

            val bufferFrames = max(minBufferBytes / BYTES_PER_FRAME, MIN_BUFFER_FRAMES)
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(sampleRateHint)
                .build()

            val newTrack = buildTrack(format, bufferFrames * BYTES_PER_FRAME)
            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { newTrack.release() }
                throw IllegalStateException("The AudioTrack is not initialised: ${newTrack.state}")
            }
            track = newTrack

            val sampleRate = newTrack.sampleRate
            val clicks = ClickSynthesizer(sampleRate)
            scheduler = BeatScheduler(sampleRate, pendingBpm, pendingBeatsPerBar)
            mixer = ClickMixer(clicks.accent, clicks.regular)
        }

        /**
         * Renders blocks until the session ends.
         *
         * The order inside one pass is load-bearing:
         *
         * 1. the pending controls, so a tempo or metre change applies from this block and the beat
         *    in progress keeps its phase ([BeatScheduler] does that retiming);
         * 2. the block start frame, because the beats reported by the next step are described
         *    relative to the block and the publication rule needs their absolute position;
         * 3. the scheduler, which reports the beats inside the block and queues their click at the
         *    exact frame they fall on;
         * 4. the mix, which turns those queues into the block of PCM;
         * 5. the write, which hands the block to the device;
         * 6. on the first pass only, `play`, which starts the track *after* that first block is
         *    already in its buffer;
         * 7. the play head, which publishes the beats the speaker has now reached —after the write,
         *    never before, so a pulse cannot run ahead of its click.
         *
         * Step 6 exists because a track started on an empty buffer underruns immediately: `play`
         * marks the stream as running and the device drains whatever the buffer holds, which is
         * nothing, so the first 256 frames are then written into a stream that has already asked
         * for audio and been given none. On the fast-mixer path that start underrun is where a
         * burst can be dropped or re-timed, and the burst it would damage is the *first* one —the
         * click a player judges the tempo by. Priming costs nothing: the buffer holds at least
         * [MIN_BUFFER_FRAMES] = 2048 frames, so one block of 256 goes in without blocking. It also
         * leaves the first beat published by the ordinary rule rather than by a special case: the
         * primed block holds frame 0, `playbackHeadPosition` is 0 the first time it is read, and
         * step 7 publishes the beat queued at frame 0 on that same pass.
         */
        private fun renderLoop() {
            val track = this.track
            if (track == null) {
                stopWithReason(MetronomeStopReason.FAILED)
                return
            }
            var primed = false
            while (running) {
                renderBlock(track)
                if (!primed) {
                    track.play()
                    primed = true
                }
                publishReachedBeats(reachedFrame(track))
            }
        }

        /**
         * Renders one block into [pcm] and writes it to [track].
         *
         * Steps 1 to 5 of the loop, kept in one place so the priming above cannot accidentally
         * reorder them. Allocates nothing: every buffer it touches is a field of the session.
         *
         * @param track The track of this session.
         */
        private fun renderBlock(track: AudioTrack) {
            applyPendingControls()
            blockStartFrame = scheduler.framePosition
            scheduler.advance(BLOCK_FRAMES, onBeat)
            mixer.render(pcm, BLOCK_FRAMES)
            writeBlock(track)
        }

        /**
         * Moves the values published by the setters into the render state.
         *
         * Called at the top of every block, never from another thread: this is the whole hand-over
         * mechanism between the UI and the audio side, and it only works because it is one-sided.
         * The accent has no phase, so it is simply copied; the tempo and the metre are assigned to
         * the scheduler, whose setters already ignore a value that changes nothing and retime the
         * beat in progress themselves.
         */
        private fun applyPendingControls() {
            scheduler.bpm = pendingBpm
            scheduler.beatsPerBar = pendingBeatsPerBar
            accentedFirstBeat = pendingAccentFirstBeat
        }

        /**
         * Queues the click and the pulse of the beat reported by the scheduler.
         *
         * The `onBeat` callback of [BeatScheduler.advance]: it runs on the render thread, inside
         * the block being rendered, and it must not allocate beyond the pulse it queues.
         *
         * The overflow branch is a guard, not a code path: eight slots against the shortest beat
         * interval the supported tempo range allows
         * ([MetronomeState.MIN_BEAT_INTERVAL_MS] = 200 ms) means the ring only fills when the track
         * is buffering **1.6 s** of audio, which no hardware buffer comes close to —a beat is two
         * blocks old by the time the speaker reaches the next one. It stays because losing a beat
         * or overwriting the oldest slot would reorder the ring, and a pulse that arrives out of
         * order is worse than one that arrives early. So the oldest beat is published to make room,
         * which is the only degradation available here: a beat published one buffer before its
         * click is heard, i.e. a sync error of a few milliseconds on a device whose buffer is
         * already metres outside the design, and never a lost beat, a reordered ring or a crash.
         *
         * @param offsetInBlock Frame of the beat within the block being rendered.
         * @param beatInBar Position of the beat in its bar, `0` being the downbeat.
         */
        private fun queueBeat(offsetInBlock: Int, beatInBar: Int) {
            mixer.scheduleClick(
                atFrameInBlock = offsetInBlock,
                accent = accentedFirstBeat && beatInBar == FIRST_BEAT_OF_BAR,
            )
            if (pendingCount == MAX_PENDING_BEATS) {
                publishOldest()
            }
            val slot = (pendingHead + pendingCount) % MAX_PENDING_BEATS
            pendingFrames[slot] = blockStartFrame + offsetInBlock
            pendingBars[slot] = beatInBar
            pendingCount++
        }

        /**
         * Publishes the beats the loudspeaker has reached, in order.
         *
         * The play head is read after every write, so a beat is published one buffer after it was
         * rendered rather than when it was rendered: see the class documentation about the
         * audio/visual sync rule. Beats are queued in ascending frame order and the play head only
         * ever goes back on a device that reports a smaller one (see [reachedFrame]), where it
         * stays behind the queue until the counter catches up again — so draining the queue from
         * the head until the first beat that is still ahead yields exactly the beats that have not
         * been heard yet, in order, and never one twice.
         *
         * @param playHead Frames played by the track so far, unwrapped.
         */
        private fun publishReachedBeats(playHead: Long) {
            while (pendingCount > 0 && pendingFrames[pendingHead] <= playHead) {
                publishOldest()
            }
        }

        /**
         * Publishes the beat at the head of the queue, freeing its slot and counting it.
         *
         * A [BeatPulse] is the only allocation of the render loop and it is unavoidable: it is the
         * message the interface hands to the receiver, and the receiver is entitled to keep it.
         */
        private fun publishOldest() {
            val slot = pendingHead
            pendingHead = (pendingHead + 1) % MAX_PENDING_BEATS
            pendingCount--
            onPulse(BeatPulse(pulseCount++, pendingBars[slot]))
        }

        /**
         * Reads the play head of the track as a monotonic frame count.
         *
         * [AudioTrack.playbackHeadPosition] is an **unsigned** 32 bit count of the frames played
         * since the track was created: it is a signed `Int` in the API, so it is masked into a
         * `Long`, and it wraps to 0 after 2^32 frames, i.e. ~27 hours at 44.1 kHz. A session that
         * long is unlikely but the wrap is cheap to handle, and an unbalanced comparison would
         * silently stop publishing beats for the rest of the session if the caller left the screen
         * open.
         *
         * A decrease is the only way either event can show up, and there are two of them:
         *
         * * a **wrap**, where the counter goes from just under 2^32 back to a small number, i.e.
         *   backwards by nearly 2^32 — the base is advanced by one wrap and the count continues;
         * * a **glitch**, where a device reports a smaller head once (an HAL or scope reset, a
         *   momentarily 0). It is not a wrap, and treating it as one would add ~46 000 hours of
         *   base in one step, after which every queued beat would compare as already played: the
         *   dot would lead its click by a whole buffer, forever, silently, because nothing about
         *   the session looks wrong afterwards.
         *
         * So only a backwards jump of more than [WRAP_BACKWARDS_FRAMES] counts as a wrap. A smaller
         * one is taken at face value —no base is added and the head is adopted as it stands— which
         * costs at most a few milliseconds of publishing delay while the counter catches up with
         * the queued beats, and cannot leave the pulse permanently ahead of the click.
         *
         * @param track The track of this session.
         * @return Frames played since the track was created, monotonic.
         */
        private fun reachedFrame(track: AudioTrack): Long {
            val raw = track.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK
            if (lastRawPlayHead - raw > WRAP_BACKWARDS_FRAMES) {
                playHeadBase += FRAMES_PER_WRAP
            }
            lastRawPlayHead = raw
            return playHeadBase + raw
        }

        /**
         * Writes the whole block to the track, looping over partial writes.
         *
         * `write` blocks until the device has room, which is the only place the render thread waits
         * and therefore the only reason a stop has to wait: a blocking call returns within one
         * buffer (46 ms), well inside [STOP_JOIN_TIMEOUT_MS].
         *
         * A negative return is an error code from the platform. Zero is treated as an error too:
         * nothing was written and the loop would spin forever, which on the audio thread is worse
         * than a session that stops and says it failed.
         *
         * @param track The track of this session.
         * @throws IllegalStateException If the platform rejects the block or writes nothing.
         */
        private fun writeBlock(track: AudioTrack) {
            var written = 0
            while (written < BLOCK_FRAMES) {
                val count = track.write(pcm, written, BLOCK_FRAMES - written)
                if (count <= 0) {
                    throw IllegalStateException("The AudioTrack rejected a block: write=$count")
                }
                written += count
            }
        }

        /**
         * Records that the session is ending for [reason], unless it is already ending.
         *
         * The guard is what keeps a failure that happens while the user is pausing from being
         * reported: [running] is already `false` then, and the user would get a failure message for
         * a stop they asked for.
         *
         * @param reason Why the session is ending.
         */
        private fun stopWithReason(reason: MetronomeStopReason) {
            if (!running) return
            stopReason = reason
            running = false
        }

        /**
         * Releases the track, which only this thread may touch.
         *
         * `stop` is called before `release` because it is the call that ends the stream, and both
         * are wrapped because a track that never initialised throws from either. What the pair
         * does to the tail is worth being exact about, because the obvious reading is backwards:
         * in [AudioTrack.MODE_STREAM] `stop` lets the frames already written **play out**, it is
         * `release` that discards them, so a click that is half-written when the session ends is
         * cut by the release at whatever sample it had reached. The DAC therefore stops mid-
         * waveform, with a step of up to 0.9 of full scale for the accent click.
         *
         * That artefact is accepted rather than faded out. It is a tick on a tick, it only happens
         * when a pause lands inside a 40 ms click —about 8 % of pauses, in an interval that is at
         * least [MetronomeState.MIN_BEAT_INTERVAL_MS] = 200 ms— and the fade that would remove it
         * has to be mixed on the render thread across the very blocks a stop is trying to cut
         * short. A user pressing pause has asked for the click to stop, and one truncated click is
         * a better answer than a session that lingers for a few more blocks to taper out.
         */
        private fun releaseOutput() {
            val track = this.track ?: return
            this.track = null
            runCatching { track.stop() }
            runCatching { track.release() }
        }

        /**
         * Ends the session: clears the flag, detaches it and reports it if it ended by itself.
         *
         * The order matters and is part of the contract of `onStopped`: the engine is already
         * stopped and already forgotten by [MetronomePlayer] when the receiver runs, so the
         * receiver may read [isPlaying] (false) and may call [MetronomePlayer.start] or
         * [MetronomePlayer.stop] from inside the callback. Detaching first is also what stops a
         * receiver that stops the engine from joining the very thread it is running on.
         *
         * The report is wrapped because this is the `finally` of the render thread: an exception
         * thrown by the receiver here would escape the thread and kill the process, and there is
         * nothing left for it to fail —the session is over and the output is already released— so
         * the exception is swallowed. A receiver that throws from `onPulse` instead ends the
         * session with [MetronomeStopReason.FAILED], which is reported through this path: a
         * receiver that cannot be called is a reason to stop calling it, not to crash.
         */
        private fun finish() {
            running = false
            detach(this)
            val reason = stopReason ?: return
            runCatching { onStopped(reason) }
        }
    }

    private companion object {

        /**
         * Frames rendered per block, i.e. per `write` to the track.
         *
         * 256 frames is 5.8 ms at 44.1 kHz and ~172 blocks per second: small enough that a beat is
         * placed within 5.8 ms of where the scheduler wants it (well below the ~10 ms at which a
         * metronome sounds off), and equal to the burst size the fast mixer of most devices
         * consumes, so a block is handed over in one go. Larger blocks would loosen the beat
         * placement and delay the pulse; much smaller ones would multiply the number of syscalls
         * for nothing.
         */
        const val BLOCK_FRAMES = 256

        /**
         * Fewest frames the track's buffer is given, i.e. 2048 frames = **46 ms** = eight blocks.
         *
         * The device often accepts a smaller buffer, and it is used as a floor because a buffer
         * that only holds one or two blocks leaves no room for the render thread to be late: a
         * collection or a scheduler hiccup of a few milliseconds would underrun the track and be
         * heard as a hole in the click. Eight blocks of slack absorbs that, and 46 ms of buffering
         * is still far below what a musician notices when the click is what they play to.
         */
        const val MIN_BUFFER_FRAMES = 2048

        /**
         * Bytes per frame of the output: mono, 16 bit, i.e. one `Short` per frame.
         *
         * [AudioTrack.getMinBufferSize] answers in bytes while everything else here is in frames,
         * so this is the one conversion between the two units.
         */
        const val BYTES_PER_FRAME = 2

        /**
         * Beats that can be waiting to be published at once.
         *
         * The render thread is ahead of the loudspeaker by at most one track buffer, and a beat is
         * queued only when it is scheduled: at the shortest beat interval of the supported tempo
         * range ([MetronomeState.MIN_BEAT_INTERVAL_MS] = 200 ms = 8820 frames = 34 blocks) and a
         * 46 ms buffer, one beat can be in flight —two, if a beat is queued and the block that
         * holds it has not been written yet— and eight slots only fill at a buffer of **1.6 s**,
         * i.e. far beyond any hardware buffer. The overflow branch in `queueBeat` is therefore
         * unreachable in the supported configuration; it exists so that a device that rounds the
         * buffer up to seconds degrades into a beat published one buffer early instead of a
         * reordered ring. It is a ring of preallocated slots because the pending beats must keep
         * their order across blocks and cannot be allocated.
         */
        const val MAX_PENDING_BEATS = 8

        /**
         * Longest time [MetronomePlayer.stop] waits for the render thread, in milliseconds.
         *
         * The same value and the same reason as `Tuner.STOP_JOIN_TIMEOUT_MS`: the render thread can
         * only be blocked inside a blocking `write`, and that returns within one buffer (46 ms at
         * 44.1 kHz). 250 ms is thus several times the worst honest case, while still being short
         * enough that a stop called from a UI event cannot be noticed as a freeze.
         */
        const val STOP_JOIN_TIMEOUT_MS = 250L

        /** Rate the click is rendered at when the device does not report one, in Hz. */
        const val DEFAULT_SAMPLE_RATE_HZ = 44100

        /** Position in the bar of the downbeat, i.e. the beat the accent applies to. */
        const val FIRST_BEAT_OF_BAR = 0

        /** Mask of the low 32 bits, which is the width of [AudioTrack.playbackHeadPosition]. */
        const val UNSIGNED_INT_MASK = 0xFFFFFFFFL

        /** Frames in one wrap of the unsigned 32 bit play head, i.e. 2^32. */
        const val FRAMES_PER_WRAP = 0x1_0000_0000L

        /**
         * Smallest backwards jump of the play head accepted as a wrap, in frames, i.e. 2^31.
         *
         * A real wrap goes backwards by nearly [FRAMES_PER_WRAP]; a glitched reading that drops to
         * a small value can go backwards by any amount. Half the counter apart is the natural cut:
         * no device report of a wrap is even close to the boundary, and no plausible glitch is a
         * meaningful fraction of 2^31 frames (13.5 hours at 44.1 kHz).
         */
        const val WRAP_BACKWARDS_FRAMES = 0x7FFF_FFFFL

        /** Name of the render thread, so it is identifiable in a trace. */
        const val THREAD_NAME = "metronome-render"
    }
}
