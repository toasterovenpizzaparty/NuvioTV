package com.nuvio.tv.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImagePainter
import coil3.network.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

internal const val MAX_IMAGE_LOAD_RETRIES = 2
private const val IMAGE_RETRY_BASE_DELAY_MS = 1_500L

/**
 * Retries a failed card image a few times. Without this, one timeout on a slow
 * TV connection leaves the card blank until it leaves composition.
 *
 * Wrap the AsyncImage in `key(attempt) { ... }` and call [onError] from its
 * onError. AsyncImage compares requests by data and cache keys, so rebuilding
 * an identical request would not reload; a new composition does, and the
 * memory cache key stays the same so prefetched entries still match.
 */
@Stable
class ImageRetryState internal constructor(private val scope: CoroutineScope) {
    var attempt by mutableIntStateOf(0)
        private set

    private var pending: Job? = null

    fun onError(state: AsyncImagePainter.State.Error) {
        if (!isRetryableImageError(state.result.throwable)) return
        if (attempt >= MAX_IMAGE_LOAD_RETRIES || pending?.isActive == true) return
        val next = attempt + 1
        pending = scope.launch {
            delay(IMAGE_RETRY_BASE_DELAY_MS * next)
            attempt = next
        }
    }
}

/** Retry state that resets whenever [key] (usually the image URL) changes. */
@Composable
fun rememberImageRetryState(key: Any?): ImageRetryState {
    val scope = rememberCoroutineScope()
    return remember(key) { ImageRetryState(scope) }
}

/** Network failures and 5xx/429 can succeed later; 4xx and decode errors will not. */
internal fun isRetryableImageError(throwable: Throwable): Boolean = when (throwable) {
    is HttpException -> throwable.response.code.let { it == 408 || it == 429 || it >= 500 }
    is IOException -> true
    else -> false
}
