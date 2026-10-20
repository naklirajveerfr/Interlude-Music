package com.metrolist.music.playback

enum class MixState {
    /** Smooth skip is off, so there is nothing to show. */
    OFF,
    NO_NEXT_SONG,
    ANALYZING_CURRENT,
    ANALYZING_NEXT,

    /** Both tempos are known and steady, so a smooth skip can beat match. */
    READY,

    /** A smooth skip works, but as a plain fade because the tempos are unknown or unsteady. */
    FADE_ONLY,
    FAILED,
}

data class MixStatus(
    val state: MixState,
    val bpmFrom: Int? = null,
    val bpmTo: Int? = null,
)
