package com.metrolist.music.constants

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

enum class MenuBackgroundStyle { BLUR, TRANSPARENT, OPAQUE }

val MenuBackgroundStyleKey = stringPreferencesKey("menuBackgroundStyle")
val MenuBlurLevelKey = floatPreferencesKey("menuBlurLevel")
val MenuTransparencyLevelKey = floatPreferencesKey("menuTransparencyLevel")
