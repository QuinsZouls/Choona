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

package com.rohankhayech.choona.app.view.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rohankhayech.android.util.ui.preview.CompactOrientationThemePreview
import com.rohankhayech.android.util.ui.theme.StatusBarColor
import com.rohankhayech.android.util.ui.theme.StatusBarIconColor
import com.rohankhayech.android.util.ui.theme.m3.isLight
import com.rohankhayech.choona.app.view.theme.AppTheme
import com.rohankhayech.choona.lib.R
import com.rohankhayech.choona.lib.model.metronome.BeatPulse
import com.rohankhayech.choona.lib.model.metronome.MetronomeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

/**
 * UI screen used to configure and run the metronome.
 *
 * It displays the beat indicator, the tempo, the metre and the first-beat accent, and hosts the
 * transport and the tap-tempo input. Everything the screen shows comes from [state], except the
 * per-beat visual, which comes from [pulses] and is collected only by the beat indicator, so a
 * beat up to 300 times a minute never recomposes the rest of the screen.
 *
 * @param state The current tempo, metre, accent and playing state of the metronome.
 * @param pulses The most recent beat of the running session, or `null` while stopped. Collected
 *  only inside the beat indicator.
 * @param error The reason the last session could not play, or `null` if it did not fail.
 * @param onBpmChange Called with the tempo selected on the slider.
 * @param onAdjustBpm Called with the change in tempo, in beats per minute, of the stepping buttons.
 * @param onBeatsPerBarChange Called with the number of beats of the selected metre.
 * @param onAccentChange Called when the first-beat accent is switched on or off.
 * @param onToggle Called when the transport control is pressed.
 * @param onTap Called when the tap-tempo button is pressed.
 * @param onDismiss Called when the screen is dismissed.
 * @param modifier Modifier to be applied to the screen.
 *
 * @author Rohan Khayech
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetronomeScreen(
    state: MetronomeState,
    pulses: StateFlow<BeatPulse?>,
    error: Exception?,
    onBpmChange: (Int) -> Unit,
    onAdjustBpm: (Int) -> Unit,
    onBeatsPerBarChange: (Int) -> Unit,
    onAccentChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            StatusBarColor(if (MaterialTheme.isLight) StatusBarIconColor.DARK else StatusBarIconColor.LIGHT)
            TopAppBar(
                title = {
                    Text(stringResource(R.string.metronome), style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.dismiss))
                    }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .fillMaxSize()
        ) {
            val compact = maxHeight < COMPACT_HEIGHT

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(
                        if (compact) COMPACT_VERTICAL_SPACING else VERTICAL_SPACING,
                        Alignment.CenterVertically
                    )
                ) {
                    BeatIndicator(
                        state = state,
                        pulses = pulses,
                        dotSize = if (state.beatsPerBar <= LARGE_METRE_BEATS) DOT_SIZE else SMALL_DOT_SIZE
                    )
                    TempoReadout(bpm = state.bpm, compact = compact)
                    TempoControls(state = state, onBpmChange = onBpmChange, onAdjustBpm = onAdjustBpm)
                    BeatsPerBarControls(state = state, onBeatsPerBarChange = onBeatsPerBarChange)
                    AccentControl(accentFirstBeat = state.accentFirstBeat, onAccentChange = onAccentChange)
                    if (error != null) {
                        Text(
                            text = error.message ?: stringResource(R.string.metronome_error_generic),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    TransportControls(state = state, onToggle = onToggle, onTap = onTap)
                }
            }
        }
    }
}

/**
 * Row of dots showing the bar, with the beat that is currently sounding highlighted.
 *
 * [pulses] is collected here and nowhere else, so the per-beat signal only reaches the one
 * composable that has to react to it: everything else on the screen is driven by the rarely
 * changing [MetronomeState]. The animation itself is only read inside [graphicsLayer], so the
 * pulse animation recomposes nothing, while a beat does recompose the indicator subtree, whose
 * highlighted dot is composition state, and never the rest of the screen.
 *
 * The dots are hidden from screen readers and the bar is described by a static state description
 * instead, deliberately without a live region: a beat fires up to five times a second, so a live
 * region would announce the beat continuously and make the screen unusable with a screen reader.
 *
 * @param state The current tempo, metre, accent and playing state of the metronome.
 * @param pulses The most recent beat of the running session, or `null` while stopped.
 * @param dotSize Diameter of each beat dot.
 */
@Composable
private fun BeatIndicator(
    state: MetronomeState,
    pulses: StateFlow<BeatPulse?>,
    dotSize: Dp,
) {
    val pulse = remember { Animatable(0f) }
    var activeBeat by remember { mutableIntStateOf(-1) }
    val description = stringResource(
        if (state.playing) R.string.metronome_state_playing else R.string.metronome_state_stopped,
        state.bpm,
        state.beatsPerBar
    )

    LaunchedEffect(pulses) {
        pulses.filterNotNull().collect { beat ->
            activeBeat = beat.beatInBar
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(BEAT_PULSE_MS))
        }
    }

    LaunchedEffect(state.playing) {
        if (!state.playing) {
            activeBeat = -1
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.semantics { stateDescription = description }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1f + pulse.value * PULSE_SCALE
                    scaleY = 1f + pulse.value * PULSE_SCALE
                    alpha = pulse.value
                }
                .border(width = RING_WIDTH, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(DOT_SPACING),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics {}
        ) {
            repeat(state.beatsPerBar) { beat ->
                BeatDot(
                    size = dotSize,
                    active = beat == activeBeat,
                    accented = state.accentFirstBeat && beat == 0 && beat != activeBeat
                )
            }
        }
    }
}

/**
 * A single dot of the beat indicator.
 *
 * @param size Diameter of the dot.
 * @param active Whether this beat is the one currently sounding, i.e. filled with the primary
 *  colour.
 * @param accented Whether the dot marks the accented first beat, i.e. is outlined when it is not
 *  the active one.
 */
@Composable
private fun BeatDot(size: Dp, active: Boolean, accented: Boolean) {
    val modifier = Modifier
        .size(size)
        .clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)

    if (accented) {
        Box(
            modifier = modifier.border(
                width = RING_WIDTH,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
        )
    } else {
        Box(modifier = modifier)
    }
}

/**
 * Tempo of the metronome as a large numeric readout.
 *
 * Reads only [bpm] and never the beat pulse, so it is not recomposed on every beat.
 *
 * @param bpm Tempo in beats per minute.
 * @param compact Whether to render the number at a smaller type scale, used on short windows.
 */
@Composable
private fun TempoReadout(bpm: Int, compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = bpm.toString(),
            style = if (compact) {
                MaterialTheme.typography.displayMedium
            } else {
                MaterialTheme.typography.displayLarge
            },
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.metronome_bpm),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Slider and stepping buttons used to set the tempo.
 *
 * @param state The current tempo, metre, accent and playing state of the metronome.
 * @param onBpmChange Called with the tempo selected on the slider.
 * @param onAdjustBpm Called with the change in tempo of the stepping buttons.
 */
@Composable
private fun TempoControls(
    state: MetronomeState,
    onBpmChange: (Int) -> Unit,
    onAdjustBpm: (Int) -> Unit,
) {
    val bpmStateDescription = stringResource(R.string.metronome_bpm_state, state.bpm)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(MAX_CONTROLS_WIDTH)
    ) {
        IconButton(
            onClick = { onAdjustBpm(-1) },
            enabled = state.bpm > MetronomeState.MIN_BPM
        ) {
            Icon(
                Icons.Default.Remove,
                stringResource(R.string.metronome_decrease_bpm)
            )
        }
        // The slider is deliberately continuous: `steps` also decides how many tick marks the
        // slider draws, and one tick per BPM would paint 271 of them into a solid band over a
        // range of 270 BPM. The tempo is quantised here and clamped by the ViewModel, so a
        // fractional thumb position never reaches the engine.
        Slider(
            value = state.bpm.toFloat(),
            onValueChange = { onBpmChange(it.roundToInt()) },
            valueRange = MetronomeState.MIN_BPM.toFloat()..MetronomeState.MAX_BPM.toFloat(),
            steps = 0,
            modifier = Modifier
                .weight(1f)
                .semantics { stateDescription = bpmStateDescription }
        )
        IconButton(
            onClick = { onAdjustBpm(1) },
            enabled = state.bpm < MetronomeState.MAX_BPM
        ) {
            Icon(
                Icons.Default.Add,
                stringResource(R.string.metronome_increase_bpm)
            )
        }
    }
}

/**
 * Labelled stepper used to set the number of beats in a bar.
 *
 * @param state The current tempo, metre, accent and playing state of the metronome.
 * @param onBeatsPerBarChange Called with the number of beats of the selected metre.
 */
@Composable
private fun BeatsPerBarControls(
    state: MetronomeState,
    onBeatsPerBarChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(MAX_CONTROLS_WIDTH)
    ) {
        Text(
            text = stringResource(R.string.metronome_beats_per_bar),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onBeatsPerBarChange(state.beatsPerBar - 1) },
            enabled = state.beatsPerBar > MetronomeState.MIN_BEATS_PER_BAR
        ) {
            Icon(
                Icons.Default.Remove,
                stringResource(R.string.metronome_decrease_beats)
            )
        }
        Text(
            text = state.beatsPerBar.toString(),
            style = MaterialTheme.typography.titleLarge
        )
        IconButton(
            onClick = { onBeatsPerBarChange(state.beatsPerBar + 1) },
            enabled = state.beatsPerBar < MetronomeState.MAX_BEATS_PER_BAR
        ) {
            Icon(
                Icons.Default.Add,
                stringResource(R.string.metronome_increase_beats)
            )
        }
    }
}

/**
 * Switch used to accent the first beat of the bar.
 *
 * @param accentFirstBeat Whether the first beat of the bar is accented.
 * @param onAccentChange Called when the switch changes.
 */
@Composable
private fun AccentControl(
    accentFirstBeat: Boolean,
    onAccentChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(MAX_CONTROLS_WIDTH)
            .toggleable(value = accentFirstBeat, role = Role.Switch, onValueChange = onAccentChange)
    ) {
        Text(
            text = stringResource(R.string.metronome_accent_first_beat),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = accentFirstBeat, onCheckedChange = null)
    }
}

/**
 * Play/stop control and tap-tempo button.
 *
 * @param state The current tempo, metre, accent and playing state of the metronome.
 * @param onToggle Called when the play/stop control is pressed.
 * @param onTap Called when the tap-tempo button is pressed.
 */
@Composable
private fun TransportControls(
    state: MetronomeState,
    onToggle: () -> Unit,
    onTap: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val transportLabel =
        stringResource(if (state.playing) R.string.metronome_stop else R.string.metronome_play)

    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(TRANSPORT_BUTTON_SIZE),
            shape = CircleShape
        ) {
            Icon(
                if (state.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = transportLabel,
                modifier = Modifier.size(TRANSPORT_ICON_SIZE)
            )
        }
        OutlinedButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTap()
            }
        ) {
            Text(stringResource(R.string.metronome_tap))
        }
    }
}

/** Height below which the screen is laid out in its compact form. */
private val COMPACT_HEIGHT = 600.dp

/** Vertical space between the sections of the screen. */
private val VERTICAL_SPACING = 24.dp

/** Vertical space between the sections of the screen in its compact form. */
private val COMPACT_VERTICAL_SPACING = 12.dp

/** Widest the control rows grow to, so they stay readable on wide windows. */
private val MAX_CONTROLS_WIDTH = 480.dp

/** Metre up to which the beat dots are drawn at their larger size. */
private const val LARGE_METRE_BEATS = 8

/** Diameter of a beat dot at the larger size. */
private val DOT_SIZE = 14.dp

/** Diameter of a beat dot at the smaller size, used for the widest metres. */
private val SMALL_DOT_SIZE = 10.dp

/** Space between beat dots. */
private val DOT_SPACING = 8.dp

/** Scale the pulse ring grows to at the start of a beat. */
private const val PULSE_SCALE = 0.15f

/** Width of the pulse ring and of the outline marking the accented first beat. */
private val RING_WIDTH = 2.dp

/**
 * Duration of the pulse animation of a beat, in milliseconds.
 *
 * Tied to [MetronomeState.MIN_BEAT_INTERVAL_MS] on purpose: the beat collector animates each
 * pulse to completion before taking the next beat, so the animation must always finish before
 * the shortest beat can arrive, or the collector falls behind and the conflating flow silently
 * drops beats. Raising [MetronomeState.MAX_BPM] moves the bound with it.
 */
private val BEAT_PULSE_MS = (MetronomeState.MIN_BEAT_INTERVAL_MS * 0.9f).toInt()

/** Diameter of the play/stop control. */
private val TRANSPORT_BUTTON_SIZE = 72.dp

/** Size of the icon inside the play/stop control. */
private val TRANSPORT_ICON_SIZE = 36.dp

@CompactOrientationThemePreview
@Composable
private fun Preview() {
    AppTheme {
        MetronomeScreen(
            state = MetronomeState(bpm = 120, beatsPerBar = 4, playing = true),
            pulses = remember { MutableStateFlow(BeatPulse(count = 2, beatInBar = 2)) },
            error = null,
            onBpmChange = {},
            onAdjustBpm = {},
            onBeatsPerBarChange = {},
            onAccentChange = {},
            onToggle = {},
            onTap = {},
            onDismiss = {}
        )
    }
}
