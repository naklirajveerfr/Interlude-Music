package com.metrolist.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.blur
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
private fun BareButton(res: Int, tint: Color, iconSize: Dp, boxSize: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(boxSize)) {
        Icon(painterResource(res), null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun RoundFilled(res: Int, container: Color, content: Color, boxSize: Dp, iconSize: Dp, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier.size(boxSize),
    ) {
        Icon(painterResource(res), null, modifier = Modifier.size(iconSize))
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
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val total = duration.coerceAtLeast(1L)
    val favRes = if (isFavorite) R.drawable.favorite else R.drawable.favorite_border
    val playRes = if (isPlaying) R.drawable.nm_pause else R.drawable.nm_play_arrow

    Box(modifier.fillMaxSize()) {
        if (showArt) {
            if (fadeStyle == PlayerFadeStyle.TRANSLUCENT) {
                AsyncImage(
                    model = artworkUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(60.dp),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }
            AsyncImage(
                model = artworkUrl, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
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
                if (buttonShape == PlayerButtonShape.APPLE) {
                    BareButton(favRes, appleTint(textColor, appleButtonColor), 26.dp, 44.dp, onFavorite)
                    BareButton(R.drawable.more_horiz, appleTint(textColor, appleButtonColor), 26.dp, 44.dp, onMore)
                } else {
                    FilledSmall(favRes, buttonColor, iconColor, onFavorite)
                    Spacer(Modifier.width(8.dp))
                    FilledSmall(R.drawable.more_horiz, buttonColor, iconColor, onMore)
                }
            }

            Spacer(Modifier.height(12.dp))
            seekBar()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(fmt(position), color = textColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("-" + fmt(total - position), color = textColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }

            when (buttonShape) {
                PlayerButtonShape.PILL -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = onPrevious,
                        shape = RoundedCornerShape(50),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = sideButtonColor, contentColor = sideIconColor),
                        modifier = Modifier.height(68.dp).weight(0.45f),
                    ) { Icon(painterResource(R.drawable.nm_skip_previous), null, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = onPlayPause,
                        shape = RoundedCornerShape(50),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = buttonColor, contentColor = iconColor),
                        modifier = Modifier.height(68.dp).weight(1.3f),
                    ) { Icon(painterResource(playRes), null, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = onNext,
                        shape = RoundedCornerShape(50),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = sideButtonColor, contentColor = sideIconColor),
                        modifier = Modifier.height(68.dp).weight(0.45f),
                    ) { Icon(painterResource(R.drawable.nm_skip_next), null, modifier = Modifier.size(32.dp)) }
                }

                PlayerButtonShape.ROUND -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundFilled(R.drawable.nm_skip_previous, sideButtonColor, sideIconColor, 68.dp, 32.dp, onPrevious)
                    RoundFilled(playRes, buttonColor, iconColor, 88.dp, 40.dp, onPlayPause)
                    RoundFilled(R.drawable.nm_skip_next, sideButtonColor, sideIconColor, 68.dp, 32.dp, onNext)
                }

                PlayerButtonShape.APPLE -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BareButton(R.drawable.nm_skip_previous, appleTint(textColor, appleButtonColor), 44.dp, 72.dp, onPrevious)
                    BareButton(playRes, appleTint(textColor, appleButtonColor), 64.dp, 96.dp, onPlayPause)
                    BareButton(R.drawable.nm_skip_next, appleTint(textColor, appleButtonColor), 44.dp, 72.dp, onNext)
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

private fun appleTint(textColor: Color, apple: Color): Color =
    if (apple != Color.Unspecified) apple else textColor
