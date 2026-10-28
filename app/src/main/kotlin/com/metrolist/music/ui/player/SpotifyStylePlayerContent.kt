/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.models.MediaMetadata
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Immersive player: the animated artwork fills the screen, with one live lyric line,
 * the title row (no thumbnail), seek bar and transport controls over the bottom.
 */
@Composable
fun SpotifyStylePlayerContent(
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    position: Long,
    duration: Long,
    shuffleOn: Boolean,
    repeatMode: Int,
    positionProvider: () -> Long,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onQueue: () -> Unit,
    onSeek: (Long) -> Unit,
    showLyrics: Boolean = false,
    onToggleLyrics: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = mediaMetadata?.title.orEmpty()
    val artist = mediaMetadata?.artists?.joinToString { it.name }.orEmpty()
    val albumName = mediaMetadata?.album?.title
    val total = duration.coerceAtLeast(1L)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = mediaMetadata?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        CanvasArtworkOverlay(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize(),
            crop = true,
        )
        // Scrims keep the white text readable on bright videos.
        Box(
            Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth().height(380.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
        )

        overlay()

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.expand_more), null, tint = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (albumName != null) {
                    Text(
                        stringResource(R.string.playing_from_album),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Text(
                        albumName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onMore) {
                Icon(painterResource(R.drawable.more_vert), null, tint = Color.White)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            if (!showLyrics) {
                LiveLyricLine(mediaMetadata, positionProvider, onClick = onToggleLyrics)
            }
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        painterResource(if (isFavorite) R.drawable.favorite else R.drawable.favorite_border),
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            var dragValue by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = (dragValue ?: position.toFloat()).coerceIn(0f, total.toFloat()),
                valueRange = 0f..total.toFloat(),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it.toLong()) }
                    dragValue = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((dragValue ?: position.toFloat()).toLong()), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Text(formatTime(total), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onShuffle) {
                    Icon(
                        painterResource(if (shuffleOn) R.drawable.shuffle_on else R.drawable.shuffle),
                        null,
                        tint = if (shuffleOn) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(painterResource(R.drawable.nm_skip_previous), null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(68.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.nm_pause else R.drawable.nm_play_arrow),
                        null,
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(R.drawable.nm_skip_next), null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = onRepeat) {
                    Icon(
                        painterResource(
                            when (repeatMode) {
                                1 -> R.drawable.repeat_one_on
                                2 -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            },
                        ),
                        null,
                        tint = if (repeatMode != 0) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }

            val bluetoothName = rememberBluetoothOutputName()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bluetoothName != null) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.bluetooth),
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            bluetoothName,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = onToggleLyrics) {
                    Icon(
                        painterResource(R.drawable.lyrics),
                        null,
                        tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = onQueue) {
                    Icon(painterResource(R.drawable.queue_music), null, tint = Color.White)
                }
            }
        }
    }
}

/** Parenthesised adlibs such as "(yeah)" or "(ooh)" are not part of the main sentence. */
private val AdlibRegex = Regex("""\s*[(\[][^)\]]*[)\]]""")

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The line being sung right now, sliding up on each change. Tapping it opens the full lyrics. Empty without synced lyrics. */
@Composable
private fun LiveLyricLine(mediaMetadata: MediaMetadata?, positionProvider: () -> Long, onClick: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    // Same lazy fetch the lyrics pane does, so the live line also works without opening it.
    LaunchedEffect(mediaMetadata?.id, currentLyrics) {
        if (mediaMetadata != null && currentLyrics == null) {
            delay(500)
            scope.launch(Dispatchers.IO) {
                try {
                    val helper = EntryPointAccessors
                        .fromApplication(context.applicationContext, com.metrolist.music.di.LyricsHelperEntryPoint::class.java)
                        .lyricsHelper()
                    val fetched = helper.getLyrics(mediaMetadata)
                    database.query { upsert(LyricsEntity(mediaMetadata.id, fetched.lyrics, fetched.provider)) }
                } catch (_: Exception) {
                }
            }
        }
    }

    val lines = remember(currentLyrics) {
        val raw = currentLyrics?.lyrics?.trim()
        if (raw.isNullOrBlank() || raw == LyricsEntity.LYRICS_NOT_FOUND) {
            emptyList()
        } else {
            LyricsUtils.parseLyrics(raw)
                .filterNot { it.isBackground }
                .map { it.copy(text = it.text.replace(AdlibRegex, "").replace("  ", " ").trim()) }
                .filter { it.text.isNotBlank() }
        }
    }
    val synced = lines.any { it.time > 0L }
    val lineText by remember(lines) {
        derivedStateOf {
            if (!synced) null else lines.getOrNull(LyricsUtils.findCurrentLineIndex(lines, positionProvider()))?.text
        }
    }

    // A line that has stayed the same for 5 seconds fades away until the next one arrives.
    var lineVisible by remember { mutableStateOf(true) }
    LaunchedEffect(lineText) {
        lineVisible = true
        delay(5_000)
        lineVisible = false
    }

    // Fixed height so the controls below do not jump when a line is empty or wraps.
    Box(
        Modifier.fillMaxWidth().height(48.dp).clipToBounds().clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = if (lineVisible) lineText.orEmpty() else "",
            transitionSpec = {
                if (targetState.isEmpty()) {
                    fadeIn(tween(1)) togetherWith fadeOut(tween(600))
                } else {
                    (slideInVertically(tween(350)) { it } + fadeIn(tween(350))) togetherWith
                        (slideOutVertically(tween(350)) { -it } + fadeOut(tween(250)))
                }
            },
            label = "liveLyricLine",
        ) { text ->
            Text(
                text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Name of the Bluetooth audio device currently connected for output, or null when there is none. */
@Composable
private fun rememberBluetoothOutputName(): String? {
    val context = LocalContext.current
    var name by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun isBluetooth(device: AudioDeviceInfo): Boolean =
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        fun refresh() {
            name = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull(::isBluetooth)
                ?.let { it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth" }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }
        refresh()
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    return name
}
