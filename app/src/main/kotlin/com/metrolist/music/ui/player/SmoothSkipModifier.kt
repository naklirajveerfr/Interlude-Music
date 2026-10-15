package com.metrolist.music.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.metrolist.music.LocalPlayerConnection
import kotlinx.coroutines.withTimeoutOrNull

private const val SMOOTH_SKIP_HOLD_MS = 1500L

/**
 * Holding a next button for 1.5 seconds skips with the smooth skip transition (when it is enabled).
 * A normal tap is left alone and skips as usual.
 */
fun Modifier.smoothSkipOnHold(): Modifier =
    composed {
        val playerConnection = LocalPlayerConnection.current
        val haptic = LocalHapticFeedback.current
        pointerInput(playerConnection) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var held = true
                withTimeoutOrNull(SMOOTH_SKIP_HOLD_MS) {
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    held = false
                }
                val connection = playerConnection
                if (held && connection != null && connection.service.isSmoothSkipEnabled() && connection.canSkipNext.value) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    connection.seekToNextSmooth()
                    // Swallow the release so the button does not also register a normal tap
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
        }
    }
