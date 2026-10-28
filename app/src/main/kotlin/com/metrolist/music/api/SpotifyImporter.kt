/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

data class SpotifyImportResult(val playlistId: String, val matched: Int, val total: Int)

/** Copies a Spotify playlist into a local playlist by matching each track on YouTube Music. */
object SpotifyImporter {
    suspend fun import(
        database: MusicDatabase,
        spotifyPlaylist: SpotifyPlaylist,
        onProgress: (Int) -> Unit = {},
    ): Result<SpotifyImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val total = spotifyPlaylist.tracks.size
            val done = AtomicInteger(0)
            val semaphore = Semaphore(10)
            val resolvedIds = arrayOfNulls<String>(total)
            val resolvedThumbnails = arrayOfNulls<String>(total)

            coroutineScope {
                spotifyPlaylist.tracks.forEachIndexed { index, track ->
                    launch {
                        semaphore.withPermit {
                            try {
                                val query = "${track.title} ${track.artists.joinToString(" ")}"
                                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                                    .onSuccess { result ->
                                        val match = result.items.firstOrNull() as? SongItem
                                        if (match != null) {
                                            database.insert(match.toMediaMetadata())
                                            resolvedIds[index] = match.id
                                            resolvedThumbnails[index] = match.thumbnail
                                        }
                                    }
                                    .onFailure { reportException(it) }
                            } catch (e: Exception) {
                                reportException(e)
                            } finally {
                                onProgress((done.incrementAndGet() * 100) / total)
                            }
                        }
                    }
                }
            }

            val songIds = resolvedIds.filterNotNull().distinct()
            val newPlaylist = PlaylistEntity(
                name = spotifyPlaylist.name,
                bookmarkedAt = LocalDateTime.now(),
                isEditable = true,
                thumbnailUrl = spotifyPlaylist.coverUrl ?: resolvedThumbnails.firstNotNullOfOrNull { it },
            )
            database.insert(newPlaylist)
            val playlist = database.playlist(newPlaylist.id).firstOrNull() ?: error("Playlist was not created")
            database.addSongsToPlaylist(playlist, songIds.map { it to null })
            SpotifyImportResult(newPlaylist.id, songIds.size, total)
        }
    }
}
