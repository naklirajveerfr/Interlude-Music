/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Haze source shared by the app; glass surfaces blur whatever is drawn into it. */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }

val GlassBorderColor = Color.White.copy(alpha = 0.18f)

/**
 * Frosted glass surface: blurs the content behind it, adds a tint and a thin light edge.
 * Must not be used on a node that is a descendant of the haze source itself.
 * Falls back to a plain translucent fill when no haze source is available.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RectangleShape,
    tint: Color = MaterialTheme.colorScheme.surface,
    tintAlpha: Float = 0.45f,
    blur: Dp = 28.dp,
    bordered: Boolean = true,
): Modifier {
    val haze = LocalGlassHaze.current
    val filled =
        if (haze != null) {
            this
                .clip(shape)
                .hazeEffect(haze) {
                    blurRadius = blur
                    tints = listOf(HazeTint(tint.copy(alpha = tintAlpha)))
                }
        } else {
            this
                .clip(shape)
                .background(tint.copy(alpha = (tintAlpha + 0.35f).coerceAtMost(0.95f)))
        }
    return if (bordered) filled.border(0.75.dp, GlassBorderColor, shape) else filled
}

/** Theme-coloured wash drawn behind every screen so the glass has something to blur. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surface
    val primary = scheme.primary
    val secondary = scheme.secondary
    val tertiary = scheme.tertiary
    val primaryContainer = scheme.primaryContainer
    val secondaryContainer = scheme.secondaryContainer
    val tertiaryContainer = scheme.tertiaryContainer
    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h)
                drawRect(
                    Brush.linearGradient(
                        listOf(
                            primaryContainer.copy(alpha = 0.95f),
                            secondaryContainer.copy(alpha = 0.55f),
                            tertiaryContainer.copy(alpha = 0.95f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(w * 0.1f, h * 0.1f),
                        radius = r * 0.8f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(tertiary.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(w * 0.95f, h * 0.55f),
                        radius = r * 0.75f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(secondary.copy(alpha = 0.4f), Color.Transparent),
                        center = Offset(w * 0.2f, h * 0.95f),
                        radius = r * 0.75f,
                    ),
                )
            },
    )
}
