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
import kotlinx.coroutines.delay
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.ByteArrayInputStream
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

    // Spotify's login refuses the default WebView user agent (it contains "wv").
    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WEB_USER_AGENT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // The app's dark theme otherwise makes Android recolour the page, which can hide Spotify's text.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = false
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
            }
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

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

    /** Signs in with an sp_dc cookie copied from a desktop browser. Returns false if Spotify gives no token for it. */
    suspend fun connectWithCookie(context: Context, spDc: String): Boolean {
        val value = spDc.trim().removePrefix("sp_dc=").trim().trim('"', ';')
        if (value.length < 20 || value.any { it.isWhitespace() }) return false
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setCookie("https://open.spotify.com", "sp_dc=$value; Domain=.spotify.com; Path=/; Secure; HttpOnly")
            cookies.flush()
        }
        context.dataStore.edit { it[SpotifyConnectedKey] = true }
        if (accessToken(context) != null) return true
        disconnect(context)
        return false
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
                configure(webView)
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
            val token = SpotifySession.accessToken(context)
            if (token == null) {
                Timber.tag(TAG).w("No Spotify token (session expired or token capture failed)")
                return@withContext null
            }
            val trackId = searchTrack(context, title, artist)
            if (trackId == null) {
                Timber.tag(TAG).w("No Spotify track matched: %s - %s", artist, title)
                return@withContext null
            }
            val url = fetchCanvas(token, trackId)
            Timber.tag(TAG).d("Spotify canvas for %s: %s", trackId, url ?: "none")
            url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Spotify canvas lookup failed")
            null
        }
    }

    private val idCache = object : LinkedHashMap<String, String?>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 200
    }
    private val searchMutex = Mutex()

    private val pageClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    // The web player shows "Playback of protected content is not enabled" and nothing else when the WebView
    // has no DRM. This makes that check pass; the page is only used to read search results, never to play.
    private const val DRM_STUB = """(function(){
        var access={keySystem:'com.widevine.alpha',getConfiguration:function(){return {}},
            createMediaKeys:function(){return Promise.reject(new Error('unsupported'))}};
        var f=function(){return Promise.resolve(access)};
        try{Object.defineProperty(navigator,'requestMediaKeySystemAccess',{value:f,configurable:true})}
        catch(e){navigator.requestMediaKeySystemAccess=f}
    })();"""

    private val heavyExtensions = setOf("woff", "woff2", "ttf", "otf", "png", "jpg", "jpeg", "webp", "gif", "svg", "mp3", "mp4", "m4a", "ogg")
    private val imageHosts = setOf("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com")

    private fun isHeavyResource(url: android.net.Uri): Boolean =
        url.host in imageHosts || url.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in heavyExtensions

    private val headTag = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)

    /** Fetches the page like the WebView would, with [DRM_STUB] first in <head>. Null lets the WebView load it normally. */
    private fun pageWithDrmStub(url: String): WebResourceResponse? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SpotifySession.DESKTOP_USER_AGENT)
            .header("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        pageClient.newCall(request).execute().use { response ->
            if (response.code !in 200..299 || response.header("Content-Type").orEmpty().contains("text/html").not()) {
                return@use null
            }
            val html = response.body?.string() ?: return@use null
            val head = headTag.find(html) ?: return@use null
            val patched = html.substring(0, head.range.last + 1) + "<script>$DRM_STUB</script>" + html.substring(head.range.last + 1)
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(patched.toByteArray()))
        }
    }.getOrNull()

    private const val RESULTS_SCRIPT = """(function(){
        var out=[];
        document.querySelectorAll('a[href*="/track/"]').forEach(function(a){
            var m=a.getAttribute('href').match(/\/track\/([A-Za-z0-9]{22})/);
            if(!m||out.length>=10) return;
            var row=a.closest('[data-testid="tracklist-row"]')||a.closest('[role="row"]')||a.parentElement.parentElement;
            var artists=Array.from(row.querySelectorAll('a[href*="/artist/"]')).map(function(x){return x.textContent});
            out.push({id:m[1],name:a.textContent,artists:artists});
        });
        return out.length?JSON.stringify(out):'';
    })()"""

    private const val DIAGNOSTIC_SCRIPT = """(function(){
        var a=document.querySelectorAll('a[href*="/track/"]').length;
        return location.href+' | title='+document.title+' | trackLinks='+a+' | text='+
            (document.body?document.body.innerText.replace(/\s+/g,' ').substring(0,160):'none');
    })()"""

    /**
     * Spotify's search API refuses web-player tokens (HTTP 429), so the track is found by opening the
     * web player's own search page in a hidden WebView and reading the result links from the page.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun searchTrack(context: Context, title: String, artist: String): String? {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        synchronized(idCache) { if (idCache.containsKey(key)) return idCache[key] }
        val saved = context.getSharedPreferences("spotify_canvas_ids", Context.MODE_PRIVATE)
        saved.getString(key, null)?.let { id ->
            synchronized(idCache) { idCache[key] = id }
            return id
        }
        val query = URLEncoder.encode("$title $artist", "UTF-8").replace("+", "%20")
        val json = searchMutex.withLock {
            withTimeoutOrNull(25_000) {
                withContext(Dispatchers.Main) {
                    val webView = WebView(context.applicationContext)
                    try {
                        SpotifySession.configure(webView)
                        webView.settings.userAgentString = SpotifySession.DESKTOP_USER_AGENT
                        webView.settings.blockNetworkImage = true
                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                when {
                                    request.isForMainFrame && request.method == "GET" && request.url.host == "open.spotify.com" ->
                                        pageWithDrmStub(request.url.toString())
                                    // Only the result links are read, so fonts, images and media are skipped.
                                    isHeavyResource(request.url) ->
                                        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                    else -> null
                                }
                        }
                        webView.layout(0, 0, 1280, 2400)
                        webView.loadUrl("https://open.spotify.com/search/$query/tracks")
                        repeat(100) {
                            delay(250)
                            val raw = suspendCancellableCoroutine<String?> { cont ->
                                webView.evaluateJavascript(RESULTS_SCRIPT) { cont.resume(it) }
                            }
                            val text = raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                            if (!text.isNullOrEmpty()) return@withContext text
                        }
                        val diagnostic = suspendCancellableCoroutine<String?> { cont ->
                            webView.evaluateJavascript(DIAGNOSTIC_SCRIPT) { cont.resume(it) }
                        }
                        Timber.tag(TAG).w("Search page empty: %s", diagnostic)
                        null
                    } finally {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }
        if (json == null) {
            Timber.tag(TAG).w("Spotify search page returned no results for: %s - %s", artist, title)
            return null
        }
        val results = JSONArray(json)
        var found: String? = null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val candidate = CanvasArtwork(
                name = item.optString("name"),
                artist = item.optJSONArray("artists")?.optString(0),
            )
            if (candidate.matchesSong(title, artist)) {
                found = item.optString("id").takeIf { it.isNotEmpty() }
                break
            }
        }
        Timber.tag(TAG).d("Search page: %d results, match=%s", results.length(), found)
        synchronized(idCache) { idCache[key] = found }
        // Only hits are saved to disk; misses stay in memory so a song is retried after a restart.
        if (found != null) saved.edit().putString(key, found).apply()
        return found
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
        val bytes = client.newCall(request).execute().use {
            Timber.tag(TAG).d("Canvas HTTP %s", it.code)
            if (it.isSuccessful) it.body?.bytes() else null
        } ?: return null
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
