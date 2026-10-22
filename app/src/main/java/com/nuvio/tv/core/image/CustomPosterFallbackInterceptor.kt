package com.nuvio.tv.core.image

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/**
 * Coil interceptor that detects failed custom poster loads and retries with the
 * original poster URL set via [posterFallbackUrl].
 *
 * This runs globally in the ImageLoader pipeline, so every composable that loads
 * posters (ModernHomeRows, ContentCard, GridContentCard, ContinueWatching, etc.)
 * gets automatic fallback without per-composable code.
 *
 * The URL travels in request extras, not memoryCacheKeyExtras, so it does not
 * change the memory cache key and prefetched entries still match the card.
 */
class CustomPosterFallbackInterceptor : Interceptor {

    companion object {
        val FALLBACK_URL = Extras.Key<String?>(default = null)
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()

        if (result is ErrorResult) {
            val fallbackUrl = chain.request.extras[FALLBACK_URL]
            if (!fallbackUrl.isNullOrBlank()) {
                val fallbackRequest = chain.request.newBuilder()
                    .data(fallbackUrl)
                    .apply { extras[FALLBACK_URL] = null }
                    .build()
                return chain.withRequest(fallbackRequest).proceed()
            }
        }

        return result
    }
}

/** Load [url] if this request's image fails. */
fun ImageRequest.Builder.posterFallbackUrl(url: String?): ImageRequest.Builder = apply {
    extras[CustomPosterFallbackInterceptor.FALLBACK_URL] = url
}
