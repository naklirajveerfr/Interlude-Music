package com.metrolist.music.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Stub CastButton for F-Droid builds.
 * Does not render anything - Cast not available without GMS.
 */
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // No-op: Cast not available in FOSS build
}


/** Stub for builds without Google Cast. */
class CastMenuHandle {
    fun open() = Unit
}

/** Stub for builds without Google Cast: renders nothing. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CastMenuIcon(
    handle: CastMenuHandle,
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
}
