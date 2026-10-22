package com.nuvio.tv.core.recommendations

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchHistoryRecommender"
private const val SEED_COUNT = 5
private const val HISTORY_EXCLUSION_COUNT = 30
// Below the 15 items at which Classic home adds a "See all" card, which has no
// catalog to open for this row.
private const val MAX_RESULTS = 14
private const val LOOKUP_CONCURRENCY = 4
private const val RESULT_TTL_MS = 6L * 60 * 60 * 1000

/** A recently watched title the row is built from. */
internal data class RecommendationSeed(val tmdbId: String, val contentType: ContentType)

/**
 * Builds a "Recommended for you" row from the titles watched most recently,
 * using TMDB recommendations for each. Titles suggested by several watched
 * titles rank higher, and anything already in the watch history is left out.
 */
@Singleton
class WatchHistoryRecommender @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val mutex = Mutex()
    private var cachedSignature: String? = null
    private var cachedAt = 0L
    private var cachedItems: List<MetaPreview> = emptyList()

    suspend fun recommendations(): List<MetaPreview> {
        val settings = tmdbSettingsDataStore.settings.first()
        if (!settings.enabled || !settings.useMoreLikeThis) return emptyList()

        val history = distinctTitles(watchProgressRepository.allProgress.first())
        val seedEntries = recommendationSeeds(history)
        if (seedEntries.isEmpty()) return emptyList()

        val signature = seedEntries.joinToString(",") { it.contentId } + "|" + settings.language
        return mutex.withLock {
            val now = System.currentTimeMillis()
            if (signature == cachedSignature && now - cachedAt < RESULT_TTL_MS) {
                return@withLock cachedItems
            }
            val items = try {
                build(seedEntries, history.take(HISTORY_EXCLUSION_COUNT), settings.language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build recommendations: ${e.message}")
                return@withLock cachedItems
            }
            cachedSignature = signature
            cachedAt = now
            cachedItems = items
            items
        }
    }

    private suspend fun build(
        seedEntries: List<WatchProgress>,
        historyEntries: List<WatchProgress>,
        language: String
    ): List<MetaPreview> = coroutineScope {
        val lookups = Semaphore(LOOKUP_CONCURRENCY)
        val resolved = (seedEntries + historyEntries).distinctBy { it.contentId }.map { entry ->
            async { entry.contentId to lookups.withPermit { resolveSeed(entry) } }
        }.awaitAll().toMap()
        val seeds = seedEntries.mapNotNull { resolved[it.contentId] }
        val candidatesPerSeed = seeds.map { seed ->
            async {
                lookups.withPermit {
                    tmdbMetadataService.fetchRecommendationPosters(seed.tmdbId, seed.contentType, language)
                }
            }
        }.awaitAll()
        val excludedKeys = resolved.values.filterNotNull()
            .mapTo(HashSet()) { "${it.contentType.toApiString()}:tmdb:${it.tmdbId}" }
        rankRecommendations(candidatesPerSeed, excludedKeys, MAX_RESULTS)
    }

    private suspend fun resolveSeed(entry: WatchProgress): RecommendationSeed? {
        val contentType = ContentType.fromString(entry.contentType)
        val isTv = contentType == ContentType.SERIES || contentType == ContentType.TV
        val tmdbId = try {
            tmdbService.ensureTmdbId(entry.contentId, if (isTv) "series" else "movie")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return tmdbId?.let { RecommendationSeed(it, if (isTv) ContentType.SERIES else ContentType.MOVIE) }
    }
}

/** One entry per title, most recent first. Episodes of a series collapse into one. */
internal fun distinctTitles(progress: List<WatchProgress>): List<WatchProgress> =
    progress.sortedByDescending { it.lastWatched }.distinctBy { it.contentId }

/** The titles recommendations are built from. Changes to this list should rebuild the row. */
internal fun recommendationSeeds(history: List<WatchProgress>): List<WatchProgress> =
    distinctTitles(history).filter(::isMeaningfulWatch).take(SEED_COUNT)

/**
 * Any episode of a series counts. A movie counts once a quarter is watched, so
 * something opened and abandoned does not drive suggestions.
 */
internal fun isMeaningfulWatch(entry: WatchProgress): Boolean {
    val isSeries = entry.season != null || entry.episode != null ||
        entry.contentType.equals("series", ignoreCase = true) ||
        entry.contentType.equals("tv", ignoreCase = true)
    return isSeries || entry.progressPercentage >= 0.25f
}

/**
 * Merges each seed's ranked list. A seed's weight falls with recency and an
 * item's contribution falls with its rank, so titles suggested by several
 * recent watches come first. [excludedKeys] are "apiType:id", since a movie
 * and a series can share a TMDB id.
 */
internal fun rankRecommendations(
    candidatesPerSeed: List<List<MetaPreview>>,
    excludedKeys: Set<String>,
    maxResults: Int
): List<MetaPreview> {
    val scores = LinkedHashMap<String, Double>()
    val items = HashMap<String, MetaPreview>()
    candidatesPerSeed.forEachIndexed { seedIndex, candidates ->
        val seedWeight = 1.0 / (1 + seedIndex * 0.3)
        candidates.forEachIndexed { rank, item ->
            val key = "${item.apiType}:${item.id}"
            if (key in excludedKeys) return@forEachIndexed
            scores[key] = (scores[key] ?: 0.0) + seedWeight / (1 + rank * 0.15)
            items.putIfAbsent(key, item)
        }
    }
    return scores.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenByDescending { items.getValue(it.key).voteCount ?: 0 }
        )
        .take(maxResults)
        .map { items.getValue(it.key) }
}
