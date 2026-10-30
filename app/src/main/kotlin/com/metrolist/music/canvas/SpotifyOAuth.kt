package com.metrolist.music.canvas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SpotifyClientIdKey
import com.metrolist.music.constants.SpotifyConnectedKey
import com.metrolist.music.constants.SpotifyPkceVerifierKey
import com.metrolist.music.constants.SpotifyRefreshTokenKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyTokenKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom

/** Spotify login in the system browser (OAuth authorization code + PKCE), returning through [REDIRECT_URI]. */
object SpotifyOAuth {
    private const val TAG = "SpotifyOAuth"
    const val REDIRECT_URI = "interlude://spotify-callback"
    private const val SCOPES = "user-read-private user-read-email streaming"
    private val client = OkHttpClient()

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Opens Spotify's login page in the browser. The result arrives later in [handleCallback]. */
    suspend fun start(context: Context, clientId: String) {
        val verifier = base64Url(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        context.dataStore.edit {
            it[SpotifyClientIdKey] = clientId
            it[SpotifyPkceVerifierKey] = verifier
        }
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
        withContext(Dispatchers.Main) {
            val intent = CustomTabsIntent.Builder().build().apply {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent.launchUrl(context, url)
        }
    }

    /** Exchanges the code from the redirect for tokens. Returns false if Spotify refused. */
    suspend fun handleCallback(context: Context, uri: Uri): Boolean {
        val code = uri.getQueryParameter("code")
        if (code == null) {
            Timber.tag(TAG).w("Login returned no code: %s", uri.getQueryParameter("error"))
            return false
        }
        val prefs = context.dataStore.data.first()
        val clientId = prefs[SpotifyClientIdKey] ?: return false
        val verifier = prefs[SpotifyPkceVerifierKey] ?: return false
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return requestToken(context, body)
    }

    /** Gets a new access token from the saved refresh token, or null if there is none. */
    suspend fun refresh(context: Context): String? {
        val prefs = context.dataStore.data.first()
        val clientId = prefs[SpotifyClientIdKey] ?: return null
        val refresh = prefs[SpotifyRefreshTokenKey] ?: return null
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .build()
        return if (requestToken(context, body)) context.dataStore.data.first()[SpotifyTokenKey] else null
    }

    private suspend fun requestToken(context: Context, body: FormBody): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Token request failed: HTTP %d %s", response.code, text)
                    return@withContext false
                }
                val json = JSONObject(text)
                context.dataStore.edit {
                    it[SpotifyConnectedKey] = true
                    it[SpotifyTokenKey] = json.getString("access_token")
                    it[SpotifyTokenExpiryKey] = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
                    json.optString("refresh_token").takeIf(String::isNotEmpty)?.let { r -> it[SpotifyRefreshTokenKey] = r }
                    it.remove(SpotifyPkceVerifierKey)
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Token request failed")
            false
        }
    }
}
