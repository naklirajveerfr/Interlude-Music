/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.PlayerBackgroundStyle
import com.metrolist.music.constants.PlayerButtonShape
import com.metrolist.music.constants.PlayerButtonShapeKey
import com.metrolist.music.constants.PlayerStyle
import com.metrolist.music.constants.PlayerStyleKey
import com.metrolist.music.constants.SliderStyle
import com.metrolist.music.constants.SliderStyleKey
import com.metrolist.music.constants.SquigglySliderKey
import com.metrolist.music.ui.component.PlayerSliderTrack
import com.metrolist.music.ui.component.SquigglySlider
import com.metrolist.music.ui.component.WavySlider
import com.metrolist.music.ui.theme.PlayerSliderColors
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

private const val INTRO_MS = 1800L
private const val LOADING_MS = 2500L
private const val LAST_STEP = 2
private const val STEP_ANIM_MS = 350

/**
 * Drop your screenshots in `app/src/main/res/drawable-nodpi/` with these exact names
 * (png, webp or jpg, portrait 9:16 works best). Until they exist a plain placeholder is shown.
 */
private const val PLAYER_STYLE_DEFAULT_IMAGE = "onboarding_player_default"
private const val PLAYER_STYLE_FULLART_IMAGE = "onboarding_player_fullart"

@Composable
fun CustomiseStage(onFinished: () -> Unit) {
    var intro by rememberSaveable { mutableStateOf(true) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var finishing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (intro) {
            delay(INTRO_MS)
            intro = false
        }
    }
    LaunchedEffect(finishing) {
        if (finishing) {
            delay(LOADING_MS)
            onFinished()
        }
    }

    if (finishing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ContainedLoadingIndicator()
        }
        return
    }

    val titleBias by animateFloatAsState(
        targetValue = if (intro) 0f else -1f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "customise_title",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_customise_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(BiasAlignment(0f, titleBias))
                    .fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = !intro,
            enter =
                fadeIn(tween(500, delayMillis = 500)) +
                    slideInVertically(tween(600, delayMillis = 400)) { it / 6 },
            modifier = Modifier.fillMaxSize().padding(top = 88.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            (slideInHorizontally(tween(STEP_ANIM_MS)) { it } + fadeIn(tween(STEP_ANIM_MS))) togetherWith
                                (slideOutHorizontally(tween(STEP_ANIM_MS)) { -it } + fadeOut(tween(STEP_ANIM_MS)))
                        },
                        label = "customise_step",
                    ) { current ->
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (current) {
                                0 -> PlayerStyleStep()
                                1 -> ButtonShapeStep()
                                else -> SliderStyleStep()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { if (step < LAST_STEP) step++ else finishing = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(if (step < LAST_STEP) R.string.next else R.string.continue_action))
                }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    )
}

@Composable
private fun selectableBorder(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

@Composable
private fun PlayerStyleStep() {
    val (playerStyle, onPlayerStyleChange) = rememberEnumPreference(PlayerStyleKey, PlayerStyle.DEFAULT)

    StepTitle(stringResource(R.string.onboarding_player_style_title))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayerStyleCard(
            imageName = PLAYER_STYLE_DEFAULT_IMAGE,
            label = stringResource(R.string.onboarding_player_style_default),
            selected = playerStyle == PlayerStyle.DEFAULT,
            onClick = { onPlayerStyleChange(PlayerStyle.DEFAULT) },
            modifier = Modifier.weight(1f),
        )
        PlayerStyleCard(
            imageName = PLAYER_STYLE_FULLART_IMAGE,
            label = stringResource(R.string.onboarding_player_style_fullart),
            selected = playerStyle == PlayerStyle.FULLART,
            onClick = { onPlayerStyleChange(PlayerStyle.FULLART) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerStyleCard(
    imageName: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRes = remember(imageName) { context.resources.getIdentifier(imageName, "drawable", context.packageName) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .border(if (selected) 2.dp else 1.dp, selectableBorder(selected), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (imageRes != 0) {
                Image(
                    painter = painterResource(imageRes),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ButtonShapeStep() {
    val (buttonShape, onButtonShapeChange) = rememberEnumPreference(PlayerButtonShapeKey, PlayerButtonShape.ROUND)

    StepTitle(stringResource(R.string.onboarding_button_shape_title))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            PlayerButtonShape.ROUND to R.string.onboarding_shape_round,
            PlayerButtonShape.PILL to R.string.onboarding_shape_pill,
            PlayerButtonShape.APPLE to R.string.onboarding_shape_soft,
        ).forEach { (shape, labelRes) ->
            val selected = buttonShape == shape
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(if (selected) 2.dp else 1.dp, selectableBorder(selected), RoundedCornerShape(16.dp))
                        .clickable { onButtonShapeChange(shape) }
                        .padding(vertical = 16.dp),
            ) {
                ButtonShapePreview(shape)
                Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ButtonShapePreview(shape: PlayerButtonShape) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val plain = MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (shape) {
            PlayerButtonShape.ROUND -> {
                PreviewButton(R.drawable.skip_previous, 48.dp, 48.dp, CircleShape, container, onContainer)
                PreviewButton(R.drawable.play, 64.dp, 64.dp, CircleShape, accent, onAccent)
                PreviewButton(R.drawable.skip_next, 48.dp, 48.dp, CircleShape, container, onContainer)
            }

            PlayerButtonShape.PILL -> {
                PreviewButton(R.drawable.skip_previous, 56.dp, 44.dp, RoundedCornerShape(50), container, onContainer)
                PreviewButton(R.drawable.play, 96.dp, 56.dp, RoundedCornerShape(50), accent, onAccent)
                PreviewButton(R.drawable.skip_next, 56.dp, 44.dp, RoundedCornerShape(50), container, onContainer)
            }

            PlayerButtonShape.APPLE -> {
                Icon(painterResource(R.drawable.nm_skip_previous), null, tint = plain, modifier = Modifier.size(40.dp))
                Icon(painterResource(R.drawable.nm_play_arrow), null, tint = plain, modifier = Modifier.size(56.dp))
                Icon(painterResource(R.drawable.nm_skip_next), null, tint = plain, modifier = Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun PreviewButton(
    icon: Int,
    width: Dp,
    height: Dp,
    shape: Shape,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier.size(width, height).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SliderStyleStep() {
    val (sliderStyle, onSliderStyleChange) = rememberEnumPreference(SliderStyleKey, SliderStyle.DEFAULT)
    val (squigglySlider, onSquigglySliderChange) = rememberPreference(SquigglySliderKey, false)
    val colors =
        PlayerSliderColors.getSliderColors(
            MaterialTheme.colorScheme.primary,
            PlayerBackgroundStyle.DEFAULT,
            isSystemInDarkTheme(),
        )

    StepTitle(stringResource(R.string.onboarding_slider_title))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SliderOption(
                label = stringResource(R.string.default_),
                selected = sliderStyle == SliderStyle.DEFAULT && !squigglySlider,
                onClick = {
                    onSliderStyleChange(SliderStyle.DEFAULT)
                    onSquigglySliderChange(false)
                },
                modifier = Modifier.weight(1f),
            ) {
                Slider(
                    value = 0.35f,
                    valueRange = 0f..1f,
                    onValueChange = {},
                    colors = colors,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
            }
            SliderOption(
                label = stringResource(R.string.wavy),
                selected = sliderStyle == SliderStyle.WAVY && !squigglySlider,
                onClick = {
                    onSliderStyleChange(SliderStyle.WAVY)
                    onSquigglySliderChange(false)
                },
                modifier = Modifier.weight(1f),
            ) {
                WavySlider(
                    value = 0.5f,
                    valueRange = 0f..1f,
                    onValueChange = {},
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    isPlaying = true,
                    enabled = false,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SliderOption(
                label = stringResource(R.string.slim),
                selected = sliderStyle == SliderStyle.SLIM,
                onClick = {
                    onSliderStyleChange(SliderStyle.SLIM)
                    onSquigglySliderChange(false)
                },
                modifier = Modifier.weight(1f),
            ) {
                val sliderState = remember { SliderState(value = 0.65f) }
                Slider(
                    state = sliderState,
                    thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                    track = { state -> PlayerSliderTrack(sliderState = state, colors = colors) },
                    colors = colors,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
            }
            SliderOption(
                label = stringResource(R.string.squiggly),
                selected = sliderStyle == SliderStyle.WAVY && squigglySlider,
                onClick = {
                    onSliderStyleChange(SliderStyle.WAVY)
                    onSquigglySliderChange(true)
                },
                modifier = Modifier.weight(1f),
            ) {
                SquigglySlider(
                    value = 0.5f,
                    valueRange = 0f..1f,
                    onValueChange = {},
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    colors = colors,
                    isPlaying = true,
                )
            }
        }
    }
}

@Composable
private fun SliderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(if (selected) 2.dp else 1.dp, selectableBorder(selected), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
    ) {
        preview()
        Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}
