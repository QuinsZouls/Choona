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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.rohankhayech.choona.lib.model.tempo.TapTempoState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * UI screen used to determine the tempo of a song by tapping along to the beat.
 *
 * @param state The current tap-tempo state.
 * @param onTap Called when the tap pad is tapped.
 * @param onReset Called when the tap sequence is to be reset.
 * @param onDoubleTempo Called when the displayed tempo is to be doubled.
 * @param onHalveTempo Called when the displayed tempo is to be halved.
 * @param onDismiss Called when the screen is dismissed.
 * @param modifier Modifier to be applied to the screen.
 *
 * @author Rohan Khayech
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapTempoScreen(
    state: TapTempoState,
    onTap: () -> Unit,
    onReset: () -> Unit,
    onDoubleTempo: () -> Unit,
    onHalveTempo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            StatusBarColor(if (MaterialTheme.isLight) StatusBarIconColor.DARK else StatusBarIconColor.LIGHT)
            TopAppBar(
                title = {
                    Text(stringResource(R.string.tap_tempo), style = MaterialTheme.typography.titleMedium)
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
            val padSize = minOf(MAX_PAD_SIZE, maxWidth * 0.62f, maxHeight - CONTROLS_RESERVED_HEIGHT)
                .coerceAtLeast(MIN_PAD_SIZE)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                TapPad(state = state, padSize = padSize, onTap = onTap)
                Spacer(Modifier.height(16.dp))
                if (state.tapCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.tap_tempo_taps,
                            state.tapCount,
                            state.tapCount
                        ),
                        style = if (padSize < SMALL_PAD_SIZE) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.steady) {
                    Text(
                        text = stringResource(R.string.tap_tempo_steady),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                TempoControls(
                    state = state,
                    onReset = onReset,
                    onDoubleTempo = onDoubleTempo,
                    onHalveTempo = onHalveTempo
                )
            }
        }
    }
}

/**
 * Circular pad used to register taps, with a pulse animation on each tap.
 *
 * The detected tempo, together with the applied tempo multiplier when it is not the default
 * one, is exposed as the accessibility state description of the pad, so screen readers read
 * the result of the tap sequence. The visual content of the pad is hidden from screen readers
 * with [clearAndSetSemantics] so that the same result is not announced twice, once from the
 * state description and once from the merged text of the pad.
 *
 * @param state The current tap-tempo state.
 * @param padSize Diameter of the pad and of its surrounding pulse ring.
 * @param onTap Called when the pad is tapped.
 */
@Composable
private fun TapPad(
    state: TapTempoState,
    padSize: Dp,
    onTap: () -> Unit,
) {
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val tapLabel = stringResource(R.string.tap_tempo)
    val bpmLabel = stringResource(R.string.tap_tempo_bpm)
    val tapStartLabel = stringResource(R.string.tap_tempo_start)
    val displayBpm = state.displayBpm
    val multiplierText = multiplierLabel(state.multiplier)

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(padSize)
                .graphicsLayer {
                    scaleX = 1f + pulse.value * 0.06f
                    scaleY = 1f + pulse.value * 0.06f
                    alpha = pulse.value
                }
                .border(width = 4.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
        )
        Box(
            modifier = Modifier
                .size(padSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(role = Role.Button, onClickLabel = tapLabel) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTap()
                    scope.launch {
                        pulse.snapTo(1f)
                        pulse.animateTo(0f, tween(durationMillis = 250))
                    }
                }
                .semantics {
                    stateDescription = listOfNotNull(
                        displayBpm?.let { bpm -> "${bpm.roundToInt()} $bpmLabel" },
                        multiplierText
                    ).joinToString(" ").ifEmpty { tapStartLabel }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (displayBpm == null) {
                    Text(
                        text = tapStartLabel,
                        style = if (padSize < SMALL_PAD_SIZE) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                } else {
                    Text(
                        text = displayBpm.roundToInt().toString(),
                        style = if (padSize < MEDIUM_PAD_SIZE) {
                            MaterialTheme.typography.displayMedium
                        } else {
                            MaterialTheme.typography.displayLarge
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = bpmLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (multiplierText != null) {
                        Text(
                            text = multiplierText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Describes a non-default tempo multiplier as the glyph shown on the pad.
 *
 * @param multiplier Factor currently applied to the tapped tempo.
 * @return The multiplier glyph, e.g. `×2` or `÷4`, or null when it is the default one.
 */
private fun multiplierLabel(multiplier: Double): String? = when {
    multiplier > 1.0 -> "×${multiplier.toInt()}"
    multiplier < 1.0 -> "÷${(1.0 / multiplier).roundToInt()}"
    else -> null
}

/**
 * Reset and tempo adjustment controls of the tap tempo screen.
 *
 * @param state The current tap-tempo state.
 * @param onReset Called when the tap sequence is to be reset.
 * @param onDoubleTempo Called when the displayed tempo is to be doubled.
 * @param onHalveTempo Called when the displayed tempo is to be halved.
 */
@Composable
private fun TempoControls(
    state: TapTempoState,
    onReset: () -> Unit,
    onDoubleTempo: () -> Unit,
    onHalveTempo: () -> Unit,
) {
    val halveLabel = stringResource(R.string.tap_tempo_halve)
    val doubleLabel = stringResource(R.string.tap_tempo_double)

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onHalveTempo,
            enabled = state.bpm != null,
            modifier = Modifier.semantics { contentDescription = halveLabel }
        ) {
            Text("÷2")
        }
        OutlinedButton(onClick = onReset, enabled = state.tapCount > 0) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tap_tempo_reset))
        }
        FilledTonalButton(
            onClick = onDoubleTempo,
            enabled = state.bpm != null,
            modifier = Modifier.semantics { contentDescription = doubleLabel }
        ) {
            Text("×2")
        }
    }
}

/** Maximum diameter of the tap pad. */
private val MAX_PAD_SIZE = 240.dp

/** Minimum diameter the tap pad may shrink to. */
private val MIN_PAD_SIZE = 96.dp

/** Pad diameter below which the BPM is rendered at a smaller type scale. */
private val MEDIUM_PAD_SIZE = 200.dp

/** Pad diameter below which the pad texts are rendered at a smaller type scale. */
private val SMALL_PAD_SIZE = 150.dp

/** Vertical space reserved for the tap counter, the controls and their padding. */
private val CONTROLS_RESERVED_HEIGHT = 180.dp

@CompactOrientationThemePreview
@Composable
private fun Preview() {
    AppTheme {
        TapTempoScreen(
            state = TapTempoState(bpm = 120.0, intervalMs = 500.0, tapCount = 6, steady = true),
            onTap = {},
            onReset = {},
            onDoubleTempo = {},
            onHalveTempo = {},
            onDismiss = {}
        )
    }
}
