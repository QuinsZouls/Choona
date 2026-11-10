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

package com.rohankhayech.choona.lib.model.error

/**
 * An exception that occurs when the metronome is prevented from running, i.e. when the audio
 * output is unavailable or the click track could not be created.
 *
 * It is reported to the UI the same way [TunerException] is: it reaches the error state of the
 * metronome screen, so a metronome that cannot play says why instead of failing silently.
 *
 * @property message The detail message of the exception.
 * @property cause The cause of the exception, if any.
 * @author Rohan Khayech
 */
class MetronomeException(override val message: String?, override val cause: Throwable?): Exception()
