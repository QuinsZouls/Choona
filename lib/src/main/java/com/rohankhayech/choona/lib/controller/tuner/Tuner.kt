/*
 * Choona - Guitar Tuner
 * Copyright (C) 2025 Rohan Khayech
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

package com.rohankhayech.choona.lib.controller.tuner

import kotlin.math.abs
import kotlin.math.roundToInt
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import be.tarsos.dsp.pitch.PitchDetectionResult
import com.rohankhayech.choona.lib.controller.tuner.Tuner.Companion.HIGHEST_NOTE
import com.rohankhayech.choona.lib.controller.tuner.Tuner.Companion.LOWEST_NOTE
import com.rohankhayech.choona.lib.controller.tunings.TuningEditor
import com.rohankhayech.choona.lib.controller.util.alsoIfTrue
import com.rohankhayech.choona.lib.model.error.TunerException
import com.rohankhayech.choona.lib.model.tuning.Notes
import com.rohankhayech.choona.lib.model.tuning.Tuning
import com.rohankhayech.choona.lib.view.PermissionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Guitar Tuner class is responsible for listening to incoming microphone audio
 * and comparing the currently playing note with the strings in the tuning.
 *
 * ### Audio pipeline
 * The microphone is captured at [TunerPipeline.SAMPLE_RATE] (44100 Hz), which keeps every harmonic
 * of the highest detectable note far from the Nyquist frequency, and the audio is analysed in
 * windows of [TunerPipeline.BUFFER_SIZE] (4096) samples that advance
 * [TunerPipeline.BUFFER_OVERLAP] (2048) samples at a time. The overlap halves the latency without
 * shortening the window, which is what lets the detector resolve the low strings: 4096 samples are
 * roughly 93 ms, enough for several periods of the low E.
 *
 * Each frame travels through three stages, which [TunerPipeline.createDispatcher] adds in this
 * order because processors run in the order they are added, on the audio thread:
 *
 * 1. A [SignalConditioner] removes the rumble and the mains hum, and publishes the level of the
 *    frame it has just received.
 * 2. A [be.tarsos.dsp.pitch.FastYin] detector estimates the frequency of the conditioned frame.
 * 3. A [PitchTracker] turns the estimates into a stable offset, discarding the frames that cannot
 *    hold a note, folding the octave errors and smoothing the result over a short window.
 *
 * [processPitch] is the entry point of the frames: the detector reports each one to it, together
 * with the level published by the conditioner.
 *
 * @param tuning The guitar tuning used for comparison.
 * @param tracker The tracker that filters the frames reported by the detector.
 * @param sourceFactory Test seam: opens the stream [start] analyses. The default is what the app
 *                      uses, which is the microphone; it exists so that the lifecycle of
 *                      [start]/[stop] can be exercised on the host JVM, where there is no
 *                      `AudioRecord` to capture from.
 * @param dispatcherFactory Test seam: builds the chain that runs over the stream [start] opened.
 *                          The default is what the app uses; it exists so that a chain that fails
 *                          to build can be exercised on the host JVM.
 *
 * @author Rohan Khayech
 */
class Tuner internal constructor(
    tuning: Tuning,
    private val tracker: PitchTracker,
    private val sourceFactory: (sampleRateHz: Int, bufferSize: Int) -> TarsosDSPAudioInputStream =
        { rate, size -> TunerAudioSource(rate, size) },
    private val dispatcherFactory: (
        TarsosDSPAudioInputStream,
        (PitchDetectionResult, Float) -> Unit,
    ) -> AudioDispatcher = { source, onFrame ->
        TunerPipeline.createDispatcher(source, onFrame = onFrame)
    },
) : TuningEditor(tuning) {

    /**
     * Creates a tuner for the given [tuning].
     *
     * The tracker is given the range of notes the tuner can represent, so that a frame that lands
     * outside of it, such as a harmonic several octaves above the plucked string, is discarded
     * instead of moving the needle, while a note one octave above the ceiling of the range is folded
     * onto the string it belongs to and shown after the frames the folding takes to confirm.
     *
     * @param tuning The guitar tuning used for comparison.
     */
    constructor(tuning: Tuning = Tuning.STANDARD) : this(
        tuning,
        PitchTracker(lowestNote = LOWEST_NOTE, highestNote = HIGHEST_NOTE),
    )

    companion object {

        /** Threshold in semitones that note offset must be below to be considered in tune. */
        const val TUNED_OFFSET_THRESHOLD = 0.15

        /** Time in ms that a note must be held below the threshold for before being considered in tune. */
        const val TUNED_SUSTAIN_TIME = 900

        /** Maximum time to wait for the audio thread to finish when stopping. */
        private const val STOP_JOIN_TIMEOUT_MS = 250L

        /** Index of the lowest detectable note. */
        val LOWEST_NOTE = Notes.getIndex("D1")

        /** Index of the highest detectable note. */
        val HIGHEST_NOTE = Notes.getIndex("B4")
    }

    /** Mutable backing property for [selectedString]. */
    private val _selectedString = MutableStateFlow(0)

    /** Index of the currently selected string within the tuning. */
    val selectedString = _selectedString.asStateFlow()

    /** Mutable backing property for [selectedNote]. */
    private val _selectedNote = MutableStateFlow(Notes.getIndex("E2"))

    /** The index of the currently selected note. */
    val selectedNote = _selectedNote.asStateFlow()

    /** Mutable backing property for [noteOffset] */
    private val _noteOffset = MutableStateFlow<Double?>(null)

    /** The offset between the currently playing note and the selected string. */
    val noteOffset = _noteOffset.asStateFlow()

    /** Mutable backing property for [autoDetect]. */
    private val _autoDetect = MutableStateFlow(true)

    /** Whether the tuner will automatically detect the currently playing string. */
    val autoDetect = _autoDetect.asStateFlow()

    /** Mutable backing property for [tuned]. */
    private val _tuned = MutableStateFlow(BooleanArray(tuning.numStrings()) { false })

    /** Whether each string has been tuned. */
    val tuned = _tuned.asStateFlow()

    /** Mutable backing property for [noteTuned]. */
    private val _noteTuned = MutableStateFlow(false)

    /** Whether the currently playing chromatic note is tuned. */
    val noteTuned = _noteTuned.asStateFlow()

    /** Mutable backing property for [chromatic]. */
    private val _chromatic = MutableStateFlow(false)

    /** Whether the tuner is currently in chromatic mode, or instrument mode. */
    val chromatic = _chromatic.asStateFlow()

    /** Whether the tuner is currently running. */
    private var running = false

    /**
     * Test seam: whether the tuner is currently listening to audio.
     *
     * The state is private, and the UI reads it through the [error] and the offsets the tuner
     * publishes, so this accessor exists for the lifecycle tests alone.
     */
    internal val isRunning: Boolean get() = running

    /** Audio dispatcher used to receive incoming audio data.  */
    private var dispatcher: AudioDispatcher? = null

    /** Thread running the [dispatcher], or `null` while the tuner is stopped. */
    private var dispatcherThread: Thread? = null

    /** Mutable backing property for [error]. */
    private val _error = MutableStateFlow<Exception?>(null)

    /** Error preventing the tuner from running. `null` if no error has occurred. */
    val error = _error.asStateFlow()

    /** Selects the [nth][n] string in the tuning for comparison. */
    fun selectString(n: Int) {
        require(n in 0 until tuning.value.numStrings()) { "Invalid string index." }
        _selectedString.update { n }
        _autoDetect.update { false }
    }

    /** Sets the guitar tuning for comparison. */
    fun setTuning(tuning: Tuning) {
        if (chromatic.value) {
            setChromatic(false)
        }
        _tuning.update {
            updateTunedStatus(it, tuning)
            tuning
        }
        if (selectedString.value >= tuning.numStrings()) {
            _selectedString.update { tuning.numStrings() - 1 }
        }
    }

    /** Tunes all strings in the tuning up by one semitone */
    override fun tuneUp(): Boolean {
        return super.tuneUp().alsoIfTrue {
            _tuned.update { BooleanArray(tuning.value.numStrings()) { false } }
        }
    }

    /** Tunes all strings in the tuning down by one semitone */
    override fun tuneDown(): Boolean {
        return super.tuneDown().alsoIfTrue {
            _tuned.update { BooleanArray(tuning.value.numStrings()) { false } }
        }
    }

    /** Tunes the [nth][n] string in the tuning up by one semitone.
     * @return False if the string could not be tuned any lower, true otherwise.
     */
    override fun tuneStringUp(n: Int): Boolean {
        return super.tuneStringUp(n).alsoIfTrue {
            setTuned(n, false)
        }
    }

    /**
     * Tunes the [nth][n] string in the tuning down by one semitone.
     * @return False if the string could not be tuned any lower, true otherwise.
     */
    override fun tuneStringDown(n: Int): Boolean {
        return super.tuneStringDown(n).alsoIfTrue {
            setTuned(n, false)
        }
    }

    /**
     * Selects the note to tune to in chromatic mode.
     * @param noteIndex The index of the note to select, must be between [LOWEST_NOTE] and [HIGHEST_NOTE].
     */
    fun selectNote(noteIndex: Int) {
        require(noteIndex in LOWEST_NOTE..HIGHEST_NOTE) { "Invalid note index." }
        if (selectedNote.value != noteIndex) {
            _noteTuned.update { false }
        }
        _selectedNote.update { noteIndex }
        _autoDetect.update { false }
    }

    /**
     * Sets the [tuned] value of the [nth][n] string.
     * If in chromatic mode, sets the [noteTuned] value instead.
     */
    fun setTuned(n: Int = selectedString.value, tuned: Boolean = true) {
        require(n in 0 until tuning.value.numStrings()) { "Invalid string index." }
        if (chromatic.value) {
            _noteTuned.update { tuned }
        } else {
            _tuned.update { old -> old.clone().also { it[n] = tuned } }
        }
    }

    /** Sets whether the tuner will automatically detect the currently playing string. */
    fun setAutoDetect(on: Boolean) {
        _autoDetect.update { on }
    }

    /** Sets whether the tuner is in chromatic mode or instrument mode. */
    fun setChromatic(on: Boolean = true) {
        // Reset tuned state.
        _tuned.update { BooleanArray(tuning.value.numStrings()) { false } }
        _noteTuned.update { false }

        // Set chromatic or instrument mode
        _chromatic.update { on }
    }

    /**
     * Checks if any strings in the [new][newTuning] tuning are the same as in the [old][oldTuning] tuning and allows them to keep their tuned status.
     */
    private fun updateTunedStatus(oldTuning: Tuning, newTuning: Tuning) {
        _tuned.update {
            if (oldTuning.numStrings() != newTuning.numStrings()) {
                BooleanArray(newTuning.numStrings()) { false }
            } else {
                it.clone().also { newArr ->
                    for (i in 0 until newTuning.numStrings()) {
                        if (newTuning.getString(i) != oldTuning.getString(i)) {
                            newArr[i] = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Processes an already detected [result], the compatibility entry point for a caller that
     * brings its own detector.
     *
     * The level of the frame is unknown here, so it is not used to discard it: use the overload
     * that receives it for the full filtering of the pipeline, which is the one
     * [TunerPipeline.createDispatcher] reports the frames to and the one that can discard a frame
     * the microphone captured below the noise floor.
     *
     * Everything else is decided by the [tracker] the tuner was built with, and applies here in the
     * same way: a tuner built with the default tracker smooths the offsets over its window, folds
     * the octave error and rejects the notes outside the detectable range.
     *
     * @param result The result reported by the pitch detector.
     */
    fun processPitch(result: PitchDetectionResult) = processPitch(result, Float.NaN)

    /**
     * Processes a single pitch detection frame and updates the offset between the currently playing
     * note and the root note of the selected string.
     *
     * The frame arrives already filtered by the confidence of the detector, which only reports a
     * pitch whose clarity stays above `1 - [TunerPipeline.YIN_THRESHOLD]`: the filtering this method
     * adds is the level of the frame, and the ones the [tracker] decides.
     *
     * @param result The result reported by the pitch detector.
     * @param inputRms Level of the frame in the same scale as the received samples, or [Float.NaN]
     *                 to leave the frame ungated by its level.
     */
    internal fun processPitch(result: PitchDetectionResult, inputRms: Float) {
        val notePlaying = if (result.isPitched) {
            tracker.process(result.pitch, inputRms, currentCandidates())
        } else {
            tracker.reset()
            null
        }

        if (notePlaying != null) {
            if (autoDetect.value) {
                if (chromatic.value) {
                    // Defence in depth: a tracker built with the range of the tuner has already
                    // discarded this frame, so the clamp only catches a tuner built without one. It
                    // keeps the selection within the bounds the rest of the UI expects.
                    val closestNote = notePlaying.roundToInt().coerceIn(LOWEST_NOTE, HIGHEST_NOTE)
                    if (selectedNote.value != closestNote) {
                        _noteTuned.update { false }
                    }
                    _selectedNote.update { closestNote }
                } else {
                    _selectedString.update {
                        tuning.value.getStringNum(
                            tuning.value.minBy { abs(it.getNoteIndex(0) - notePlaying) }
                        )
                    }
                }
            }

            // Update note offset.
            _noteOffset.update { calcNoteOffset(notePlaying) }
        } else {
            _noteOffset.update { null }
        }
    }

    /** Note indices of the strings of the tuning, or null in chromatic mode (disables octave folding). */
    private fun currentCandidates(): IntArray? =
        if (chromatic.value) null
        else IntArray(tuning.value.numStrings()) { tuning.value.getString(it).getNoteIndex(0) }

    /** Returns the offset between the specified [note][notePlaying] and the root note of the selected string. */
    private fun calcNoteOffset(notePlaying: Double): Double {
        val noteIndex = if (chromatic.value) {
            selectedNote.value
        } else {
            val str = tuning.value.getString(selectedString.value)
            str.getNoteIndex(0)
        }
        return notePlaying - noteIndex
    }

    /**
     * Starts listening to incoming audio and begins note comparison.
     * This process must be stopped by calling [stop] when finished.
     *
     * The sample rate is never changed to fit the device: if the microphone cannot keep up with the
     * requested buffer, only the buffer of the [android.media.AudioRecord] grows, which changes how
     * much audio is captured before it is read and not the window that is analysed. The chain of
     * processors is the one [TunerPipeline] builds, which is also where their order lives: the
     * [AudioDispatcher] walks them in insertion order, on the audio thread.
     *
     * @param ph The Android runtime permission handler.
     * @throws IllegalStateException If the RECORD_AUDIO permission is not granted, or the tuner has already started.
     * @throws TunerException If the tuner fails to start due to another error.
     *                        The [error] property will also be set with this exception.
     */
    @Throws(IllegalStateException::class, TunerException::class)
    fun start(ph: PermissionHandler) {
        startWithPermissionCheck(checkingPermission = ph::check)
    }

    /**
     * Starts listening to incoming audio, asking [checkingPermission] whether the tuner is allowed to
     * capture it.
     *
     * This is the body of [start], which supplies the check of the [PermissionHandler], and it is a
     * test seam, the app must always call [start] which checks the real permission. The name says so
     * on purpose: a closure of this shape is easy to reach for from anywhere in the library as
     * `startWithPermissionCheck { true }`, and that would capture audio without asking the user.
     *
     * It is the entry point of the lifecycle tests: a [PermissionHandler] cannot be built on the
     * host JVM, since it registers an activity result launcher of an Android activity, and the
     * lifecycle of [start]/[stop] is exactly what has to be covered.
     *
     * @param checkingPermission Returns whether the RECORD_AUDIO permission is currently granted.
     * @throws IllegalStateException If [checkingPermission] returns `false`, or the tuner has
     *                               already started.
     * @throws TunerException If the tuner fails to start due to another error.
     *                        The [error] property will also be set with this exception.
     */
    @Throws(IllegalStateException::class, TunerException::class)
    internal fun startWithPermissionCheck(checkingPermission: () -> Boolean) {
        check(!running) { "Tuner already started." }
        check(checkingPermission()) { "RECORD_AUDIO permission not granted." }

        running = true
        _error.update { null }
        var source: TarsosDSPAudioInputStream? = null
        try {
            val newSource = sourceFactory(TunerPipeline.SAMPLE_RATE, TunerPipeline.BUFFER_SIZE)
            source = newSource
            val newDispatcher = dispatcherFactory(newSource) { result, inputRms ->
                processPitch(result, inputRms)
            }

            this.dispatcher = newDispatcher
            this.dispatcherThread = Thread(newDispatcher, "audio-dispatcher").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            running = false
            // This is the only point where the microphone can be lost: the stream starts capturing
            // as soon as it is created, so it has to be released by whoever owns it at the moment of
            // the failure. Once the dispatcher is assigned it owns the stream, and stopping it closes
            // the stream; before that the stream is the only owner and nothing else can close it.
            // The two paths are exclusive, since closing the stream twice would release the
            // AudioRecord twice.
            if (dispatcher == null) {
                runCatching { source?.close() }
            }
            releaseDispatcher()

            val err = if (e is TunerException) e else TunerException(e.message, e)
            _error.update { err }
            throw err
        }
    }

    /** Stops listening to incoming audio and note comparison. */
    fun stop() {
        if (running) {
            running = false
            releaseDispatcher()
            tracker.reset()
        }
    }

    /**
     * Stops the dispatcher and forgets it, waiting for its thread to finish.
     *
     * Stopping the dispatcher is what closes the audio stream, which stops and releases the
     * microphone: it is the only owner of the stream while it exists. Both fields are `null` when
     * the tuner never started, which makes the call a no-op.
     *
     * The call never throws, so it is safe from the catch block that is already handling a failure.
     */
    private fun releaseDispatcher() {
        runCatching { dispatcher?.stop() }
        dispatcherThread?.let { runCatching { it.join(STOP_JOIN_TIMEOUT_MS) } }
        dispatcher = null
        dispatcherThread = null
    }
}
