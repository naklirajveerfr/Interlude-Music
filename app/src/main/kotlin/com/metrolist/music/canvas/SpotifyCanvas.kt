package com.metrolist.music.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.SpotifyConnectedKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyTokenKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Spotify web session, used only to fetch Spotify Canvas videos.
 * The user logs in on open.spotify.com in a WebView; the web player's own bearer token is read
 * from its requests, so no API key or password is ever stored by the app.
 */
object SpotifySession {
    private const val TAG = "SpotifyCanvas"
    private const val TOKEN_LIFETIME_MS = 45 * 60 * 1000L
    private val tokenHosts = setOf("spclient.wg.spotify.com", "api-partner.spotify.com", "api.spotify.com")
    private val mutex = Mutex()

    fun hasSession(): Boolean =
        CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty().contains("sp_dc=")

    /** Returns the bearer token if this web-player request carries one. */
    fun tokenFrom(request: WebResourceRequest): String? {
        if (request.url.host !in tokenHosts) return null
        val header = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value ?: return null
        return header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") && it.length > 20 }
    }

    suspend fun saveToken(context: Context, token: String) {
        context.dataStore.edit {
            it[SpotifyConnectedKey] = true
            it[SpotifyTokenKey] = token
            it[SpotifyTokenExpiryKey] = System.currentTimeMillis() + TOKEN_LIFETIME_MS
        }
    }

    suspend fun disconnect(context: Context) {
        context.dataStore.edit {
            it[SpotifyConnectedKey] = false
            it.remove(SpotifyTokenKey)
            it.remove(SpotifyTokenExpiryKey)
        }
        withContext(Dispatchers.Main) {
            // Only Spotify's cookies are expired, so the YouTube login stored in the same WebView survives.
            val cookies = CookieManager.getInstance()
            listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com").forEach { url ->
                cookies.getCookie(url).orEmpty().split(";").mapNotNull { it.substringBefore("=").trim().takeIf(String::isNotEmpty) }
                    .forEach { name ->
                        cookies.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.spotify.com")
                    }
            }
            cookies.flush()
        }
    }

    suspend fun accessToken(context: Context): String? = mutex.withLock {
        val prefs = context.dataStore.data.first()
        if (prefs[SpotifyConnectedKey] != true) return@withLock null
        val stored = prefs[SpotifyTokenKey]
        if (stored != null && (prefs[SpotifyTokenExpiryKey] ?: 0L) > System.currentTimeMillis() + 60_000) {
            return@withLock stored
        }
        val fresh = harvest(context.applicationContext) ?: return@withLock null
        saveToken(context, fresh)
        fresh
    }

    /** Loads the web player in a hidden WebView (it reuses the saved login) and waits for its token. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvest(context: Context): String? = withTimeoutOrNull(25_000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val webView = WebView(context)
                fun finish(token: String?) {
                    if (!cont.isActive) return
                    webView.post {
                        webView.stopLoading()
                        webView.destroy()
                    }
                    cont.resume(token)
                }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        tokenFrom(request)?.let(::finish)
                        return null
                    }
                }
                cont.invokeOnCancellation { webView.post { webView.destroy() } }
                webView.loadUrl("https://open.spotify.com/")
            }
        }
    }
}

/** Looks up a Spotify Canvas video for a song through the connected Spotify session. */
object SpotifyCanvas {
    private const val TAG = "SpotifyCanvas"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(context: Context, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = SpotifySession.accessToken(context) ?: return@withContext null
            val trackId = searchTrack(token, title, artist) ?: return@withContext null
            fetchCanvas(token, trackId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Spotify canvas lookup failed")
            null
        }
    }

    private fun searchTrack(token: String, title: String, artist: String): String? {
        val query = URLEncoder.encode("track:$title artist:$artist", "UTF-8")
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$query&type=track&limit=5")
            .header("Authorization", "Bearer $token")
            .build()
        val body = client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val candidate = CanvasArtwork(
                name = item.optString("name"),
                artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name"),
            )
            if (candidate.matchesSong(title, artist)) return item.optString("id").takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun fetchCanvas(token: String, trackId: String): String? {
        val uri = "spotify:track:$trackId".toByteArray()
        // EntityCanvazRequest { entities: [ { entity_uri } ] }
        val entity = byteArrayOf(0x0A, uri.size.toByte()) + uri
        val payload = byteArrayOf(0x0A, entity.size.toByte()) + entity
        val request = Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/protobuf")
            .post(payload.toRequestBody("application/x-protobuf".toMediaType()))
            .build()
        val bytes = client.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return null
        return parseCanvasUrl(bytes)
    }

    // EntityCanvazResponse { canvases(1): Canvaz { url(2) } }
    private fun parseCanvasUrl(data: ByteArray): String? {
        var pos = 0
        fun varint(): Int {
            var result = 0
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        fun fields(start: Int, end: Int, onField: (Int, Int, Int) -> String?): String? {
            pos = start
            while (pos < end) {
                val tag = varint()
                val number = tag shr 3
                when (tag and 7) {
                    0 -> varint()
                    2 -> {
                        val len = varint()
                        val from = pos
                        val next = from + len
                        onField(number, from, next)?.let { return it }
                        pos = next
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> return null
                }
            }
            return null
        }
        return fields(0, data.size) { number, from, to ->
            if (number != 1) return@fields null
            val url = fields(from, to) { n, f, t ->
                if (n == 2) String(data, f, t - f) else null
            }
            url?.takeIf { it.startsWith("https://") }
        }
    }
}
