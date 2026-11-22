package com.metrolist.music.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey

val SpeedDialGlowKey = booleanPreferencesKey("speedDialGlow")
val SpeedDialGlowSpreadKey = floatPreferencesKey("speedDialGlowSpread")
val SpeedDialGlowBlurKey = floatPreferencesKey("speedDialGlowBlur")

const val DefaultSpeedDialGlowSpread = 0f
const val DefaultSpeedDialGlowBlur = 14f
