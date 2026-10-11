/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.ArtistGenresKey
import kotlinx.coroutines.flow.first

/**
 * Genre taste without storing genres in the database. An artist's genres are read from the
 * description on their YouTube page, cached in DataStore, and then weighted by how much the
 * user listens to that artist (see [TasteProfile]).
 */
object GenreTaste {
    /** Genre name to the words that identify it in an artist description. Matched as whole words, case-insensitive. */
    private val genreKeywords: Map<String, List<String>> =
        linkedMapOf(
            "Hip-Hop" to listOf("rapper", "hip hop", "hip-hop", "rap", "trap"),
            "Pop" to listOf("pop"),
            "Rock" to listOf("rock"),
            "Metal" to listOf("metal"),
            "Electronic" to listOf("electronic", "edm", "dj", "techno", "dubstep"),
            "R&B" to listOf("r&b", "rnb", "rhythm and blues", "soul"),
            "Punjabi" to listOf("punjabi"),
            "Bollywood" to listOf("bollywood", "playback singer", "hindi"),
            "K-Pop" to listOf("k-pop", "kpop", "korean pop"),
            "Latin" to listOf("latin", "reggaeton"),
            "Country" to listOf("country music"),
            "Jazz" to listOf("jazz"),
            "Classical" to listOf("classical", "orchestra"),
            "Indie" to listOf("indie", "alternative"),
            "Lo-fi" to listOf("lo-fi", "lofi"),
            "Phonk" to listOf("phonk"),
            "Reggae" to listOf("reggae", "dancehall"),
            "Afrobeats" to listOf("afrobeats", "afropop"),
        )

    private val genrePatterns: Map<String, Regex> =
        genreKeywords.mapValues { (_, words) ->
            Regex("(?<![\\p{L}\\p{N}])(" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }

    private const val MAX_CACHED_ARTISTS = 400
    private const val MAX_NEW_LOOKUPS_PER_RUN = 6

    /** Genres named in an artist description, in vocabulary order. */
    internal fun tagsFromDescription(description: String?): List<String> {
        if (description.isNullOrBlank()) return emptyList()
        return genrePatterns.filterValues { it.containsMatchIn(description) }.keys.toList()
    }

    // One line per artist: "artistId=Genre,Genre". Genres never contain '=' or newlines.
    private fun parse(raw: String?): LinkedHashMap<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>()
        raw.orEmpty().lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) map[line.substring(0, i)] = line.substring(i + 1).split(',').filter { it.isNotBlank() }
        }
        return map
    }

    private fun serialize(map: Map<String, List<String>>): String =
        map.entries.toList().takeLast(MAX_CACHED_ARTISTS).joinToString("\n") { (id, tags) -> id + "=" + tags.joinToString(",") }

    /**
     * Genres for each of [artistIds], using the cache and looking up a few artists YouTube hasn't been asked about yet.
     * An artist that was looked up and matched nothing is cached with no genres, so it isn't asked about again.
     */
    suspend fun genresFor(
        context: Context,
        artistIds: List<String>,
    ): Map<String, List<String>> {
        val cache = parse(context.dataStore.data.first()[ArtistGenresKey])
        var changed = false
        artistIds
            .filter { it !in cache }
            .take(MAX_NEW_LOOKUPS_PER_RUN)
            .forEach { id ->
                val page = YouTube.artist(id).getOrNull() ?: return@forEach
                cache[id] = tagsFromDescription(page.description)
                changed = true
            }
        if (changed) {
            context.safeDataStoreEdit { it[ArtistGenresKey] = serialize(cache) }
        }
        return cache.filterKeys { it in artistIds }
    }

    /** Genre to taste score: the sum of the scores of the artists tagged with it. Highest first. */
    fun genreScores(
        artistScores: Map<String, Double>,
        artistGenres: Map<String, List<String>>,
    ): List<Pair<String, Double>> {
        val totals = HashMap<String, Double>()
        artistGenres.forEach { (artistId, genres) ->
            val score = artistScores[artistId] ?: return@forEach
            genres.forEach { genre -> totals.merge(genre, score, Double::plus) }
        }
        return totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
