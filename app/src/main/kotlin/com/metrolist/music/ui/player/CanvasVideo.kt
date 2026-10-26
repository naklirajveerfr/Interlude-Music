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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.media3.exoplayer.ExoPlayer
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var firstFrameRendered by remember(url) { mutableStateOf(false) }

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
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

    // Follow the music: pause with it, and whenever the app is in the background.
    DisposableEffect(exoPlayer, lifecycleOwner, isPlaying) {
        fun sync() {
            val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            exoPlayer.playWhenReady = isPlaying && started
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
        factory = { TextureView(it) },
        update = { exoPlayer.setVideoTextureView(it) },
        onRelease = { exoPlayer.clearVideoTextureView(it) },
        modifier = modifier.alpha(alpha),
    )
}
