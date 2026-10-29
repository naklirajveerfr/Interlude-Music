package com.metrolist.music.canvas

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Warms the canvas caches for a song that is about to play. Each song is only prefetched once per run. */
object CanvasPrefetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requested = HashSet<String>()

    fun prefetch(context: Context, title: String, artist: String, source: CanvasSource, spotify: Boolean) {
        val key = "${source.name}|$spotify|${title.lowercase()}|${artist.lowercase()}"
        if (!synchronized(requested) { requested.add(key) }) return
        val appContext = context.applicationContext
        scope.launch {
            listOfNotNull(
                async { CanvasRepository.resolve(title, artist, source) },
                if (spotify) async { SpotifyCanvas.resolve(appContext, title, artist) } else null,
            ).awaitAll()
        }
    }
}
