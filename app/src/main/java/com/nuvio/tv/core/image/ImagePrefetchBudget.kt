package com.nuvio.tv.core.image

import android.app.ActivityManager
import android.content.Context

private const val LOW_RAM_THRESHOLD_MB = 2048L
private const val ROW_PREFETCH_ITEMS = 6
private const val LOW_RAM_ROW_PREFETCH_ITEMS = 3

/**
 * How many posters of the next home row to load ahead of scrolling. Enough to
 * cover the cards on screen, fewer on low-RAM boxes so prefetching does not
 * push visible posters out of the memory cache.
 */
fun rowImagePrefetchCount(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return LOW_RAM_ROW_PREFETCH_ITEMS
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
    return if (activityManager.isLowRamDevice || totalRamMb <= LOW_RAM_THRESHOLD_MB) {
        LOW_RAM_ROW_PREFETCH_ITEMS
    } else {
        ROW_PREFETCH_ITEMS
    }
}
