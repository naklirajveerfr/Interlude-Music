/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score

val DefaultThemeColor = Color(0xFFED5564)

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    frostedUi: Boolean = false,
    forceColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Determine if system dynamic colors should be used (Android S+ and default theme color)
    val useSystemDynamicColor = (themeColor == DefaultThemeColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    // Select the appropriate color scheme generation method
    val baseColorScheme = if (useSystemDynamicColor) {
        // Use standard Material 3 dynamic color functions for system wallpaper colors
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // Use materialKolor only when a specific seed color is provided
        rememberDynamicColorScheme(
            seedColor = if (forceColor) themeColor.softened() else themeColor, // themeColor is guaranteed non-default here
            isDark = darkTheme,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            // Fidelity keeps the seed's own hue and saturation instead of muting it to a tonal palette
            style = if (forceColor) PaletteStyle.Fidelity else PaletteStyle.TonalSpot
        )
    }

    // Apply pureBlack modification if needed, similar to original logic
    val colorScheme = remember(baseColorScheme, pureBlack, darkTheme) {
        if (darkTheme && pureBlack) {
            baseColorScheme.pureBlack(true)
        } else {
            baseColorScheme
        }
    }

    MaterialTheme(
        colorScheme = if (frostedUi) colorScheme.frosted() else colorScheme,
        content = content,
    )
}

private const val FORCED_COLOR_MAX_CHROMA = 40.0
private const val FORCED_COLOR_MIN_TONE = 55.0

/**
 * Keeps "Force exact colour" from turning the whole app into a fully saturated cover colour:
 * the hue is kept, but very strong colours get a capped chroma and a lighter tone.
 */
internal fun Color.softened(): Color {
    val hct = Hct.fromInt(toArgb())
    if (hct.chroma <= FORCED_COLOR_MAX_CHROMA) return this
    return Color(
        Hct.from(hct.hue, FORCED_COLOR_MAX_CHROMA, hct.tone.coerceAtLeast(FORCED_COLOR_MIN_TONE)).toInt()
    )
}

/** Makes the container surface roles translucent so they read as glass over the backdrop. */
fun ColorScheme.frosted() =
    copy(
        surfaceContainerLowest = surfaceContainerLowest.copy(alpha = 0.35f),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = 0.42f),
        surfaceContainer = surfaceContainer.copy(alpha = 0.5f),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = 0.58f),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = 0.65f),
    )

fun Bitmap.extractThemeColor(): Color = Color(
    Palette.from(this)
        .maximumColorCount(8)
        .generate()
        .rankedColors(1, DefaultThemeColor.toArgb())
        .first()
)

internal fun Palette.rankedColors(
    desiredColorCount: Int,
    fallbackColor: Int,
): List<Int> = Score.score(
    swatches.associate { it.rgb to it.population },
    desiredColorCount,
    fallbackColor,
    true,
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}
