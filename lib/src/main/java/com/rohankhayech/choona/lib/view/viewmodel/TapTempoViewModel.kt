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
import com.rohankhayech.choona.lib.controller.tempo.TapTempoCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for the Tap Tempo screen.
 *
 * Exposes the tempo detected by a [TapTempoCalculator] as observable state, and
 * delegates the tap sequence, its reset and the manual tempo adjustments to the
 * calculator.
 *
 * The clock is injectable so that the tap timestamps can be controlled in tests.
 * Taps must be registered from the main (UI) thread, as the calculator holds the
 * tap sequence in unsynchronised state.
 *
 * @param calculator The calculator used to derive the tempo from the registered taps.
 * @param clock Source of the current time in milliseconds, from a monotonic clock.
 *
 * @author Rohan Khayech
 */
class TapTempoViewModel(
    private val calculator: TapTempoCalculator = TapTempoCalculator(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {

    private val _state = MutableStateFlow(calculator.state)

    /** The current tap-tempo state. */
    val state = _state.asStateFlow()

    /** Registers a tap at the current time. */
    fun tap() {
        _state.value = calculator.tap(clock())
    }

    /** Clears the tap sequence and resets the tempo multiplier. */
    fun reset() {
        _state.value = calculator.reset()
    }

    /** Doubles the tempo multiplier. */
    fun doubleTempo() {
        _state.value = calculator.doubleTempo()
    }

    /** Halves the tempo multiplier. */
    fun halveTempo() {
        _state.value = calculator.halveTempo()
    }
}
