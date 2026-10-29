/*
 * Adapted from ArchiveTune (github.com/rukamori/ArchiveTune), GPL-3.0.
 * © Rukamori — original notice retained per GPL-3.0 Sections 4 & 5.
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.metrolist.music.ui.player

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.canvas.CanvasRepository
import com.metrolist.music.canvas.CanvasSource
import com.metrolist.music.canvas.SpotifyCanvas
import com.metrolist.music.constants.CanvasEnabledKey
import com.metrolist.music.constants.CanvasSourceKey
import com.metrolist.music.constants.SpotifyCanvasEnabledKey
import com.metrolist.music.constants.SpotifyConnectedKey
import com.metrolist.music.constants.PlayerStyle
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.canvas.CanvasPrefetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * Muted, looping animated artwork. Fades in once the first frame renders,
 * so the static cover underneath stays visible until then (and on any error).
 */
@Composable
fun CanvasVideo(
    url: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    keepPlaying: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    val textureView = remember(url) { TextureView(context) }

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setForceHighestSupportedBitrate(true)
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            val mime = if (url.lowercase(Locale.ROOT).contains("m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4
            setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (crop) textureView.centerCrop(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.tag("Canvas").w(error, "Canvas playback failed")
                firstFrameRendered = false
                exoPlayer.stop()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Follow the music: pause with it (unless keepPlaying), and whenever the app is in the background.
    DisposableEffect(exoPlayer, lifecycleOwner, isPlaying, keepPlaying) {
        fun sync() {
            val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            exoPlayer.playWhenReady = (isPlaying || keepPlaying) && started
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycleOwner.lifecycle.addObserver(observer)
        sync()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val alpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "canvasAlpha",
    )

    AndroidView(
        factory = { textureView },
        update = {
            exoPlayer.setVideoTextureView(it)
            if (crop) it.centerCrop(exoPlayer.videoSize)
        },
        onRelease = { exoPlayer.clearVideoTextureView(it) },
        modifier = modifier.alpha(alpha),
    )
}

/** TextureView stretches video to its bounds; scale it so the video fills the view without distortion. */
private fun TextureView.centerCrop(videoSize: VideoSize) {
    if (videoSize.width == 0 || videoSize.height == 0) return
    val apply = {
        if (width > 0 && height > 0) {
            val videoAspect = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
            val viewAspect = width.toFloat() / height
            // The view is stretched to the video, so undo that stretch, then scale up to cover.
            val fitX = if (videoAspect > viewAspect) 1f else videoAspect / viewAspect
            val fitY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f
            val cover = 1f / minOf(fitX, fitY).coerceAtLeast(0.0001f)
            scaleX = fitX * cover
            scaleY = fitY * cover
        }
    }
    if (width > 0 && height > 0) apply() else post(apply)
}

/**
 * Animated artwork for the current song, shown over the static cover when "Use canvas" is on.
 * Draws nothing until a source returns a video.
 */
@Composable
fun CanvasArtworkOverlay(
    title: String,
    artist: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    allowSpotify: Boolean = false,
) {
    val canvasEnabled by rememberPreference(CanvasEnabledKey, true)
    if (!canvasEnabled) return

    val context = LocalContext.current
    val sourcePref by rememberPreference(CanvasSourceKey, CanvasSource.ALL.name)
    val spotifyEnabled by rememberPreference(SpotifyCanvasEnabledKey, false)
    val spotifyConnected by rememberPreference(SpotifyConnectedKey, false)
    val spotifyActive = allowSpotify && spotifyEnabled
    val useSpotify = spotifyActive && spotifyConnected
    val canvas by produceState<ResolvedCanvas?>(initialValue = null, title, artist, sourcePref, spotifyActive, useSpotify) {
        value = null
        // With Spotify canvas on, the source picker is unavailable and every other source is a fallback.
        val fallbackSource = if (spotifyActive) CanvasSource.ALL else CanvasSource.fromPreference(sourcePref)
        if (!useSpotify) {
            value = CanvasRepository.resolve(title, artist, fallbackSource)?.let { ResolvedCanvas(it, spotify = false) }
            return@produceState
        }
        // Spotify's lookup can be slow, so the other sources run alongside it: their result is shown straight
        // away and Spotify's replaces it if it finds one.
        coroutineScope {
            val spotify = async { SpotifyCanvas.resolve(context, title, artist) }
            val fallback = async { CanvasRepository.resolve(title, artist, fallbackSource) }
            launch {
                val early = fallback.await()
                if (!spotify.isCompleted && early != null) value = ResolvedCanvas(early, spotify = false)
            }
            val fromSpotify = spotify.await()
            value = if (fromSpotify != null) {
                ResolvedCanvas(fromSpotify, spotify = true)
            } else {
                fallback.await()?.let { ResolvedCanvas(it, spotify = false) }
            }
        }
    }

    canvas?.let { resolved ->
        // Spotify canvases keep looping while the song is paused.
        CanvasVideo(url = resolved.url, isPlaying = isPlaying, modifier = modifier, crop = crop, keepPlaying = resolved.spotify)
    }
}

private data class ResolvedCanvas(val url: String, val spotify: Boolean)

/**
 * Resolves the canvas for the next song in the queue ahead of time, so it is ready when that song starts.
 * Uses the same sources and caches as [CanvasArtworkOverlay].
 */
@Composable
fun CanvasPrefetchEffect() {
    val context = LocalContext.current
    val playerConnection = com.metrolist.music.LocalPlayerConnection.current ?: return
    val canvasEnabled by rememberPreference(CanvasEnabledKey, true)
    val sourcePref by rememberPreference(CanvasSourceKey, CanvasSource.ALL.name)
    val spotifyEnabled by rememberPreference(SpotifyCanvasEnabledKey, false)
    val spotifyConnected by rememberPreference(SpotifyConnectedKey, false)
    val playerStyle by rememberEnumPreference(com.metrolist.music.constants.PlayerStyleKey, PlayerStyle.DEFAULT)
    val current by playerConnection.mediaMetadata.collectAsState()

    LaunchedEffect(current?.id, canvasEnabled, sourcePref, spotifyEnabled, spotifyConnected, playerStyle) {
        if (!canvasEnabled) return@LaunchedEffect
        val player = playerConnection.player
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return@LaunchedEffect
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        val title = next.title?.toString().orEmpty()
        val artist = next.artist?.toString().orEmpty()
        if (title.isBlank()) return@LaunchedEffect
        val spotifyActive = spotifyEnabled && playerStyle != PlayerStyle.DEFAULT
        val source = if (spotifyActive) CanvasSource.ALL else CanvasSource.fromPreference(sourcePref)
        CanvasPrefetcher.prefetch(context, title, artist, source, spotify = spotifyActive && spotifyConnected)
    }
}
