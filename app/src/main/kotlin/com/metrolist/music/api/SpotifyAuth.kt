/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifyAuthStateKey
import com.metrolist.music.constants.SpotifyClientIdKey
import com.metrolist.music.constants.SpotifyCodeVerifierKey
import com.metrolist.music.constants.SpotifyDisplayNameKey
import com.metrolist.music.constants.SpotifyRefreshTokenKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Spotify login with the official Authorization Code + PKCE flow (no client secret in the app). */
object SpotifyAuth {
    const val REDIRECT_URI = "interlude://spotify-callback"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-read-private"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val refreshMutex = Mutex()

    /** The Client ID the user entered on the Spotify page, falling back to a build-time one. */
    suspend fun clientId(context: Context): String =
        context.dataStore.data.first()[SpotifyClientIdKey]?.trim().orEmpty()
            .ifBlank { BuildConfig.SPOTIFY_CLIENT_ID }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Opens Spotify's login page in the browser; the result returns through [handleCallback]. */
    suspend fun startLogin(context: Context) {
        val clientId = clientId(context)
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        context.dataStore.edit {
            it[SpotifyCodeVerifierKey] = verifier
            it[SpotifyAuthStateKey] = state
        }
        val url = Uri.Builder()
            .scheme("https").authority("accounts.spotify.com").path("authorize")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isCallback(uri: Uri): Boolean = uri.toString().startsWith(REDIRECT_URI)

    /** Exchanges the returned code for tokens. Returns an error message, or null on success. */
    suspend fun handleCallback(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let { return@withContext it }
        val code = uri.getQueryParameter("code") ?: return@withContext "No authorization code returned"
        val prefs = context.dataStore.data.first()
        if (uri.getQueryParameter("state") != prefs[SpotifyAuthStateKey]) return@withContext "Login state mismatch"
        val verifier = prefs[SpotifyCodeVerifierKey] ?: return@withContext "Login expired, try again"

        runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", clientId(context))
                .add("code_verifier", verifier)
                .build()
            saveTokens(context, requestTokens(body))
            context.dataStore.edit {
                it.remove(SpotifyCodeVerifierKey)
                it.remove(SpotifyAuthStateKey)
            }
            SpotifyApi.fetchDisplayName(context)?.let { name ->
                context.dataStore.edit { it[SpotifyDisplayNameKey] = name }
            }
            null
        }.getOrElse { it.message ?: "Spotify login failed" }
    }

    private fun requestTokens(body: FormBody): JSONObject {
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Spotify token error ${response.code}")
            JSONObject(text)
        }
    }

    private suspend fun saveTokens(context: Context, json: JSONObject) {
        context.dataStore.edit {
            it[SpotifyAccessTokenKey] = json.getString("access_token")
            json.optString("refresh_token").takeIf { token -> token.isNotEmpty() }
                ?.let { token -> it[SpotifyRefreshTokenKey] = token }
            it[SpotifyTokenExpiryKey] = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        }
    }

    /** A valid access token, refreshed when it is about to expire; null when not logged in. */
    suspend fun accessToken(context: Context): String? = refreshMutex.withLock {
        val prefs = context.dataStore.data.first()
        val token = prefs[SpotifyAccessTokenKey] ?: return@withLock null
        if ((prefs[SpotifyTokenExpiryKey] ?: 0L) > System.currentTimeMillis() + 60_000) return@withLock token
        val refresh = prefs[SpotifyRefreshTokenKey] ?: return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", clientId(context))
                    .build()
                val json = requestTokens(body)
                saveTokens(context, json)
                json.getString("access_token")
            }.getOrNull()
        }
    }

    suspend fun logout(context: Context) {
        context.dataStore.edit {
            it.remove(SpotifyAccessTokenKey)
            it.remove(SpotifyRefreshTokenKey)
            it.remove(SpotifyTokenExpiryKey)
            it.remove(SpotifyDisplayNameKey)
        }
    }
}
