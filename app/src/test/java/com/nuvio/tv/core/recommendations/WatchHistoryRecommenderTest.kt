package com.nuvio.tv.core.recommendations

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryRecommenderTest {

    @Test
    fun `titles suggested by several watched titles rank first`() {
        val ranked = rankRecommendations(
            candidatesPerSeed = listOf(
                listOf(preview("a"), preview("b"), preview("shared")),
                listOf(preview("shared"), preview("c"))
            ),
            excludedKeys = emptySet(),
            maxResults = 10
        )
        assertEquals("tmdb:shared", ranked.first().id)
        assertEquals(4, ranked.size)
    }

    @Test
    fun `already watched titles are left out and the result is capped`() {
        val ranked = rankRecommendations(
            candidatesPerSeed = listOf(listOf(preview("a"), preview("b"), preview("c"))),
            excludedKeys = setOf("movie:tmdb:a"),
            maxResults = 1
        )
        assertEquals(listOf("tmdb:b"), ranked.map { it.id })
    }

    @Test
    fun `a barely started movie is not a seed but any series episode is`() {
        assertFalse(isMeaningfulWatch(progress("tt1", "movie", position = 10, duration = 100)))
        assertTrue(isMeaningfulWatch(progress("tt2", "movie", position = 50, duration = 100)))
        assertTrue(isMeaningfulWatch(progress("tt3", "series", position = 1, duration = 100, season = 1)))
    }

    @Test
    fun `seeds are distinct titles, most recent first`() {
        val seeds = recommendationSeeds(
            listOf(
                progress("tt1", "series", lastWatched = 1, season = 1),
                progress("tt2", "movie", position = 90, duration = 100, lastWatched = 3),
                progress("tt1", "series", lastWatched = 2, season = 1)
            )
        )
        assertEquals(listOf("tt2", "tt1"), seeds.map { it.contentId })
    }

    private fun preview(id: String) = MetaPreview(
        id = "tmdb:$id",
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun progress(
        contentId: String,
        contentType: String,
        position: Long = 0,
        duration: Long = 0,
        lastWatched: Long = 0,
        season: Int? = null
    ) = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = season,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched
    )
}
