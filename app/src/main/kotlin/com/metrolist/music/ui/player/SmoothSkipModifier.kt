package com.metrolist.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.metrolist.music.LocalPlayerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val SMOOTH_SKIP_HOLD_MS = 1500L
private const val FLASH_IN_MS = 400
private const val FLASH_HOLD_MS = 1_000L
private const val FLASH_OUT_MS = 600

/**
 * Holding a next button for 1.5 seconds skips with the smooth skip transition (when it is enabled).
 * A normal tap is left alone and skips as usual.
 */
fun Modifier.smoothSkipOnHold(): Modifier =
    composed {
        val playerConnection = LocalPlayerConnection.current
        val haptic = LocalHapticFeedback.current
        val primary = MaterialTheme.colorScheme.primary

        // When the smooth skip is ready the button fades into the primary color, then back a second later.
        val flash = remember { Animatable(0f) }
        LaunchedEffect(playerConnection) {
            playerConnection?.service?.mixReadyEvents?.collect {
                flash.animateTo(1f, tween(FLASH_IN_MS))
                delay(FLASH_HOLD_MS)
                flash.animateTo(0f, tween(FLASH_OUT_MS))
            }
        }

        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (flash.value > 0f) {
                    drawRect(primary.copy(alpha = flash.value), blendMode = BlendMode.SrcAtop)
                }
            }
            .pointerInput(playerConnection) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    playerConnection?.service?.prepareBeatAlign()
                    var held = true
                    withTimeoutOrNull(SMOOTH_SKIP_HOLD_MS) {
                        waitForUpOrCancellation(PointerEventPass.Initial)
                        held = false
                    }
                    val connection = playerConnection
                    if (!held) connection?.service?.cancelBeatAlignPrep()
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
