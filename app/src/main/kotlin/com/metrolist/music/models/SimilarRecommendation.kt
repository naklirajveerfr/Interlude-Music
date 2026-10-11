/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import com.metrolist.innertube.models.YTItem
import com.metrolist.music.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)

/** Songs from YouTube for a genre the user listens to a lot, e.g. "Hip-Hop". */
data class GenreRecommendation(
    val genre: String,
    val items: List<YTItem>,
)
