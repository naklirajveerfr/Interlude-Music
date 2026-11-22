package com.metrolist.music.ui.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * Draws the blurred halo behind a Speed dial card. [spreadPx] grows the glow's shape beyond the card's edge,
 * [blurPx] is how far it fades out. [edgeColors] colour the halo round the card; [fallback] is used until they load.
 */
fun DrawScope.drawSpeedDialGlow(
    card: Rect,
    edgeColors: List<Color>?,
    fallback: Color,
    strength: Float,
    blurPx: Float,
    spreadPx: Float,
    cornerPx: Float,
) {
    if (card.width <= 0f || card.height <= 0f) return
    val shape = card.inflate(spreadPx)

    val paint =
        android.graphics.Paint().apply {
            isAntiAlias = true
            if (edgeColors != null) {
                shader =
                    android.graphics.SweepGradient(
                        card.center.x,
                        card.center.y,
                        IntArray(edgeColors.size) { edgeColors[it].copy(alpha = 0.8f * strength).toArgb() },
                        null,
                    )
            } else {
                color = fallback.copy(alpha = 0.7f * strength).toArgb()
            }
            if (blurPx > 0.5f) {
                maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
    val corner = minOf(cornerPx + spreadPx, shape.width / 2f)
    drawContext.canvas.nativeCanvas.drawRoundRect(shape.left, shape.top, shape.right, shape.bottom, corner, corner, paint)
}
