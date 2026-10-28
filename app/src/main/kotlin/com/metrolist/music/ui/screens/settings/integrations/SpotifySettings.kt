/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.api.SpotifyApi
import com.metrolist.music.api.SpotifyAuth
import com.metrolist.music.api.SpotifyImporter
import com.metrolist.music.api.SpotifyUserPlaylist
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifyClientIdKey
import com.metrolist.music.constants.SpotifyDisplayNameKey
import com.metrolist.music.ui.component.GlassTopAppBar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.reportException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(navController: NavController) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    val accessToken by rememberPreference(SpotifyAccessTokenKey, "")
    val displayName by rememberPreference(SpotifyDisplayNameKey, "")
    var clientId by rememberPreference(SpotifyClientIdKey, "")
    val isLoggedIn = accessToken.isNotEmpty()

    var playlists by remember { mutableStateOf<List<SpotifyUserPlaylist>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var importingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    val addedIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            playlists = null
            return@LaunchedEffect
        }
        loadFailed = false
        SpotifyApi.fetchPlaylists(context)
            .onSuccess { playlists = it }
            .onFailure {
                reportException(it)
                loadFailed = true
            }
    }

    val topPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top).asPaddingValues()

    LazyColumn(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topPadding.calculateTopPadding(),
            bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (isLoggedIn) {
                            stringResource(R.string.spotify_connected_as, displayName.ifBlank { "Spotify" })
                        } else {
                            stringResource(R.string.spotify_integration)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (isLoggedIn) {
                    OutlinedButton(onClick = { scope.launch { SpotifyAuth.logout(context) } }) {
                        Text(stringResource(R.string.spotify_disconnect))
                    }
                } else {
                    Button(
                        enabled = clientId.isNotBlank(),
                        onClick = { scope.launch { SpotifyAuth.startLogin(context) } },
                    ) {
                        Text(stringResource(R.string.spotify_connect))
                    }
                }
            }
        }

        if (!isLoggedIn) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.spotify_client_id)) },
                    )
                    Text(
                        text = stringResource(R.string.spotify_client_id_help, SpotifyAuth.REDIRECT_URI),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (isLoggedIn) {
            item {
                Text(
                    text = stringResource(R.string.spotify_your_playlists),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            when {
                loadFailed -> item { Text(stringResource(R.string.spotify_load_failed)) }
                playlists == null -> item {
                    CircularProgressIndicator(Modifier.padding(16.dp).size(28.dp))
                }
                else -> items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    val added = playlist.id in addedIds
                    val importing = importingId == playlist.id
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = playlist.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            placeholder = painterResource(R.drawable.library_music),
                            error = painterResource(R.drawable.library_music),
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = stringResource(
                                    R.string.spotify_playlist_meta,
                                    playlist.owner,
                                    playlist.trackCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            enabled = !added && importingId == null,
                            onClick = {
                                importingId = playlist.id
                                progress = 0
                                scope.launch {
                                    val result = SpotifyApi.fetchPlaylist(context, playlist)
                                        .mapCatching { full ->
                                            SpotifyImporter.import(database, full) { progress = it }.getOrThrow()
                                        }
                                    importingId = null
                                    result.onSuccess {
                                        addedIds += playlist.id
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.spotify_import_done, it.matched, it.total),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }.onFailure {
                                        reportException(it)
                                        Toast.makeText(
                                            context,
                                            it.message ?: context.getString(R.string.spotify_import_failed),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                        ) {
                            when {
                                importing -> Text("$progress%")
                                added -> Icon(painterResource(R.drawable.check), contentDescription = null, modifier = Modifier.size(18.dp))
                                else -> Text(stringResource(R.string.spotify_add_playlist))
                            }
                        }
                    }
                }
            }
        }
    }

    GlassTopAppBar(
        title = { Text(stringResource(R.string.spotify_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
