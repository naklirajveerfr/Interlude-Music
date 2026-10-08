/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.constants.NotInterestedArtistsKey
import com.metrolist.music.constants.NotInterestedSongsKey
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Songs and artists the user asked not to be recommended. Stored in DataStore, so no database change is needed. */
object NotInterested {
    data class Blocked(
        val songs: Set<String> = emptySet(),
        val artists: Set<String> = emptySet(),
    ) {
        private fun hasBlockedArtist(ids: Iterable<String?>) = ids.any { it != null && it in artists }

        fun blocks(song: Song) = song.id in songs || hasBlockedArtist(song.artists.map { it.id })

        fun blocks(item: LocalItem): Boolean =
            when (item) {
                is Song -> blocks(item)
                is Album -> hasBlockedArtist(item.artists.map { it.id })
                is Artist -> item.id in artists
                else -> false
            }

        fun blocks(item: YTItem): Boolean =
            when (item) {
                is SongItem -> item.id in songs || hasBlockedArtist(item.artists.map { it.id })
                is AlbumItem -> hasBlockedArtist(item.artists.orEmpty().map { it.id })
                is ArtistItem -> item.id in artists
                else -> false
            }
    }

    // Outlives the menu that triggers a write, so closing the menu can't cancel it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun flow(context: Context): Flow<Blocked> =
        context.dataStore.data
            .map { Blocked(it[NotInterestedSongsKey].orEmpty(), it[NotInterestedArtistsKey].orEmpty()) }
            .distinctUntilChanged()

    suspend fun current(context: Context): Blocked = flow(context).first()

    fun blockSong(
        context: Context,
        songId: String,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            appContext.safeDataStoreEdit { it[NotInterestedSongsKey] = it[NotInterestedSongsKey].orEmpty() + songId }
        }
    }

    fun blockArtist(
        context: Context,
        artistId: String,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            appContext.safeDataStoreEdit { it[NotInterestedArtistsKey] = it[NotInterestedArtistsKey].orEmpty() + artistId }
        }
    }
}
