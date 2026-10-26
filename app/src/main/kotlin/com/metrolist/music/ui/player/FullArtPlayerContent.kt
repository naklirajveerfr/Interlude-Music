package com.metrolist.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.constants.PlayerButtonShape
import androidx.compose.ui.graphics.Color
import com.metrolist.music.constants.PlayerFadeStyle

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SmallIcon(res: Int, tint: Color, alpha: Float = 1f, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(res), null, tint = tint.copy(alpha = alpha))
    }
}

@Composable
private fun FilledSmall(res: Int, container: Color, content: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier.size(42.dp),
    ) {
        Icon(painterResource(res), null, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun BareButton(res: Int, tint: Color, iconSize: Dp, boxSize: Dp, onClick: () -> Unit, holdToSmoothSkip: Boolean = false) {
    IconButton(onClick = onClick, modifier = Modifier.size(boxSize).then(if (holdToSmoothSkip) Modifier.smoothSkipOnHold() else Modifier)) {
        Icon(painterResource(res), null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun RoundFilled(res: Int, container: Color, content: Color, boxSize: Dp, iconSize: Dp, onClick: () -> Unit, holdToSmoothSkip: Boolean = false) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier.size(boxSize).then(if (holdToSmoothSkip) Modifier.smoothSkipOnHold() else Modifier),
    ) {
        Icon(painterResource(res), null, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun BoxScope.FullArtBackdrop(
    artworkUrl: String?,
    fadeStyle: PlayerFadeStyle,
    title: String,
    artist: String,
    isPlaying: Boolean,
    showCanvas: Boolean = true,
) {
    if (fadeStyle == PlayerFadeStyle.TRANSLUCENT) {
        AsyncImage(
            model = artworkUrl, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(60.dp),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
            .align(Alignment.TopCenter)
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        AsyncImage(
            model = artworkUrl, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (showCanvas) {
            CanvasArtworkOverlay(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
                crop = true,
            )
        }
    }
}

@Composable
fun FullArtPlayerContent(
    artworkUrl: String?,
    title: String,
    artist: String,
    isPlaying: Boolean,
    isFavorite: Boolean,
    position: Long,
    duration: Long,
    shuffleOn: Boolean,
    repeatMode: Int,
    buttonShape: PlayerButtonShape,
    appleButtonColor: Color = Color.Unspecified,
    fadeStyle: PlayerFadeStyle = PlayerFadeStyle.TRANSLUCENT,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    textColor: Color,
    buttonColor: Color,
    iconColor: Color,
    sideButtonColor: Color,
    sideIconColor: Color,
    seekBar: @Composable () -> Unit,
    showArt: Boolean = true,
    showLyrics: Boolean = false,
    isFullScreen: Boolean = false,
    onFullScreen: () -> Unit = {},
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val total = duration.coerceAtLeast(1L)
    val favRes = if (isFavorite) R.drawable.favorite else R.drawable.favorite_border
    val playRes = if (isPlaying) R.drawable.nm_pause else R.drawable.nm_play_arrow

    // Every song change fades the old art into the new one. The old art is worked
    // out during composition so it is on screen in the first frame of the new song.
    val artRef = remember { object { var last: String? = artworkUrl } }
    val fadeFromArt = remember(artworkUrl) {
        val oldArt = artRef.last
        if (oldArt != null && oldArt != artworkUrl) oldArt else null
    }
    val artFadeAlpha = remember(artworkUrl) { Animatable(if (fadeFromArt != null) 1f else 0f) }
    var artFadeDone by remember(artworkUrl) { mutableStateOf(false) }
    LaunchedEffect(artworkUrl) {
        artRef.last = artworkUrl
        if (fadeFromArt != null) {
            artFadeAlpha.animateTo(0f, tween(500))
            artFadeDone = true
        }
    }

    Box(modifier.fillMaxSize()) {
        if (showArt) {
            FullArtBackdrop(artworkUrl, fadeStyle, title, artist, isPlaying)
            if (fadeFromArt != null && !artFadeDone) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = artFadeAlpha.value }) {
                    FullArtBackdrop(fadeFromArt, fadeStyle, title, artist, isPlaying, showCanvas = false)
                }
            }
        }

        overlay()

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title, color = textColor, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        artist, color = textColor.copy(alpha = 0.7f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                // Like button; while lyrics are showing it quickly fades into a fullscreen toggle.
                Crossfade(targetState = showLyrics, animationSpec = tween(150), label = "favOrFullscreen") { lyricsOn ->
                    val res = when {
                        !lyricsOn -> favRes
                        isFullScreen -> R.drawable.fullscreen_exit
                        else -> R.drawable.fullscreen
                    }
                    val click = if (lyricsOn) onFullScreen else onFavorite
                    when (buttonShape) {
                        PlayerButtonShape.APPLE -> BareButton(res, appleTint(textColor, appleButtonColor), 26.dp, 44.dp, click)
                        PlayerButtonShape.PILL -> BareButton(res, textColor, 26.dp, 44.dp, click)
                        PlayerButtonShape.ROUND -> FilledSmall(res, buttonColor, iconColor, click)
                    }
                }
                when (buttonShape) {
                    PlayerButtonShape.APPLE -> BareButton(R.drawable.more_horiz, appleTint(textColor, appleButtonColor), 26.dp, 44.dp, onMore)
                    PlayerButtonShape.PILL -> BareButton(R.drawable.more_horiz, textColor, 26.dp, 44.dp, onMore)
                    PlayerButtonShape.ROUND -> {
                        Spacer(Modifier.width(8.dp))
                        FilledSmall(R.drawable.more_horiz, buttonColor, iconColor, onMore)
                    }
                }
            }

            // Fullscreen lyrics: hide everything under the title row.
            AnimatedVisibility(visible = !isFullScreen) {
            Column {
            Spacer(Modifier.height(12.dp))
            seekBar()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(fmt(position), color = textColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("-" + fmt(total - position), color = textColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }

            when (buttonShape) {
                PlayerButtonShape.PILL -> {
                    val backSource = remember { MutableInteractionSource() }
                    val playSource = remember { MutableInteractionSource() }
                    val nextSource = remember { MutableInteractionSource() }
                    val backPressed by backSource.collectIsPressedAsState()
                    val playPressed by playSource.collectIsPressedAsState()
                    val nextPressed by nextSource.collectIsPressedAsState()
                    val pillSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
                    val playWeight by animateFloatAsState(
                        targetValue = if (playPressed) 1.9f else if (backPressed || nextPressed) 1.1f else 1.3f,
                        animationSpec = pillSpring, label = "playPauseWeight",
                    )
                    val backWeight by animateFloatAsState(
                        targetValue = if (backPressed) 0.65f else if (playPressed) 0.35f else 0.45f,
                        animationSpec = pillSpring, label = "backButtonWeight",
                    )
                    val nextWeight by animateFloatAsState(
                        targetValue = if (nextPressed) 0.65f else if (playPressed) 0.35f else 0.45f,
                        animationSpec = pillSpring, label = "nextButtonWeight",
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledIconButton(
                            onClick = onPrevious,
                            shape = RoundedCornerShape(50),
                            interactionSource = backSource,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = sideButtonColor, contentColor = sideIconColor),
                            modifier = Modifier.height(68.dp).weight(backWeight),
                        ) { Icon(painterResource(R.drawable.nm_skip_previous), null, modifier = Modifier.size(32.dp)) }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = onPlayPause,
                            shape = RoundedCornerShape(50),
                            interactionSource = playSource,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = buttonColor, contentColor = iconColor),
                            modifier = Modifier.height(68.dp).weight(playWeight),
                        ) { Icon(painterResource(playRes), null, modifier = Modifier.size(32.dp)) }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = onNext,
                            shape = RoundedCornerShape(50),
                            interactionSource = nextSource,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = sideButtonColor, contentColor = sideIconColor),
                            modifier = Modifier.height(68.dp).weight(nextWeight).smoothSkipOnHold(),
                        ) { Icon(painterResource(R.drawable.nm_skip_next), null, modifier = Modifier.size(32.dp)) }
                    }
                }

                PlayerButtonShape.ROUND -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundFilled(R.drawable.nm_skip_previous, sideButtonColor, sideIconColor, 68.dp, 32.dp, onPrevious)
                    RoundFilled(playRes, buttonColor, iconColor, 88.dp, 40.dp, onPlayPause)
                    RoundFilled(R.drawable.nm_skip_next, sideButtonColor, sideIconColor, 68.dp, 32.dp, onNext, holdToSmoothSkip = true)
                }

                PlayerButtonShape.APPLE -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BareButton(R.drawable.nm_skip_previous, appleTint(textColor, appleButtonColor), 44.dp, 72.dp, onPrevious)
                    BareButton(playRes, appleTint(textColor, appleButtonColor), 64.dp, 96.dp, onPlayPause)
                    BareButton(R.drawable.nm_skip_next, appleTint(textColor, appleButtonColor), 44.dp, 72.dp, onNext, holdToSmoothSkip = true)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallIcon(R.drawable.lyrics, textColor, 0.8f, onLyrics)
                SmallIcon(R.drawable.queue_music, textColor, 0.8f, onQueue)
            }
            }
            }
        }
    }
}

private fun appleTint(textColor: Color, apple: Color): Color =
    if (apple != Color.Unspecified) apple else textColor
