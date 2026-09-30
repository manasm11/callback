package com.shopcallback.tracker.sync

/**
 * Tidies a server address typed in Settings: trims it and, if it has no scheme (e.g.
 * "shop-pc:8787"), adds "http://". Blank stays empty, meaning sync is off.
 */
fun normalizeServerUrl(url: String): String {
    val trimmed = url.trim()
    return if (trimmed.isEmpty() || "://" in trimmed) trimmed else "http://$trimmed"
}
