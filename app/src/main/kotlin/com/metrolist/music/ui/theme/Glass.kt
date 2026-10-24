/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
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

/**
 * Frosted glass with no outline that fades out towards one edge, so a bar blends into the
 * content instead of ending in a hard line. Put it on a layer behind the bar's content
 * (e.g. a sibling with matchParentSize) so only the glass fades, not icons or text.
 */
@Composable
fun Modifier.fadingGlass(
    fadeTowardsBottom: Boolean,
    tintAlpha: Float = 0.5f,
    blur: Dp = 28.dp,
    extend: Dp = 72.dp,
): Modifier {
    val haze = LocalGlassHaze.current
    val tint = MaterialTheme.colorScheme.surface
    val stops =
        if (fadeTowardsBottom) {
            arrayOf(
                0f to Color.Black,
                0.3f to Color.Black,
                0.65f to Color.Black.copy(alpha = 0.5f),
                1f to Color.Transparent,
            )
        } else {
            arrayOf(
                0f to Color.Transparent,
                0.35f to Color.Black.copy(alpha = 0.5f),
                0.7f to Color.Black,
                1f to Color.Black,
            )
        }
    val mask = Brush.verticalGradient(*stops)
    return this
        .layout { measurable, c ->
            // Grow past the bar into the content so the fade has room to be tall.
            val e = extend.roundToPx()
            val h = c.maxHeight + e
            val p = measurable.measure(c.copy(minHeight = h, maxHeight = h))
            layout(p.width, c.maxHeight) { p.place(0, if (fadeTowardsBottom) 0 else -e) }
        }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }.then(
            if (haze != null) {
                Modifier.hazeEffect(haze) {
                    blurRadius = blur
                    tints = listOf(HazeTint(tint.copy(alpha = tintAlpha)))
                }
            } else {
                Modifier.background(tint.copy(alpha = 0.85f))
            },
        )
}

/** Plain theme background behind every screen (follows the theme settings, incl. pure black). */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface),
    )
}
