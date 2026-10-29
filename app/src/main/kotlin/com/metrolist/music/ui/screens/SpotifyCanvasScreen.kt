package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import timber.log.Timber

/** Connects (or disconnects) the Spotify account used for Spotify Canvas. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyCanvasScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by rememberPreference(SpotifyConnectedKey, false)
    var status by remember { mutableStateOf("Loading Spotify…") }
    var loading by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
    ) {
        if (connected) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.spotify_canvas_connected_info),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { scope.launch { SpotifySession.disconnect(context) } }) {
                    Text(stringResource(R.string.disconnect))
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webViewContext ->
                    var finished = false
                    WebView(webViewContext).apply {
                        SpotifySession.configure(this)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                status = "Loaded ${url?.substringBefore('?')} — ${view.title.orEmpty()}"
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    loading = false
                                    status = "Error ${error.errorCode}: ${error.description}"
                                    Timber.tag("SpotifyCanvas").w("Page error %s %s", error.errorCode, error.description)
                                }
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) {
                                    status = "HTTP ${response.statusCode} from ${request.url.host}"
                                    Timber.tag("SpotifyCanvas").w("HTTP error %s %s", response.statusCode, request.url)
                                }
                            }

                            // Once logged in, the web player makes authorised requests; the first one carries the token.
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                if (!finished && SpotifySession.hasSession()) {
                                    SpotifySession.tokenFrom(request)?.let { token ->
                                        finished = true
                                        scope.launch {
                                            SpotifySession.saveToken(context, token)
                                            CookieManager.getInstance().flush()
                                            navController.navigateUp()
                                        }
                                    }
                                }
                                return null
                            }
                        }
                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    }
                },
            )
        }

        if (!connected) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loading) CircularProgressIndicator()
                Text(text = status, style = MaterialTheme.typography.bodySmall)
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
}
