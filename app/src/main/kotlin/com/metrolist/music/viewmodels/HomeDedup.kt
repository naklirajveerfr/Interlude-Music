/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.GenreRecommendation
import com.metrolist.music.models.SimilarRecommendation

/** The home screen's sections, with every song, album or playlist shown in only one of them. */
data class HomeFeed(
    val quickPicks: List<Song>? = null,
    val dailyDiscover: List<DailyDiscoverItem>? = null,
    val keepListening: List<LocalItem>? = null,
    val forgottenFavorites: List<Song>? = null,
    val genreRecommendations: List<GenreRecommendation>? = null,
    val similarRecommendations: List<SimilarRecommendation>? = null,
    val homePage: HomePage? = null,
    val speedDialItems: List<YTItem> = emptyList(),
)

/**
 * Removes items that would appear in more than one home section. The on-screen order of the
 * sections can be randomized, so the section that keeps an item is decided by a fixed priority:
 * pinned speed dial items, quick picks, daily discover, keep listening, forgotten favorites,
 * genre and similar rows, the speed dial's automatic items, and finally YouTube's own home rows.
 * Sections left without items are dropped.
 */
internal fun dedupeHomeFeed(
    pinnedIds: Set<String>,
    quickPicks: List<Song>?,
    dailyDiscover: List<DailyDiscoverItem>?,
    keepListening: List<LocalItem>?,
    forgottenFavorites: List<Song>?,
    genreRecommendations: List<GenreRecommendation>?,
    similarRecommendations: List<SimilarRecommendation>?,
    speedDialPool: List<YTItem>,
    homePage: HomePage?,
    chipActive: Boolean,
): HomeFeed {
    // With a chip selected only the chip's own page is shown, so there is nothing to compare against.
    if (chipActive) {
        return HomeFeed(
            quickPicks = quickPicks,
            dailyDiscover = dailyDiscover,
            keepListening = keepListening,
            forgottenFavorites = forgottenFavorites,
            genreRecommendations = genreRecommendations,
            similarRecommendations = similarRecommendations,
            homePage = homePage,
            speedDialItems = speedDialPool.take(SPEED_DIAL_SIZE),
        )
    }

    val seen = pinnedIds.toMutableSet()

    val quick = quickPicks?.filter { seen.add(it.id) }
    val daily = dailyDiscover?.filter { seen.add(it.recommendation.id) }
    val keep = keepListening?.filter { seen.add(it.id) }
    val forgotten = forgottenFavorites?.filter { seen.add(it.id) }
    val genres =
        genreRecommendations?.mapNotNull { rec ->
            rec.copy(items = rec.items.filter { seen.add(it.id) }).takeIf { it.items.isNotEmpty() }
        }
    val similar =
        similarRecommendations?.mapNotNull { rec ->
            rec.copy(items = rec.items.filter { seen.add(it.id) }).takeIf { it.items.isNotEmpty() }
        }

    val speedDial =
        speedDialPool
            .filter { it.id in pinnedIds || it.id !in seen }
            .distinctBy { it.id }
            .take(SPEED_DIAL_SIZE)
    seen.addAll(speedDial.map { it.id })

    val home =
        homePage?.let { page ->
            page.copy(
                sections =
                    page.sections.mapNotNull { section ->
                        section.copy(items = section.items.filter { seen.add(it.id) })
                            .takeIf { it.items.isNotEmpty() }
                    },
            )
        }

    return HomeFeed(
        quickPicks = quick,
        dailyDiscover = daily,
        keepListening = keep,
        forgottenFavorites = forgotten,
        genreRecommendations = genres,
        similarRecommendations = similar,
        homePage = home,
        speedDialItems = speedDial,
    )
}
