/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft light along the left and right edges of the screen. It uses the theme's primary color, which
 * follows the playing song's artwork when the dynamic theme is on, and fades smoothly between songs.
 */
@Composable
fun EdgeGlow(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 80.dp,
    maxAlpha: Float = 0.4f,
) {
    val glow by animateColorAsState(color, tween(800), label = "edge_glow")

    Canvas(modifier.fillMaxSize()) {
        val glowWidth = width.toPx()
        drawRect(
            brush =
                Brush.horizontalGradient(
                    colors = listOf(glow.copy(alpha = maxAlpha), Color.Transparent),
                    startX = 0f,
                    endX = glowWidth,
                ),
            size = Size(glowWidth, size.height),
        )
        drawRect(
            brush =
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, glow.copy(alpha = maxAlpha)),
                    startX = size.width - glowWidth,
                    endX = size.width,
                ),
            topLeft = Offset(size.width - glowWidth, 0f),
            size = Size(glowWidth, size.height),
        )
    }
}
