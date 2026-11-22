/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.metrolist.music.LocalNavController
import com.metrolist.music.ui.menu.SpotifyImportDialog
import com.yalantis.ucrop.UCrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.ui.icons.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.extensions.isSyncEnabled
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    initialTextFieldValue: String? = null,
    allowSyncing: Boolean = true,
    onPlaylistCreated: ((String) -> Unit)? = null,
) {
    val syncUtils = LocalSyncUtils.current
    val coroutineScope = rememberCoroutineScope()
    var syncedPlaylist by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val navController = LocalNavController.current

    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCropDestUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val editCoverStr = stringResource(R.string.edit_playlist_cover)
    val colorScheme = MaterialTheme.colorScheme

    val cropLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                val output = res.data?.let { UCrop.getOutput(it) } ?: pendingCropDestUri
                if (output != null) coverUri = output
            }
        }

    val pickLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { sourceUri ->
                val destFile = java.io.File(
                    java.io.File(context.cacheDir, "shared").apply { mkdirs() },
                    "playlist_cover_crop_${System.currentTimeMillis()}.jpg",
                )
                val destUri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", destFile)
                pendingCropDestUri = destUri

                val options =
                    UCrop.Options().apply {
                        setCompressionFormat(Bitmap.CompressFormat.JPEG)
                        setCompressionQuality(90)
                        setHideBottomControls(true)
                        setToolbarTitle(editCoverStr)
                        setStatusBarLight(colorScheme.surface.luminance() > 0.5f)
                        setToolbarColor(colorScheme.surface.toArgb())
                        setToolbarWidgetColor(colorScheme.inverseSurface.toArgb())
                        setRootViewBackgroundColor(colorScheme.surface.toArgb())
                        setLogoColor(colorScheme.surface.toArgb())
                    }

                val intent =
                    UCrop
                        .of(sourceUri, destUri)
                        .withAspectRatio(1f, 1f)
                        .withOptions(options)
                        .getIntent(context)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                cropLauncher.launch(intent)
            }
        }

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isSignedIn = innerTubeCookie.isNotEmpty()

    val notLoggedInYoutubeStr = stringResource(R.string.not_logged_in_youtube)
    val syncDisabledStr = stringResource(R.string.sync_disabled)
    val playlistCreatedLocallyStr = stringResource(R.string.playlist_created_locally)

    TextFieldDialog(
        icon = { Icon(painter = painterResource(R.drawable.add), contentDescription = null) },
        title = { Text(text = stringResource(R.string.create_playlist)) },
        initialTextFieldValue = TextFieldValue(initialTextFieldValue ?: ""),
        onDismiss = onDismiss,
        onDone = { playlistName ->
            syncUtils.createPlaylist(
                playlist = PlaylistEntity(
                    name = playlistName,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                    thumbnailUrl = coverUri?.toString(),
                ),
                syncWithYouTube = syncedPlaylist,
            ) { playlistId, remoteCreated ->
                if (syncedPlaylist && !remoteCreated) {
                    Toast.makeText(context, playlistCreatedLocallyStr, Toast.LENGTH_LONG).show()
                }
                onPlaylistCreated?.invoke(playlistId)
            }
        },
        topContent = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .size(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable {
                            pickLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = editCoverStr,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(160.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.queue_music),
                            contentDescription = editCoverStr,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(6.dp)
                                .size(20.dp),
                        )
                    }
                }
            }
        },
        extraButtons = {
            TextButton(onClick = { showImportDialog = true }) {
                Text(text = stringResource(R.string.import_label))
            }
        },
        extraContent = {
            if (allowSyncing) {
                Row(
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 40.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.sync_playlist),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.allows_for_sync_witch_youtube),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(0.7f),
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Switch(
                            checked = syncedPlaylist,
                            onCheckedChange = {
                                coroutineScope.launch {
                                    val isYtmSyncEnabled = withContext(Dispatchers.IO) { context.isSyncEnabled() }
                                    if (!isSignedIn && !syncedPlaylist) {
                                        Toast
                                            .makeText(
                                                context,
                                                notLoggedInYoutubeStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    } else if (!isYtmSyncEnabled) {
                                        Toast
                                            .makeText(
                                                context,
                                                syncDisabledStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    } else {
                                        syncedPlaylist = !syncedPlaylist
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    )

    if (showImportDialog) {
        SpotifyImportDialog(
            onDismiss = { showImportDialog = false },
            onImported = { playlistId ->
                showImportDialog = false
                navController.navigate("local_playlist/$playlistId")
                onDismiss()
            },
        )
    }
}
