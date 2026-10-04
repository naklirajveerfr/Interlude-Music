package com.metrolist.music.constants

import androidx.datastore.preferences.core.stringPreferencesKey

enum class PlayerStyle { DEFAULT, FULLART }

val PlayerStyleKey = stringPreferencesKey("playerStyle")
