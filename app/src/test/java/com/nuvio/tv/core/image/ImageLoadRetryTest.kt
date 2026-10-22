package com.nuvio.tv.core.image

import coil3.network.HttpException
import coil3.network.NetworkResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ImageLoadRetryTest {

    @Test
    fun `network failures are retried`() {
        assertTrue(isRetryableImageError(SocketTimeoutException("timeout")))
        assertTrue(isRetryableImageError(IOException("connection reset")))
    }

    @Test
    fun `server errors and rate limits are retried`() {
        assertTrue(isRetryableImageError(HttpException(NetworkResponse(code = 503))))
        assertTrue(isRetryableImageError(HttpException(NetworkResponse(code = 429))))
    }

    @Test
    fun `missing images and decode failures are not retried`() {
        assertFalse(isRetryableImageError(HttpException(NetworkResponse(code = 404))))
        assertFalse(isRetryableImageError(IllegalStateException("decode failed")))
    }
}
