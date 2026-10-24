/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import com.metrolist.music.ui.component.GlassTopAppBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.SmoothSkipBassSwapKey
import com.metrolist.music.constants.SmoothSkipBeatAlignKey
import com.metrolist.music.constants.SmoothSkipDurationKey
import com.metrolist.music.constants.SmoothSkipEntrySnapKey
import com.metrolist.music.constants.SmoothSkipEqBlendKey
import com.metrolist.music.constants.SmoothSkipExitFxKey
import com.metrolist.music.constants.SmoothSkipLyricMuteKey
import com.metrolist.music.constants.SmoothSkipMaxTempoChangeKey
import com.metrolist.music.constants.SmoothSkipQuantizeKey
import com.metrolist.music.constants.SmoothSkipTempoStyle
import com.metrolist.music.constants.SmoothSkipTempoStyleKey
import com.metrolist.music.constants.SmoothSkipVocalMuteStrengthKey
import com.metrolist.music.constants.SmoothSkipVocalMuteStyle
import com.metrolist.music.constants.SmoothSkipVocalMuteStyleKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmoothSkipSettings(
    navController: NavController
) {
    val (smoothSkipDuration, onSmoothSkipDurationChange) = rememberPreference(SmoothSkipDurationKey, defaultValue = 5f)
    val (smoothSkipMaxTempoChange, onSmoothSkipMaxTempoChangeChange) = rememberPreference(SmoothSkipMaxTempoChangeKey, defaultValue = 8f)
    val (smoothSkipTempoStyle, onSmoothSkipTempoStyleChange) = rememberEnumPreference(
        SmoothSkipTempoStyleKey,
        defaultValue = SmoothSkipTempoStyle.PLAYING_SONG
    )
    val (smoothSkipBeatAlign, onSmoothSkipBeatAlignChange) = rememberPreference(SmoothSkipBeatAlignKey, defaultValue = false)
    val (smoothSkipBassSwap, onSmoothSkipBassSwapChange) = rememberPreference(SmoothSkipBassSwapKey, defaultValue = true)
    val (smoothSkipLyricMute, onSmoothSkipLyricMuteChange) = rememberPreference(SmoothSkipLyricMuteKey, defaultValue = true)
    val (vocalMuteStrength, onVocalMuteStrengthChange) = rememberPreference(SmoothSkipVocalMuteStrengthKey, defaultValue = 100f)
    val (vocalMuteStyle, onVocalMuteStyleChange) = rememberEnumPreference(
        SmoothSkipVocalMuteStyleKey,
        defaultValue = SmoothSkipVocalMuteStyle.NORMAL
    )

    val (quantize, onQuantizeChange) = rememberPreference(SmoothSkipQuantizeKey, defaultValue = true)
    val (eqBlend, onEqBlendChange) = rememberPreference(SmoothSkipEqBlendKey, defaultValue = true)
    val (entrySnap, onEntrySnapChange) = rememberPreference(SmoothSkipEntrySnapKey, defaultValue = true)
    val (exitFx, onExitFxChange) = rememberPreference(SmoothSkipExitFxKey, defaultValue = true)

    var showTempoStyleDialog by remember {
        mutableStateOf(false)
    }

    var showVocalMuteStyleDialog by remember {
        mutableStateOf(false)
    }

    if (showVocalMuteStyleDialog) {
        EnumDialog(
            onDismiss = { showVocalMuteStyleDialog = false },
            onSelect = {
                onVocalMuteStyleChange(it)
                showVocalMuteStyleDialog = false
            },
            title = stringResource(R.string.smooth_skip_mute_style),
            current = vocalMuteStyle,
            values = SmoothSkipVocalMuteStyle.values().toList(),
            valueText = {
                stringResource(
                    when (it) {
                        SmoothSkipVocalMuteStyle.NORMAL -> R.string.smooth_skip_mute_style_normal
                        SmoothSkipVocalMuteStyle.NEXT_SONG_START -> R.string.smooth_skip_mute_style_next_song
                        SmoothSkipVocalMuteStyle.ADAPTIVE -> R.string.smooth_skip_mute_style_adaptive
                    }
                )
            }
        )
    }

    if (showTempoStyleDialog) {
        EnumDialog(
            onDismiss = { showTempoStyleDialog = false },
            onSelect = {
                onSmoothSkipTempoStyleChange(it)
                showTempoStyleDialog = false
            },
            title = "Tempo change style",
            current = smoothSkipTempoStyle,
            values = SmoothSkipTempoStyle.values().toList(),
            valueText = {
                when (it) {
                    SmoothSkipTempoStyle.PLAYING_SONG -> "Playing song changes"
                    SmoothSkipTempoStyle.NEXT_SONG -> "Next song changes"
                    SmoothSkipTempoStyle.BOTH -> "Both change equally"
                }
            }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.smooth_skip_configuration),
            items = buildList {
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.timer),
                    title = { Text("Smooth skip duration") },
                    description = {
                        Column {
                            Text(pluralStringResource(R.plurals.seconds, smoothSkipDuration.toInt(), smoothSkipDuration.toInt()))
                            Slider(
                                value = smoothSkipDuration,
                                onValueChange = onSmoothSkipDurationChange,
                                valueRange = 1f..15f,
                                steps = 14
                            )
                        }
                    }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text("Tempo change style") },
                    description = {
                        Text(
                            if (smoothSkipBeatAlign) {
                                "Next song changes (set by Beat align)"
                            } else {
                                when (smoothSkipTempoStyle) {
                                    SmoothSkipTempoStyle.PLAYING_SONG -> "Playing song changes"
                                    SmoothSkipTempoStyle.NEXT_SONG -> "Next song changes"
                                    SmoothSkipTempoStyle.BOTH -> "Both change equally"
                                }
                            }
                        )
                    },
                    enabled = !smoothSkipBeatAlign,
                    onClick = { showTempoStyleDialog = true }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.speed),
                    title = { Text("Max tempo change") },
                    description = {
                        Column {
                            Text("Songs can speed up or slow down by up to ${smoothSkipMaxTempoChange.roundToInt()}% to match each other's tempo")
                            Slider(
                                value = smoothSkipMaxTempoChange,
                                onValueChange = onSmoothSkipMaxTempoChangeChange,
                                valueRange = 5f..100f
                            )
                        }
                    }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text("Beat align (beta)") },
                    description = { Text("Also line up the beats of both songs when you smooth skip. Uses the \"Next song changes\" tempo style") },
                    trailingContent = {
                        Switch(
                            checked = smoothSkipBeatAlign,
                            onCheckedChange = onSmoothSkipBeatAlignChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (smoothSkipBeatAlign) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSmoothSkipBeatAlignChange(!smoothSkipBeatAlign) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.equalizer),
                    title = { Text("Bass swap") },
                    description = { Text("Trade the two songs' basslines in the middle of a smooth skip instead of playing both at once") },
                    trailingContent = {
                        Switch(
                            checked = smoothSkipBassSwap,
                            onCheckedChange = onSmoothSkipBassSwapChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (smoothSkipBassSwap) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSmoothSkipBassSwapChange(!smoothSkipBassSwap) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.smooth_skip_mute_vocals)) },
                    description = { Text(stringResource(R.string.smooth_skip_mute_vocals_desc)) },
                    trailingContent = {
                        Switch(
                            checked = smoothSkipLyricMute,
                            onCheckedChange = onSmoothSkipLyricMuteChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (smoothSkipLyricMute) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSmoothSkipLyricMuteChange(!smoothSkipLyricMute) }
                ))
                if (smoothSkipLyricMute) {
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.sync),
                        title = { Text(stringResource(R.string.smooth_skip_mute_style)) },
                        description = {
                            Text(
                                stringResource(
                                    when (vocalMuteStyle) {
                                        SmoothSkipVocalMuteStyle.NORMAL -> R.string.smooth_skip_mute_style_normal_desc
                                        SmoothSkipVocalMuteStyle.NEXT_SONG_START -> R.string.smooth_skip_mute_style_next_song_desc
                                        SmoothSkipVocalMuteStyle.ADAPTIVE -> R.string.smooth_skip_mute_style_adaptive_desc
                                    }
                                )
                            )
                        },
                        onClick = { showVocalMuteStyleDialog = true }
                    ))
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.volume_up),
                        title = { Text(stringResource(R.string.smooth_skip_mute_strength)) },
                        description = {
                            Column {
                                Text(stringResource(R.string.smooth_skip_mute_strength_desc, vocalMuteStrength.roundToInt()))
                                Slider(
                                    value = vocalMuteStrength,
                                    onValueChange = onVocalMuteStrengthChange,
                                    valueRange = 0f..100f
                                )
                            }
                        }
                    ))
                }
            }
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.smooth_skip_dj_style),
            items = listOf(
                djSwitchItem(
                    icon = painterResource(R.drawable.sync),
                    title = stringResource(R.string.smooth_skip_quantize),
                    description = stringResource(R.string.smooth_skip_quantize_desc),
                    checked = quantize,
                    onChange = onQuantizeChange
                ),
                djSwitchItem(
                    icon = painterResource(R.drawable.equalizer),
                    title = stringResource(R.string.smooth_skip_eq_blend),
                    description = stringResource(R.string.smooth_skip_eq_blend_desc),
                    checked = eqBlend,
                    onChange = onEqBlendChange
                ),
                djSwitchItem(
                    icon = painterResource(R.drawable.skip_next),
                    title = stringResource(R.string.smooth_skip_entry_snap),
                    description = stringResource(R.string.smooth_skip_entry_snap_desc),
                    checked = entrySnap,
                    onChange = onEntrySnapChange
                ),
                djSwitchItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = stringResource(R.string.smooth_skip_exit_fx),
                    description = stringResource(R.string.smooth_skip_exit_fx_desc),
                    checked = exitFx,
                    onChange = onExitFxChange
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    GlassTopAppBar(
        title = { Text(stringResource(R.string.smooth_skip_configuration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}

private fun djSwitchItem(
    icon: Painter,
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) = Material3SettingsItem(
    icon = icon,
    title = { Text(title) },
    description = { Text(description) },
    trailingContent = {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            thumbContent = {
                Icon(
                    painter = painterResource(
                        id = if (checked) R.drawable.check else R.drawable.close
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        )
    },
    onClick = { onChange(!checked) }
)
