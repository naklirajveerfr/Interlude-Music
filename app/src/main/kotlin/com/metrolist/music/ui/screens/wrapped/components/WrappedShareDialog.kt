/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.ui.screens.wrapped.WrappedConstants
import com.metrolist.music.ui.theme.bbh_bartle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private enum class ShareContent { BOTH, SONGS, ARTISTS }

private const val SHARE_COUNT = 5

@Composable
fun WrappedShareDialog(
    topSongs: List<SongWithStats>,
    topArtists: List<Artist>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    var content by rememberSaveable { mutableStateOf(ShareContent.BOTH) }
    var sharing by remember { mutableStateOf(false) }
    val shareFailed = stringResource(R.string.wrapped_share_failed)
    val shareTitle = stringResource(R.string.wrapped_share)

    val songs = if (content != ShareContent.ARTISTS) topSongs.take(SHARE_COUNT) else emptyList()
    val artists = if (content != ShareContent.SONGS) topArtists.take(SHARE_COUNT) else emptyList()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = shareTitle,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = content == ShareContent.BOTH,
                        onClick = { content = ShareContent.BOTH },
                        label = { Text(stringResource(R.string.wrapped_share_both)) },
                    )
                    FilterChip(
                        selected = content == ShareContent.SONGS,
                        onClick = { content = ShareContent.SONGS },
                        label = { Text(stringResource(R.string.wrapped_share_songs)) },
                    )
                    FilterChip(
                        selected = content == ShareContent.ARTISTS,
                        onClick = { content = ShareContent.ARTISTS },
                        label = { Text(stringResource(R.string.wrapped_share_artists)) },
                    )
                }
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    WrappedShareCard(
                        songs = songs,
                        artists = artists,
                        modifier = Modifier
                            .width(320.dp)
                            .drawWithContent {
                                graphicsLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(graphicsLayer)
                            },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !sharing && (songs.isNotEmpty() || artists.isNotEmpty()),
                        onClick = {
                            sharing = true
                            scope.launch {
                                val shared = runCatching {
                                    val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
                                    val file = withContext(Dispatchers.IO) { saveShareImage(context, bitmap) }
                                    shareImage(context, file, shareTitle)
                                }.isSuccess
                                sharing = false
                                if (shared) onDismiss() else Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.share))
                    }
                }
            }
        }
    }
}

@Composable
private fun WrappedShareCard(
    songs: List<SongWithStats>,
    artists: List<Artist>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black, RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Text(
            text = stringResource(R.string.wrapped_intro_title),
            fontFamily = bbh_bartle,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.wrapped_share_card_title, WrappedConstants.YEAR),
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f),
        )

        if (songs.isNotEmpty()) {
            ShareSectionTitle(stringResource(R.string.wrapped_share_songs))
            songs.forEachIndexed { index, song ->
                ShareRow(
                    rank = index + 1,
                    imageUrl = song.thumbnailUrl,
                    title = song.title,
                    subtitle = song.artists.joinToString(", ") { it.name }.ifBlank { song.artistName.orEmpty() },
                    round = false,
                )
            }
        }
        if (artists.isNotEmpty()) {
            ShareSectionTitle(stringResource(R.string.wrapped_share_artists))
            artists.forEachIndexed { index, artist ->
                ShareRow(
                    rank = index + 1,
                    imageUrl = artist.artist.thumbnailUrl,
                    title = artist.artist.name,
                    subtitle = null,
                    round = true,
                )
            }
        }
    }
}

@Composable
private fun ShareSectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ShareRow(
    rank: Int,
    imageUrl: String?,
    title: String,
    subtitle: String?,
    round: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            fontFamily = bbh_bartle,
            fontSize = 20.sp,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.width(28.dp),
        )
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(if (round) CircleShape else RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun saveShareImage(
    context: Context,
    bitmap: android.graphics.Bitmap,
): File {
    val dir = File(context.cacheDir, "wrapped_share").apply { mkdirs() }
    val file = File(dir, "interlude_wrapped_${WrappedConstants.YEAR}.png")
    FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

private fun shareImage(
    context: Context,
    file: File,
    chooserTitle: String,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
