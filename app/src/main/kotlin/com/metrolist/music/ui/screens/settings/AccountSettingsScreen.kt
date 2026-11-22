/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AccountChannelHandleKey
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.UseLoginForBrowse
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.ui.component.GlassTopAppBar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.icons.painterResource
import com.metrolist.music.ui.menu.SpotifyImportDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.AccountSettingsViewModel
import com.metrolist.music.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(navController: NavController) {
    val (innerTubeCookie, _) = rememberPreference(InnerTubeCookieKey, "")
    val (accountEmail, _) = rememberPreference(AccountEmailKey, "")
    val (accountChannelHandle, _) = rememberPreference(AccountChannelHandleKey, "")
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    // Same instance the home screen uses, so the name/avatar are already loaded.
    val activity = LocalContext.current as ComponentActivity
    val homeViewModel: HomeViewModel = hiltViewModel(viewModelStoreOwner = activity)
    val accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel()
    val accountName by homeViewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsStateWithLifecycle()

    var showToken by remember { mutableStateOf(false) }
    var showTokenEditor by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var showLoginMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding())
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoggedIn && accountImageUrl != null) {
                        AsyncImage(
                            model = accountImageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(if (isLoggedIn) R.drawable.account else R.drawable.login),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column {
                    Text(
                        text = if (isLoggedIn) accountName else stringResource(R.string.login),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = when {
                            !isLoggedIn -> stringResource(R.string.account_not_logged_in)
                            accountEmail.isNotBlank() -> accountEmail
                            else -> accountChannelHandle
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLoggedIn) {
                    OutlinedButton(onClick = { showLogoutDialog = true }) {
                        Text(stringResource(R.string.action_logout))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { navController.navigate("switch_channel") }) {
                        Text(stringResource(R.string.switch_youtube_channel))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Button(
                            onClick = { navController.navigate("login") },
                            shape = RoundedCornerShape(
                                topStart = 50.dp,
                                bottomStart = 50.dp,
                                topEnd = 6.dp,
                                bottomEnd = 6.dp,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.login),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.login))
                        }
                        Box {
                            FilledTonalButton(
                                onClick = { showLoginMenu = true },
                                shape = RoundedCornerShape(
                                    topStart = 6.dp,
                                    bottomStart = 6.dp,
                                    topEnd = 50.dp,
                                    bottomEnd = 50.dp,
                                ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.expand_more),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = showLoginMenu,
                                onDismissRequest = { showLoginMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_label)) },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.download), contentDescription = null)
                                    },
                                    onClick = {
                                        showLoginMenu = false
                                        showSpotifyImport = true
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showTokenEditor = true }) {
                        Text(stringResource(R.string.login_with_token))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.integrations),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.integration),
                    title = { Text(stringResource(R.string.integrations)) },
                    description = { Text(stringResource(R.string.integrations_summary)) },
                    onClick = { navController.navigate("settings/integrations") },
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.token),
                    title = {
                        Text(
                            when {
                                !isLoggedIn -> stringResource(R.string.advanced_login)
                                showToken -> stringResource(R.string.token_shown)
                                else -> stringResource(R.string.token_hidden)
                            },
                        )
                    },
                    onClick = {
                        if (!isLoggedIn) showTokenEditor = true
                        else if (!showToken) showToken = true
                        else showTokenEditor = true
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.more_content)) },
                    trailingContent = {
                        Switch(
                            enabled = isLoggedIn,
                            checked = useLoginForBrowse,
                            onCheckedChange = {
                                YouTube.useLoginForBrowse = it
                                onUseLoginForBrowseChange(it)
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (useLoginForBrowse) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    enabled = isLoggedIn,
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cached),
                    title = { Text(stringResource(R.string.yt_sync)) },
                    trailingContent = {
                        Switch(
                            enabled = isLoggedIn,
                            checked = ytmSync,
                            onCheckedChange = onYtmSyncChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (ytmSync) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    enabled = isLoggedIn,
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))
    }

    if (showLogoutDialog) {
        AccountLogoutDialog(
            viewModel = accountSettingsViewModel,
            onDismiss = { showLogoutDialog = false },
            onLoggedOut = { homeViewModel.refresh() },
        )
    }

    if (showTokenEditor) {
        AccountTokenEditorDialog(
            viewModel = accountSettingsViewModel,
            onDismiss = { showTokenEditor = false },
        )
    }

    if (showSpotifyImport) {
        SpotifyImportDialog(
            onDismiss = { showSpotifyImport = false },
            onImported = { playlistId ->
                showSpotifyImport = false
                navController.navigate("local_playlist/$playlistId")
            },
        )
    }

    GlassTopAppBar(
        title = { Text(stringResource(R.string.account)) },
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
