package com.metrolist.music.constants

import androidx.datastore.preferences.core.stringPreferencesKey

enum class PlayerFadeStyle { TRANSLUCENT, GRADIENT }

val PlayerFadeStyleKey = stringPreferencesKey("playerFadeStyle")
