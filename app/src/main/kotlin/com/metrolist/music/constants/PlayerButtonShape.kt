package com.metrolist.music.constants

import androidx.datastore.preferences.core.stringPreferencesKey

enum class PlayerButtonShape { ROUND, PILL, APPLE }

val PlayerButtonShapeKey = stringPreferencesKey("playerButtonShape")
