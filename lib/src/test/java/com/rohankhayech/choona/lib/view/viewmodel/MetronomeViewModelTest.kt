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

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rohankhayech.choona.lib.controller.metronome.FakeMetronomeEngine
import com.rohankhayech.choona.lib.controller.metronome.MetronomeEngine
import com.rohankhayech.choona.lib.controller.metronome.MetronomeStopReason
import com.rohankhayech.choona.lib.model.metronome.BeatPulse
import com.rohankhayech.choona.lib.model.metronome.MetronomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test harness for the Metronome view model.
 *
 * Every interaction is a plain call whose effect is visible synchronously in the flows, so the
 * tests need no dispatcher and no coroutine machinery: the view model publishes from the thread
 * it is called on, and the fake engine delivers its callbacks on that same thread.
 *
 * @author Rohan Khayech
 */
class MetronomeViewModelTest {

    private var now = 0L

    private var engineCreations = 0

    private lateinit var engine: FakeMetronomeEngine

    private lateinit var vm: MetronomeViewModel

    @Before
    fun setUp() {
        now = 0L
        engineCreations = 0
        engine = FakeMetronomeEngine()
        vm = newViewModel()
    }

    /** Builds a view model over the fake engine of the current test. */
    private fun newViewModel(): MetronomeViewModel =
        MetronomeViewModel(engineFactory = { engineCreations++; engine }, clock = { now })

    @Test
    fun testInitialState() {
        assertEquals(MetronomeState(), vm.state.value)
        assertEquals(120, vm.state.value.bpm)
        assertEquals(4, vm.state.value.beatsPerBar)
        assertTrue(vm.state.value.accentFirstBeat)
        assertFalse(vm.state.value.playing)

        assertNull(vm.pulses.value)
        assertNull(vm.error.value)

        assertEquals("The engine must not be built until it is needed", 0, engineCreations)
    }

    @Test
    fun testEngineIsCreatedOnFirstStartOnly() {
        assertEquals(0, engineCreations)

        vm.start()

        assertEquals(1, engineCreations)

        vm.stop()
        vm.start()

        assertEquals("A later session must reuse the engine", 1, engineCreations)
    }

    /**
     * Verifies that a second session really produces beats: the engine is handed the pulse callback
     * again on every start, so a ViewModel that only armed it on the first start would leave a
     * restarted metronome silent while the transport claimed to be playing.
     */
    @Test
    fun testRestartedSessionPublishesBeats() {
        vm.start()
        engine.emitBeat(0, 0)

        vm.stop()
        assertNull(vm.pulses.value)

        vm.start()
        engine.emitBeat(0, 1)

        assertEquals(BeatPulse(0, 1), vm.pulses.value)
        assertEquals(2, engine.startCount)
    }

    @Test
    fun testStartPassesTheCurrentConfiguration() {
        vm.setBpm(90)
        vm.setBeatsPerBar(3)
        vm.setAccentFirstBeat(false)

        vm.start()

        assertEquals(90, engine.startBpm!!)
        assertEquals(3, engine.startBeatsPerBar!!)
        assertEquals(false, engine.startAccentFirstBeat!!)
        assertEquals(1, engine.startCount)
    }

    @Test
    fun testStartSetsPlayingAndStopClearsIt() {
        vm.start()

        assertTrue(vm.state.value.playing)
        assertTrue(engine.isPlaying)

        vm.stop()

        assertFalse(vm.state.value.playing)
        assertFalse(engine.isPlaying)
        assertNull(vm.pulses.value)
        assertEquals(1, engine.stopCount)
    }

    @Test
    fun testStopBeforeStartIsANoOp() {
        vm.stop()

        assertFalse(vm.state.value.playing)
        assertEquals("The engine must not be built by a stop", 0, engineCreations)
        assertEquals(0, engine.stopCount)
        assertNull(vm.error.value)
    }

    /**
     * Verifies that the audio output is released when the ViewModel is cleared, i.e. when the
     * metronome destination is popped or the activity finishes.
     *
     * `onCleared` is protected and must not be called from a test, so the real mechanism is driven
     * instead: a [ViewModelStore] cleared through a [ViewModelProvider]. That `stop()` is the whole
     * lifetime of the engine — without it the output would stay claimed after the screen is gone —
     * and no other test in this class would notice its removal.
     */
    @Test
    fun testClearingTheStoreStopsTheEngine() {
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, viewModelFactory { initializer { newViewModel() } })
        val cleared = provider[MetronomeViewModel::class.java]

        cleared.start()
        assertTrue(engine.isPlaying)

        store.clear()

        assertEquals("Clearing the ViewModel must release the audio output", 1, engine.stopCount)
        assertFalse(engine.isPlaying)
        assertFalse(cleared.state.value.playing)
    }

    @Test
    fun testStartTwiceStartsTheEngineOnce() {
        vm.start()
        vm.start()

        assertTrue(vm.state.value.playing)
        assertEquals("A second tap on play must not reach the engine", 1, engine.startCount)
    }

    @Test
    fun testToggleStartsAndStops() {
        vm.toggle()

        assertTrue(vm.state.value.playing)
        assertTrue(engine.isPlaying)

        vm.toggle()

        assertFalse(vm.state.value.playing)
        assertFalse(engine.isPlaying)

        assertEquals(1, engine.startCount)
        assertEquals(1, engine.stopCount)
    }

    @Test
    fun testBpmIsClamped() {
        vm.setBpm(29)
        assertEquals(MetronomeState.MIN_BPM, vm.state.value.bpm)

        vm.setBpm(301)
        assertEquals(MetronomeState.MAX_BPM, vm.state.value.bpm)

        vm.setBpm(MetronomeState.MIN_BPM)
        vm.adjustBpm(-1)
        assertEquals(MetronomeState.MIN_BPM, vm.state.value.bpm)

        vm.setBpm(MetronomeState.MAX_BPM)
        vm.adjustBpm(1)
        assertEquals(MetronomeState.MAX_BPM, vm.state.value.bpm)
    }

    @Test
    fun testAdjustBpmMovesWithinTheRange() {
        vm.adjustBpm(10)
        assertEquals(130, vm.state.value.bpm)

        vm.adjustBpm(-40)
        assertEquals(90, vm.state.value.bpm)
    }

    /**
     * Verifies that a huge change saturates into the supported range instead of wrapping around the
     * [Int] range first: `120 + Int.MAX_VALUE` wraps to a large negative number, which the clamp
     * would then turn into the *slowest* tempo. The UI only ever asks for ±1, so this pins the
     * promise that a tempo is always clamped from the value that was asked for.
     */
    @Test
    fun testAdjustBpmSaturatesInsteadOfOverflowing() {
        assertEquals(120, vm.state.value.bpm)

        vm.adjustBpm(Int.MAX_VALUE)
        assertEquals(MetronomeState.MAX_BPM, vm.state.value.bpm)

        vm.adjustBpm(Int.MIN_VALUE)
        assertEquals(MetronomeState.MIN_BPM, vm.state.value.bpm)

        vm.setBpm(200)
        vm.adjustBpm(Int.MAX_VALUE)
        assertEquals(MetronomeState.MAX_BPM, vm.state.value.bpm)

        vm.setBpm(200)
        vm.adjustBpm(Int.MIN_VALUE)
        assertEquals(MetronomeState.MIN_BPM, vm.state.value.bpm)
    }

    @Test
    fun testBeatsPerBarIsClamped() {
        vm.setBeatsPerBar(0)
        assertEquals(MetronomeState.MIN_BEATS_PER_BAR, vm.state.value.beatsPerBar)

        vm.setBeatsPerBar(13)
        assertEquals(MetronomeState.MAX_BEATS_PER_BAR, vm.state.value.beatsPerBar)
    }

    @Test
    fun testSettingTheSameValueDoesNotEmitAgain() {
        vm.start()

        vm.setBpm(100)
        val afterFirst = vm.state.value

        vm.setBpm(100)
        val afterSecond = vm.state.value

        assertEquals(afterFirst, afterSecond)
        assertEquals(100, afterSecond.bpm)
        assertEquals("A repeated value must not reach the engine", listOf(100), engine.tempoChanges)
    }

    /**
     * Verifies that setting the metre it already has is ignored: a control that re-emits its value
     * on every recomposition must not reach the engine, which would restart the bar under the beat
     * that is playing.
     */
    @Test
    fun testUnchangedBeatsPerBarDoesNotReachTheEngine() {
        vm.start()

        vm.setBeatsPerBar(4)
        vm.setBeatsPerBar(3)
        vm.setBeatsPerBar(3)

        assertEquals(listOf(3), engine.beatsPerBarChanges)
        assertEquals(3, vm.state.value.beatsPerBar)
    }

    /**
     * Verifies that setting the accent it already has is ignored, for the same reason as the metre:
     * a repeated value must not reach the engine.
     */
    @Test
    fun testUnchangedAccentDoesNotReachTheEngine() {
        vm.start()

        vm.setAccentFirstBeat(true)
        vm.setAccentFirstBeat(false)
        vm.setAccentFirstBeat(false)

        assertEquals(listOf(false), engine.accentChanges)
        assertFalse(vm.state.value.accentFirstBeat)
    }

    @Test
    fun testTempoChangesAreForwardedOnlyWhilePlaying() {
        vm.setBpm(90)

        assertTrue(engine.tempoChanges.isEmpty())
        assertEquals(90, vm.state.value.bpm)

        vm.start()
        vm.setBpm(140)

        assertEquals(listOf(140), engine.tempoChanges)

        vm.stop()
        vm.setBpm(160)

        assertEquals("A stopped engine is told the configuration on start", listOf(140), engine.tempoChanges)
        assertEquals(160, vm.state.value.bpm)
    }

    @Test
    fun testBeatsPerBarAndAccentAreForwardedOnlyWhilePlaying() {
        vm.setBeatsPerBar(3)
        vm.setAccentFirstBeat(false)

        assertTrue(engine.beatsPerBarChanges.isEmpty())
        assertTrue(engine.accentChanges.isEmpty())

        vm.start()
        vm.setBeatsPerBar(5)
        vm.setAccentFirstBeat(true)

        assertEquals(listOf(5), engine.beatsPerBarChanges)
        assertEquals(listOf(true), engine.accentChanges)

        vm.stop()
        vm.setBeatsPerBar(7)
        vm.setAccentFirstBeat(false)

        assertEquals(listOf(5), engine.beatsPerBarChanges)
        assertEquals(listOf(true), engine.accentChanges)
        assertEquals(7, vm.state.value.beatsPerBar)
        assertFalse(vm.state.value.accentFirstBeat)
    }

    @Test
    fun testPulsesAreForwardedInOrder() {
        vm.start()

        val observed = mutableListOf<BeatPulse>()
        engine.emitBeat(0, 0)
        observed.add(vm.pulses.value!!)
        engine.emitBeat(1, 1)
        observed.add(vm.pulses.value!!)
        engine.emitBeat(2, 2)
        observed.add(vm.pulses.value!!)

        assertEquals(listOf(BeatPulse(0, 0), BeatPulse(1, 1), BeatPulse(2, 2)), observed)
        assertEquals(BeatPulse(2, 2), vm.pulses.value)
    }

    @Test
    fun testFocusLossStopsWithoutAnError() {
        vm.start()
        engine.emitBeat(0, 0)

        engine.stopUnexpectedly(MetronomeStopReason.FOCUS_LOST)

        assertFalse(vm.state.value.playing)
        assertNull("Losing focus is not a failure", vm.error.value)
        assertNull(vm.pulses.value)
        assertEquals(120, vm.state.value.bpm)
    }

    @Test
    fun testInterruptionStopsWithoutAnError() {
        vm.start()
        engine.emitBeat(0, 0)

        engine.stopUnexpectedly(MetronomeStopReason.INTERRUPTED)

        assertFalse(vm.state.value.playing)
        assertNull("An interruption is not a failure", vm.error.value)
        assertNull(vm.pulses.value)
    }

    @Test
    fun testFailureIsSurfacedAsAnError() {
        vm.start()
        engine.emitBeat(0, 0)

        engine.stopUnexpectedly(MetronomeStopReason.FAILED)

        assertFalse(vm.state.value.playing)
        assertNull(vm.pulses.value)

        val error = vm.error.value
        assertNotNull(error)
        assertEquals(MetronomeViewModel.FAILURE_MESSAGE, error!!.message)
    }

    @Test
    fun testFailedStartLeavesTheUiStopped() {
        engine.failOnStart = true

        vm.start()

        assertEquals(1, engine.startCount)
        assertFalse("A failed start must not leave the UI playing", vm.state.value.playing)
        assertNotNull(vm.error.value)
    }

    /**
     * Verifies that a factory that cannot build the engine is a failed start rather than a crash
     * out of the Play button: the screen goes back to stopped with an error that still reaches the
     * cause, nothing half-built is kept, and the next press builds the engine and plays.
     *
     * The production factory resolves the `AudioManager` and builds an `AudioFocusRequest` in the
     * engine's constructor — that is the one step of a start the engine's "never throws" contract
     * does not cover, so a device missing the audio service would otherwise take the screen down.
     */
    @Test
    fun testFailingFactoryIsReportedAndCanBeRetried() {
        val cause = IllegalStateException("no audio service")
        var fail = false
        val vm = MetronomeViewModel(
            engineFactory = {
                if (fail) throw cause
                engine
            },
            clock = { now },
        )
        fail = true

        vm.start()

        assertFalse("A failed start must not leave the UI playing", vm.state.value.playing)
        assertEquals(MetronomeViewModel.FAILURE_MESSAGE, vm.error.value?.message)
        assertTrue("The cause must stay reachable", vm.error.value?.cause === cause)
        assertEquals("Nothing may reach an engine that was never built", 0, engine.startCount)
        assertFalse(engine.isPlaying)

        fail = false
        vm.start()

        assertNull("The retry must clear the error", vm.error.value)
        assertTrue(vm.state.value.playing)
        assertEquals(1, engine.startCount)
        assertTrue(engine.isPlaying)
    }

    /**
     * Verifies that a start clears the error of the session before it, so a stale "audio output is
     * unavailable" cannot stay on screen while a later session is playing and then stop for a
     * reason that is not a failure.
     */
    @Test
    fun testStartClearsTheErrorOfThePreviousSession() {
        engine.failOnStart = true
        vm.start()
        assertNotNull(vm.error.value)

        engine.failOnStart = false
        vm.start()
        engine.stopUnexpectedly(MetronomeStopReason.FOCUS_LOST)

        assertFalse(vm.state.value.playing)
        assertNull("A stop that is not a failure must not report the previous error", vm.error.value)
    }

    /**
     * Verifies that the pulse of a session that is over cannot survive into the next one.
     *
     * The engine publishes a beat from its own render thread, so a beat can land *after* `stop()`
     * cleared the pulse: the stray beat then sits in [MetronomeViewModel.pulses] while the
     * metronome is stopped and would be highlighted by the beat indicator of the next session if a
     * start did not clear it. [StrayBeatEngine] reproduces exactly that delivery, which the fake
     * cannot, because it drops the callback when its session ends.
     */
    @Test
    fun testStartClearsTheStrayBeatOfThePreviousSession() {
        val stray = StrayBeatEngine(engine)
        val vm = MetronomeViewModel(engineFactory = { stray }, clock = { now })

        vm.start()
        vm.stop()
        stray.publishStrayBeat(7)
        assertNotNull("The stray beat must land, or this test proves nothing", vm.pulses.value)

        vm.start()

        assertNull(vm.pulses.value)
    }

    @Test
    fun testErrorIsClearedByANewStart() {
        engine.failOnStart = true
        vm.start()
        assertNotNull(vm.error.value)

        engine.failOnStart = false
        vm.start()

        assertNull(vm.error.value)
        assertTrue(vm.state.value.playing)
        assertTrue(engine.isPlaying)
        assertEquals(2, engine.startCount)
    }

    @Test
    fun testStopClearsThePulse() {
        vm.start()
        engine.emitBeat(0, 0)
        assertNotNull(vm.pulses.value)

        vm.stop()

        assertNull(vm.pulses.value)
    }

    @Test
    fun testTapSetsTheTempo() {
        vm.setBpm(100)

        vm.tap()
        now += 500
        vm.tap()

        assertEquals(120, vm.state.value.bpm)
    }

    @Test
    fun testTapClampsTheTempoOfVeryFastTaps() {
        vm.tap()
        now += 100
        vm.tap()

        assertEquals("A tapped 600 BPM must be clamped", MetronomeState.MAX_BPM, vm.state.value.bpm)
    }

    @Test
    fun testTapDoesNotStartPlayback() {
        vm.tap()
        now += 500
        vm.tap()

        assertFalse(vm.state.value.playing)
        assertEquals(0, engine.startCount)
        assertEquals(0, engineCreations)
    }

    @Test
    fun testTapUpdatesTheLiveTempoWithoutRestartingTheSession() {
        vm.setBpm(100)
        vm.start()
        engine.emitBeat(0, 0)

        vm.tap()
        now += 500
        vm.tap()

        assertTrue("Tapping must not stop the beat", vm.state.value.playing)
        assertEquals(120, vm.state.value.bpm)
        assertEquals("The live engine must be retuned", listOf(120), engine.tempoChanges)
        assertEquals("Tapping must not restart the session", 1, engine.startCount)

        engine.emitBeat(1, 1)
        assertEquals("The beat must keep running while the user taps", BeatPulse(1, 1), vm.pulses.value)
    }

    @Test
    fun testResetTapsClearsTheSequence() {
        vm.tap()
        now += 500
        vm.tap()
        assertEquals(120, vm.state.value.bpm)

        vm.resetTaps()

        now += 500
        vm.tap()
        assertEquals("A single tap after a reset must not detect a tempo", 120, vm.state.value.bpm)

        now += 250
        vm.tap()
        assertEquals("The new sequence must start from scratch", 240, vm.state.value.bpm)
    }

    /**
     * A [MetronomeEngine] that forwards everything to [delegate] but remembers the pulse callback of
     * the last start, so a test can publish a beat *after* the session ended.
     *
     * That is the one thing [FakeMetronomeEngine] cannot reproduce: it releases its callbacks when
     * the session ends, while the production engine renders ahead of the transport and can still
     * deliver a beat that was already in its queue. Used by the test that pins the stray beat, so
     * the hazard is reproduced without loosening the fake.
     */
    private class StrayBeatEngine(private val delegate: MetronomeEngine) : MetronomeEngine {

        private var pulseCallback: ((BeatPulse) -> Unit)? = null

        override val isPlaying: Boolean
            get() = delegate.isPlaying

        override fun start(
            bpm: Int,
            beatsPerBar: Int,
            accentFirstBeat: Boolean,
            onPulse: (BeatPulse) -> Unit,
            onStopped: (MetronomeStopReason) -> Unit,
        ) {
            pulseCallback = onPulse
            delegate.start(bpm, beatsPerBar, accentFirstBeat, onPulse, onStopped)
        }

        override fun setTempo(bpm: Int) = delegate.setTempo(bpm)

        override fun setBeatsPerBar(beatsPerBar: Int) = delegate.setBeatsPerBar(beatsPerBar)

        override fun setAccentFirstBeat(accentFirstBeat: Boolean) = delegate.setAccentFirstBeat(accentFirstBeat)

        override fun stop() = delegate.stop()

        /** Publishes a beat through the callback of the last start, whatever the session state. */
        fun publishStrayBeat(count: Long) {
            pulseCallback?.invoke(BeatPulse(count, 0))
        }
    }
}
