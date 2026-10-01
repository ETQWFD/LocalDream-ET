package io.github.xororz.localdream.utils

import okhttp3.OkHttpClient

// Single OkHttp instance for the whole app. Per-use-case clients must be
// derived via newBuilder() so they share this instance's dispatcher and
// connection pool instead of each spawning their own thread pools.
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
