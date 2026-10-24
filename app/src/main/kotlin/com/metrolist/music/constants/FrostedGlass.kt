/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey

val FrostedGlassUiKey = booleanPreferencesKey("frostedGlassUi")
val FrostedGlassBlurLevelKey = floatPreferencesKey("frostedGlassBlurLevel")

const val DefaultFrostedGlassUi = true
const val DefaultFrostedGlassBlurLevel = 24f
const val DefaultFrostedMenuBlurLevel = 15f
const val DefaultOpaqueMenuBlurLevel = 40f
