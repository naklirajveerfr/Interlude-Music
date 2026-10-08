/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import java.time.LocalDateTime

/** One play of a song, attributed to one of its artists. Used to build the taste profile. */
data class ArtistListenEvent(
    val artistId: String,
    val timestamp: LocalDateTime,
    val playTime: Long,
    val duration: Int,
)

data class ArtistLikeCount(
    val artistId: String,
    val likedCount: Int,
)
