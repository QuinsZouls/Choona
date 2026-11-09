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

import com.rohankhayech.choona.lib.model.tempo.TapTempoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Test harness for the TapTempo view model.
 *
 * @author Rohan Khayech
 */
class TapTempoViewModelTest {

    private var now = 0L

    private lateinit var vm: TapTempoViewModel

    @Before
    fun setUp() {
        now = 0L
        vm = TapTempoViewModel(clock = { now })
    }

    @Test
    fun testInitialState() {
        val state = vm.state.value
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertEquals(0, state.tapCount)
        assertEquals(TapTempoState.DEFAULT_MULTIPLIER, state.multiplier, DELTA)
    }

    @Test
    fun testFiveTapsAtFiveHundredMillis() {
        repeat(5) {
            vm.tap()
            now += 500L
        }

        val state = vm.state.value
        assertEquals(120.0, state.bpm!!, DELTA)
        assertEquals(500.0, state.intervalMs!!, DELTA)
        assertEquals(5, state.tapCount)
    }

    @Test
    fun testReset() {
        repeat(3) {
            vm.tap()
            now += 500L
        }
        assertEquals(120.0, vm.state.value.bpm!!, DELTA)

        vm.reset()

        val state = vm.state.value
        assertEquals(0, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
        assertEquals(TapTempoState.DEFAULT_MULTIPLIER, state.multiplier, DELTA)
    }

    @Test
    fun testDoubleAndHalveTempo() {
        repeat(3) {
            vm.tap()
            now += 500L
        }
        assertEquals(120.0, vm.state.value.bpm!!, DELTA)

        vm.doubleTempo()

        val doubled = vm.state.value
        assertEquals(2.0, doubled.multiplier, DELTA)
        assertEquals(240.0, doubled.displayBpm!!, DELTA)

        vm.halveTempo()

        val halved = vm.state.value
        assertEquals(TapTempoState.DEFAULT_MULTIPLIER, halved.multiplier, DELTA)
        assertEquals(120.0, halved.displayBpm!!, DELTA)
    }

    @Test
    fun testTimeoutStartsNewSequence() {
        repeat(3) {
            vm.tap()
            now += 500L
        }
        assertEquals(3, vm.state.value.tapCount)

        now = 4000L
        vm.tap()

        val state = vm.state.value
        assertEquals(1, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
    }

    @Test
    fun testStateUpdatesAfterEachTap() {
        val observed = mutableListOf<TapTempoState>()
        repeat(3) {
            vm.tap()
            observed.add(vm.state.value)
            now += 500L
        }

        assertEquals(listOf(1, 2, 3), observed.map { it.tapCount })
        assertNull(observed[0].bpm)
        assertEquals(120.0, observed[1].bpm!!, DELTA)
        assertEquals(120.0, observed[2].bpm!!, DELTA)
        assertFalse(observed[2].steady)
    }

    @Test
    fun testIgnoredTapDoesNotChangeState() {
        vm.tap()
        val first = vm.state.value

        vm.tap()

        val state = vm.state.value
        assertEquals(first, state)
        assertEquals(1, state.tapCount)
        assertNull(state.bpm)
        assertNull(state.intervalMs)
    }

    companion object {
        private const val DELTA = 1e-6
    }
}
