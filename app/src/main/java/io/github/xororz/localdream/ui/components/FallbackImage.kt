package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableStateOf
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalContext

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
    zoomable: Boolean = false,
) {
    var index by remember(urls) { mutableIntStateOf(0) }
    var scale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var offset by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        // 2026-11：按显示尺寸解码（360px 见方），而不是把 1024/2048 原图整张
        // 解进内存。原图一解码就是几十 MB，三列网格滑两屏就把 Coil 的内存缓存
        // 挤爆，于是“滑回去又重新加载”。尺寸固定后内存缓存命中率稳定，滑动不再重载。
        val ctx = LocalContext.current
        val req = remember(index, urls.firstOrNull()) {
            ImageRequest.Builder(ctx)
                .data(urls.getOrNull(index))
                .size(Size(360, 360))
                // 2026-11：缩略图用 RGB_565（每像素 2 字节）解码，解码时间与内存都再降一半，
                // 社区滑动/首屏延迟明显下降，肉眼看不出差别。
                .bitmapConfig(Bitmap.Config.RGB_565)
                .crossfade(true)  // 载入时 200ms 淡入，替代硬切，滑动观感更顺
                .build()
        }
        // 2026-11：图片解码完成前透明，成功后 240ms 淡入，滑动时不再“啪”地跳出整块图。
        var imgReady by remember(urls.firstOrNull(), index) { mutableStateOf(false) }
        val imgAlpha by animateFloatAsState(
            targetValue = if (imgReady) 1f else 0f,
            animationSpec = tween(240),
            label = "fallbackImageAlpha",
        )
        AsyncImage(
            model = req,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onSuccess = { imgReady = true },
            modifier = Modifier.fillMaxSize().alpha(imgAlpha).then(
                if (!zoomable) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
            ),
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
