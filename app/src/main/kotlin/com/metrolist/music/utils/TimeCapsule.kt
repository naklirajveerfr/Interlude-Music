/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.filterVideoSongs
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

data class TimeCapsule(
    val yearsAgo: Int,
    val songs: List<Song>,
)

/** Most played songs within 3 days of today in the closest past year (up to 3 years back) that has at least 3 songs. */
suspend fun MusicDatabase.findTimeCapsule(hideVideoSongs: Boolean = false): TimeCapsule? {
    val now = LocalDateTime.now()
    for (yearsAgo in 1..3) {
        val center = now.minusYears(yearsAgo.toLong())
        val songs = timeCapsuleSongs(center.minusDays(3), center.plusDays(3)).first()
            .filterVideoSongs(hideVideoSongs)
        if (songs.size >= 3) return TimeCapsule(yearsAgo, songs)
    }
    return null
}
