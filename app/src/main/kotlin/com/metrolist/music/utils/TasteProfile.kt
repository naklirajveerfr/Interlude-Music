/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.random.Random

/**
 * On-device taste profile: scores artists from listening history, skips, likes and follows.
 * Everything is computed locally from the existing database.
 */
object TasteProfile {
    private const val HISTORY_DAYS = 90L
    private const val HALF_LIFE_DAYS = 21.0
    private const val FOLLOW_BONUS = 6.0
    private const val LIKE_BONUS = 1.5
    private const val SKIP_WEIGHT = -0.5
    private const val SKIP_MAX_MS = 20_000L
    private const val SKIP_MAX_FRACTION = 0.25
    private const val COMPLETE_FRACTION = 0.8
    private const val UNKNOWN_DURATION_MS = 180_000L

    /** Artist id to score. Only artists with a positive score are returned; [excludedArtists] never are. */
    suspend fun artistScores(
        database: MusicDatabase,
        excludedArtists: Set<String> = emptySet(),
    ): Map<String, Double> {
        val now = LocalDateTime.now()
        val scores = HashMap<String, Double>()

        database.artistListenEvents(now.minusDays(HISTORY_DAYS)).forEach { event ->
            val durationMs = if (event.duration > 0) event.duration * 1000L else UNKNOWN_DURATION_MS
            val fraction = (event.playTime.toDouble() / durationMs).coerceAtMost(1.0)
            val weight =
                when {
                    fraction >= COMPLETE_FRACTION -> 1.0
                    event.playTime < SKIP_MAX_MS && fraction < SKIP_MAX_FRACTION -> SKIP_WEIGHT
                    else -> fraction
                }
            val ageDays = Duration.between(event.timestamp, now).toHours().coerceAtLeast(0) / 24.0
            val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            scores.merge(event.artistId, weight * decay, Double::plus)
        }

        database.likedSongArtistCounts().forEach { like ->
            scores.merge(like.artistId, like.likedCount * LIKE_BONUS, Double::plus)
        }

        database.bookmarkedArtistEntitiesByNameAsc().forEach { artist ->
            scores.merge(artist.id, FOLLOW_BONUS, Double::plus)
        }

        return scores.filter { (artistId, score) -> score > 0.0 && artistId !in excludedArtists }
    }

    /** Taste score of a recommendation seed (an artist, song or album), used to order home sections. */
    fun seedScore(
        seed: LocalItem,
        artistScores: Map<String, Double>,
    ): Double =
        when (seed) {
            is Artist -> artistScores[seed.id] ?: 0.0
            is Song -> seed.artists.maxOfOrNull { artistScores[it.id] ?: 0.0 } ?: 0.0
            is Album -> seed.artists.maxOfOrNull { artistScores[it.id] ?: 0.0 } ?: 0.0
            else -> 0.0
        }

    /** Random order where items with a higher weight tend to come first. Non-positive weights come last. */
    fun <T> weightedOrder(
        items: List<T>,
        weight: (T) -> Double,
    ): List<T> =
        items.sortedByDescending {
            val w = weight(it)
            if (w > 0.0) Random.nextDouble().pow(1.0 / w) else -1.0
        }

    /** Picks up to [count] items at random, favouring higher weights. Items with non-positive weight are never picked. */
    fun <T> pickWeighted(
        items: List<T>,
        count: Int,
        weight: (T) -> Double,
    ): List<T> = weightedOrder(items, weight).filter { weight(it) > 0.0 }.take(count)
}
