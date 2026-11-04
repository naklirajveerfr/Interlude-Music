/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.NewReleasesNotificationKey
import com.metrolist.music.constants.TimeCapsuleNotificationKey
import com.metrolist.music.notifications.NewReleasesScheduler
import com.metrolist.music.notifications.TimeCapsuleScheduler
import com.metrolist.music.ui.component.GlassTopAppBar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettings(navController: NavController) {
    val context = LocalContext.current
    val (timeCapsule, onTimeCapsuleChange) = rememberPreference(
        key = TimeCapsuleNotificationKey,
        defaultValue = false,
    )
    val (newReleases, onNewReleasesChange) = rememberPreference(
        key = NewReleasesNotificationKey,
        defaultValue = false,
    )

    // The switch that asked for the notification permission, applied once it is granted.
    var pendingApply by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingApply?.invoke()
        pendingApply = null
    }

    fun toggle(
        enabled: Boolean,
        apply: (Boolean) -> Unit,
    ) {
        val needsPermission = enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingApply = { apply(true) }
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            apply(enabled)
        }
    }

    val setTimeCapsule = { enabled: Boolean ->
        onTimeCapsuleChange(enabled)
        TimeCapsuleScheduler.update(context, enabled)
    }
    val setNewReleases = { enabled: Boolean ->
        onNewReleasesChange(enabled)
        NewReleasesScheduler.update(context, enabled)
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        Material3SettingsGroup(
            title = stringResource(R.string.notifications),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.history),
                    title = { Text(stringResource(R.string.time_capsule)) },
                    description = { Text(stringResource(R.string.time_capsule_notification_desc)) },
                    trailingContent = {
                        Switch(
                            checked = timeCapsule,
                            onCheckedChange = { toggle(it, setTimeCapsule) },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (timeCapsule) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { toggle(!timeCapsule, setTimeCapsule) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.album),
                    title = { Text(stringResource(R.string.new_releases_followed)) },
                    description = { Text(stringResource(R.string.new_releases_notification_desc)) },
                    trailingContent = {
                        Switch(
                            checked = newReleases,
                            onCheckedChange = { toggle(it, setNewReleases) },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (newReleases) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { toggle(!newReleases, setNewReleases) },
                ),
            ),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    GlassTopAppBar(
        title = { Text(stringResource(R.string.notifications)) },
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
