/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * Albums and singles from this year (last year too during January and February) by artists the user follows.
 * Artist pages only give a release year, so "new" means this year. At most [maxArtists] artists are checked,
 * favouring the ones listened to most.
 */
suspend fun fetchFollowedArtistReleases(
    database: MusicDatabase,
    maxArtists: Int,
): List<AlbumItem> {
    val today = LocalDate.now()
    val minYear = if (today.monthValue <= 2) today.year - 1 else today.year
    val followed = database.bookmarkedArtistEntitiesByNameAsc().filter { it.isYouTubeArtist }
    if (followed.isEmpty()) return emptyList()

    val artists =
        if (followed.size <= maxArtists) {
            followed
        } else {
            val scores = TasteProfile.artistScores(database)
            TasteProfile.pickWeighted(followed, maxArtists) { scores[it.id] ?: 0.0 }
        }

    return coroutineScope {
        artists.map { artist ->
            async(Dispatchers.IO) {
                val page = YouTube.artist(artist.id).getOrNull() ?: return@async emptyList<AlbumItem>()
                page.sections
                    .flatMap { it.items }
                    .filterIsInstance<AlbumItem>()
                    .filter { (it.year ?: 0) >= minYear }
                    .map { it.copy(artists = listOf(Artist(name = artist.name, id = artist.id))) }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }
}
