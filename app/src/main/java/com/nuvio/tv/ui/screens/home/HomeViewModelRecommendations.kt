@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.recommendations.recommendationSeeds
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.WATCH_HISTORY_RECOMMENDATIONS_ADDON_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal const val WATCH_HISTORY_RECOMMENDATIONS_CATALOG_ID = "recommended_for_you"
private const val RECOMMENDATIONS_DEBOUNCE_MS = 2_000L

/**
 * Rebuilds the "Recommended for you" row when the recently watched titles or
 * the TMDB settings change. Progress ticks during playback do not change the
 * seed list, so they do not trigger a rebuild.
 */
internal fun HomeViewModel.observeWatchHistoryRecommendationsPipeline() {
    viewModelScope.launch {
        val seedIds = watchProgressRepository.allProgress
            .map { progress -> recommendationSeeds(progress).map { it.contentId } }
            .distinctUntilChanged()
        val tmdbGate = tmdbSettingsDataStore.settings
            .map { Triple(it.enabled, it.useMoreLikeThis, it.language) }
            .distinctUntilChanged()
        combine(seedIds, tmdbGate) { seeds, gate -> seeds to gate }
            .debounce(RECOMMENDATIONS_DEBOUNCE_MS)
            .collectLatest {
                val items = try {
                    watchHistoryRecommender.recommendations()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(HomeViewModel.TAG, "Recommendations failed: ${e.message}")
                    return@collectLatest
                }
                val row = items.takeIf { it.isNotEmpty() }?.let {
                    CatalogRow(
                        addonId = WATCH_HISTORY_RECOMMENDATIONS_ADDON_ID,
                        addonName = "",
                        addonBaseUrl = "",
                        catalogId = WATCH_HISTORY_RECOMMENDATIONS_CATALOG_ID,
                        catalogName = appContext.getString(R.string.home_row_recommended_for_you),
                        type = ContentType.MOVIE,
                        items = it,
                        hasMore = false
                    )
                }
                if (row != watchHistoryRecommendationRow) {
                    watchHistoryRecommendationRow = row
                    scheduleUpdateCatalogRows()
                }
            }
    }
}
