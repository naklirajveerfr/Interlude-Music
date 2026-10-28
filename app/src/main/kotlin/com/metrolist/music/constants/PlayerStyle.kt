package com.metrolist.music.constants

import androidx.datastore.preferences.core.stringPreferencesKey

enum class PlayerStyle { DEFAULT, FULLART, SPOTIFY }

val PlayerStyleKey = stringPreferencesKey("playerStyle")
