package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDedupTest {
    @Test
    fun `quick picks keep a song that speed dial and home rows also contain`() {
        val result =
            dedupe(
                quickPicks = listOf(localSong("a")),
                speedDialPool = listOf(item("a"), item("b")),
                homePage = homePage(listOf(item("a"), item("c"))),
            )

        assertEquals(listOf("a"), result.quickPicks?.map { it.id })
        assertEquals(listOf("b"), result.speedDialItems.map { it.id })
        assertEquals(listOf(listOf("c")), result.homePage?.sections?.map { s -> s.items.map { it.id } })
    }

    @Test
    fun `pinned songs stay in speed dial and leave the other sections`() {
        val result =
            dedupe(
                pinnedIds = setOf("p"),
                quickPicks = listOf(localSong("p"), localSong("q")),
                speedDialPool = listOf(item("p"), item("x")),
            )

        assertEquals(listOf("q"), result.quickPicks?.map { it.id })
        assertEquals(listOf("p", "x"), result.speedDialItems.map { it.id })
    }

    @Test
    fun `daily discover drops a recommendation already in quick picks`() {
        val result =
            dedupe(
                quickPicks = listOf(localSong("a")),
                dailyDiscover =
                    listOf(
                        DailyDiscoverItem(localSong("seed1"), item("a"), null),
                        DailyDiscoverItem(localSong("seed2"), item("b"), null),
                    ),
            )

        assertEquals(listOf("b"), result.dailyDiscover?.map { it.recommendation.id })
    }

    @Test
    fun `speed dial is refilled from the remaining candidates`() {
        val pool = (1..40).map { item("s$it") }
        val claimed = (1..10).map { localSong("s$it") }

        val result = dedupe(quickPicks = claimed, speedDialPool = pool)

        assertEquals(SPEED_DIAL_SIZE, result.speedDialItems.size)
        assertTrue(result.speedDialItems.none { it.id in claimed.map { song -> song.id } })
        assertEquals("s11", result.speedDialItems.first().id)
    }

    @Test
    fun `home rows left without items are dropped`() {
        val result =
            dedupe(
                quickPicks = listOf(localSong("a")),
                homePage = homePage(listOf(item("a")), listOf(item("c"))),
            )

        assertEquals(listOf(listOf("c")), result.homePage?.sections?.map { s -> s.items.map { it.id } })
    }

    @Test
    fun `a selected chip leaves everything untouched`() {
        val page = homePage(listOf(item("a"), item("b")))

        val result =
            dedupe(
                quickPicks = listOf(localSong("a")),
                speedDialPool = listOf(item("a")),
                homePage = page,
                chipActive = true,
            )

        assertEquals(page, result.homePage)
        assertEquals(listOf("a"), result.speedDialItems.map { it.id })
    }

    private fun dedupe(
        pinnedIds: Set<String> = emptySet(),
        quickPicks: List<Song>? = null,
        dailyDiscover: List<DailyDiscoverItem>? = null,
        speedDialPool: List<YTItem> = emptyList(),
        homePage: HomePage? = null,
        chipActive: Boolean = false,
    ) = dedupeHomeFeed(
        pinnedIds = pinnedIds,
        quickPicks = quickPicks,
        dailyDiscover = dailyDiscover,
        keepListening = null,
        forgottenFavorites = null,
        genreRecommendations = null,
        similarRecommendations = null,
        speedDialPool = speedDialPool,
        homePage = homePage,
        chipActive = chipActive,
    )

    private fun homePage(vararg rows: List<YTItem>) =
        HomePage(
            chips = null,
            sections =
                rows.mapIndexed { index, items ->
                    HomePage.Section(
                        title = "row$index",
                        label = null,
                        thumbnail = null,
                        endpoint = null,
                        items = items,
                    )
                },
        )

    private fun localSong(id: String) = Song(SongEntity(id = id, title = id), emptyList())

    private fun item(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )
}
