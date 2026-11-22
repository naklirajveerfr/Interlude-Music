/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.DefaultSpeedDialGlowBlur
import com.metrolist.music.constants.DefaultSpeedDialGlowSpread
import com.metrolist.music.constants.SpeedDialCarouselOutlineKey
import com.metrolist.music.constants.SpeedDialGlowBlurKey
import com.metrolist.music.constants.SpeedDialGlowKey
import com.metrolist.music.constants.SpeedDialGlowSpreadKey
import com.metrolist.music.ui.component.GlassTopAppBar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.drawSpeedDialGlow
import com.metrolist.music.ui.component.sampleCoverEdgeColors
import com.metrolist.music.ui.icons.painterResource
import com.metrolist.music.ui.screens.SpeedDialCard
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedDialCustomizeScreen(navController: NavController) {
    val (glow, onGlowChange) = rememberPreference(SpeedDialGlowKey, true)
    val (glowSpread, onGlowSpreadChange) = rememberPreference(SpeedDialGlowSpreadKey, DefaultSpeedDialGlowSpread)
    val (glowBlur, onGlowBlurChange) = rememberPreference(SpeedDialGlowBlurKey, DefaultSpeedDialGlowBlur)
    val (outline, onOutlineChange) = rememberPreference(SpeedDialCarouselOutlineKey, false)

    // Slider values are kept locally while dragging and saved when the finger lifts
    var spreadDraft by remember(glowSpread) { mutableFloatStateOf(glowSpread) }
    var blurDraft by remember(glowBlur) { mutableFloatStateOf(glowBlur) }

    // The preview uses the first real Speed dial item so it looks like the home screen
    val homeViewModel: HomeViewModel = hiltViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val homeFeed by homeViewModel.homeFeed.collectAsStateWithLifecycle()
    val sample = remember(homeFeed) { homeFeed.speedDialItems.firstOrNull { it !is ArtistItem } }
    val context = LocalContext.current
    val glowColors by produceState<List<Color>?>(null, sample?.thumbnail) {
        value = sample?.thumbnail?.resize(200, 200)?.let { sampleCoverEdgeColors(context, it) }
    }
    val glowFallback = MaterialTheme.colorScheme.primary

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        Spacer(Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .padding(horizontal = 48.dp)
                .size(width = 280.dp, height = 320.dp)
                .drawBehind {
                    if (glow) {
                        drawSpeedDialGlow(
                            card = Rect(0f, 0f, size.width, size.height),
                            edgeColors = glowColors,
                            fallback = glowFallback,
                            strength = 1f,
                            blurPx = blurDraft * density,
                            spreadPx = spreadDraft * density,
                            cornerPx = 28.dp.toPx(),
                        )
                    }
                },
        ) {
            SpeedDialCard(
                title = sample?.title ?: stringResource(R.string.speed_dial),
                subtitle = (sample as? SongItem)?.artists?.joinToString { it.name }.orEmpty(),
                thumbnail = sample?.thumbnail,
                onClick = {},
                onLongClick = {},
                showOutline = outline,
            )
        }

        Spacer(Modifier.height(40.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpeedDialSwitchRow(
                        title = stringResource(R.string.speed_dial_glow),
                        checked = glow,
                        onCheckedChange = onGlowChange,
                    )
                    SpeedDialSliderRow(
                        title = stringResource(R.string.speed_dial_glow_spread),
                        value = spreadDraft,
                        valueRange = 0f..24f,
                        enabled = glow,
                        onValueChange = { spreadDraft = it },
                        onValueChangeFinished = { onGlowSpreadChange(spreadDraft) },
                    )
                    SpeedDialSliderRow(
                        title = stringResource(R.string.speed_dial_glow_blur),
                        value = blurDraft,
                        valueRange = 0f..40f,
                        enabled = glow,
                        onValueChange = { blurDraft = it },
                        onValueChangeFinished = { onGlowBlurChange(blurDraft) },
                    )
                }

                SpeedDialSwitchRow(
                    title = stringResource(R.string.speed_dial_outline),
                    checked = outline,
                    onCheckedChange = onOutlineChange,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    GlassTopAppBar(
        title = { Text(stringResource(R.string.customize_speed_dial)) },
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

@Composable
private fun SpeedDialSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = {
                Icon(
                    painter = painterResource(id = if (checked) R.drawable.check else R.drawable.close),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}

@Composable
private fun SpeedDialSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${value.toInt()} dp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
        )
    }
}
