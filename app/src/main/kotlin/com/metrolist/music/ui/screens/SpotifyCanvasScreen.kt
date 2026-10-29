package com.metrolist.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.canvas.SpotifySession
import com.metrolist.music.constants.SpotifyConnectedKey
import com.metrolist.music.ui.component.GlassTopAppBar
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

/** Connects (or disconnects) the Spotify account used for Spotify Canvas. */
@Composable
fun SpotifyCanvasScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by rememberPreference(SpotifyConnectedKey, false)
    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }
    var cookieError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (connected) {
                Text(
                    text = stringResource(R.string.spotify_canvas_connected_info),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { scope.launch { SpotifySession.disconnect(context) } }) {
                    Text(stringResource(R.string.disconnect))
                }
            } else {
                Text(
                    text = stringResource(R.string.spotify_canvas_connect_steps),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { cookieError = false; showCookieDialog = true }) {
                    Text(stringResource(R.string.spotify_canvas_paste_cookie))
                }
            }
        }

        GlassTopAppBar(
            title = { Text(stringResource(R.string.spotify_canvas_title)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { if (!cookieBusy) showCookieDialog = false },
            title = { Text(stringResource(R.string.spotify_canvas_cookie_title)) },
            text = {
                OutlinedTextField(
                    value = cookieText,
                    onValueChange = { cookieText = it },
                    singleLine = true,
                    isError = cookieError,
                    label = { Text("sp_dc") },
                    supportingText = if (cookieError) {
                        { Text(stringResource(R.string.spotify_canvas_cookie_rejected)) }
                    } else {
                        null
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !cookieBusy && cookieText.isNotBlank(),
                    onClick = {
                        cookieBusy = true
                        cookieError = false
                        scope.launch {
                            val ok = SpotifySession.connectWithCookie(context, cookieText)
                            cookieBusy = false
                            if (ok) {
                                showCookieDialog = false
                                cookieText = ""
                            } else {
                                cookieError = true
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (cookieBusy) R.string.spotify_canvas_checking else R.string.spotify_canvas_connect,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !cookieBusy, onClick = { showCookieDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
