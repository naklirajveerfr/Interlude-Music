package com.metrolist.music.constants

import androidx.datastore.preferences.core.floatPreferencesKey

val IconRoundednessKey = floatPreferencesKey("iconRoundedness")

@androidx.compose.runtime.Composable
fun SyncIconRoundness() {
    val (roundness, _) = com.metrolist.music.utils.rememberPreference(IconRoundednessKey, defaultValue = 3f)
    androidx.compose.runtime.SideEffect {
        setThumbnailCornerRadius(androidx.compose.ui.unit.Dp(roundness))
    }
}
