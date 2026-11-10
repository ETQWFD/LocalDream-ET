package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * Image with a mirror fallback chain.
 *
 * GitHub-hosted media is read through jsDelivr first (reachable from mainland
 * China) and raw.githubusercontent as the canonical fallback. When the first
 * URL 404s (jsDelivr lags a few minutes behind a fresh upload) we advance to the
 * next candidate instead of showing a black tile, which is what made the
 * gallery look broken.
 */
@Composable
fun FallbackImage(
    urls: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var index by remember(urls) { mutableIntStateOf(0) }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        AsyncImage(
            model = urls.getOrNull(index),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onError = {
                when {
                    index < urls.lastIndex -> index++
                    // All mirrors failed: keep the neutral placeholder instead of
                    // retrying forever (Coil would re-trigger onError each time).
                    else -> index = urls.lastIndex.coerceAtLeast(0)
                }
            },
        )
    }
}
