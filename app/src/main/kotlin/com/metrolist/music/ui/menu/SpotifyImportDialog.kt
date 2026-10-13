/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.api.SpotifyPlaylistService
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun SpotifyImportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    val invalidUrlMessage = stringResource(R.string.spotify_import_invalid_url)
    val failedMessage = stringResource(R.string.spotify_import_failed)

    fun startImport() {
        val playlistId = SpotifyPlaylistService.parsePlaylistId(url)
        if (playlistId == null) {
            error = invalidUrlMessage
            return
        }
        error = null
        isImporting = true
        scope.launch(Dispatchers.IO) {
            val spotifyPlaylist = SpotifyPlaylistService.fetchPlaylist(playlistId).getOrElse {
                reportException(it)
                withContext(Dispatchers.Main) {
                    error = it.message ?: failedMessage
                    isImporting = false
                }
                return@launch
            }

            val total = spotifyPlaylist.tracks.size
            val done = AtomicInteger(0)
            val semaphore = Semaphore(10)
            val resolvedIds = arrayOfNulls<String>(total)

            spotifyPlaylist.tracks.mapIndexed { index, track ->
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
                                    }
                                }
                                .onFailure { reportException(it) }
                        } catch (e: Exception) {
                            reportException(e)
                        } finally {
                            progress = (done.incrementAndGet() * 100) / total
                        }
                    }
                }
            }.forEach { it.join() }

            val songIds = resolvedIds.filterNotNull().distinct()
            val newPlaylist = PlaylistEntity(name = spotifyPlaylist.name)
            database.query { insert(newPlaylist) }
            database.playlist(newPlaylist.id).firstOrNull()?.let { playlist ->
                database.addSongsToPlaylist(playlist, songIds.map { it to null })
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.spotify_import_done, songIds.size, total),
                    Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            }
        }
    }

    DefaultDialog(
        onDismiss = { if (!isImporting) onDismiss() },
        icon = { Icon(painterResource(R.drawable.download), contentDescription = null) },
        title = { Text(stringResource(R.string.spotify_import_title)) },
        buttons = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(onClick = ::startImport, enabled = !isImporting && url.isNotBlank()) {
                Text(stringResource(R.string.import_label))
            }
        },
    ) {
        Column {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isImporting,
                isError = error != null,
                label = { Text(stringResource(R.string.spotify_playlist_url)) },
                supportingText = error?.let { { Text(it) } },
            )
            if (isImporting) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.spotify_import_progress, progress),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
