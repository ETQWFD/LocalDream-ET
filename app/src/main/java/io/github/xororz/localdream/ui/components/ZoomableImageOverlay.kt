package io.github.xororz.localdream.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun OverlayIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
fun ZoomableImageOverlay(
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    showScaleIndicator: Boolean = false,
    topEndContent: @Composable RowScope.() -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    scale = (scale * zoom).coerceIn(0.5f, 5f)

                    val centerX = size.width / 2f
                    val centerY = size.height / 2f

                    val focusX = (centroid.x - centerX - offsetX) / oldScale
                    val focusY = (centroid.y - centerY - offsetY) / oldScale

                    offsetX += focusX * oldScale - focusX * scale
                    offsetY += focusY * oldScale - focusY * scale

                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onTap = { offset ->
                    val bmp = bitmap
                    if (bmp == null) {
                        onDismiss()
                        return@detectTapGestures
                    }
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    // The image is laid out as a centered square of this side,
                    // then ContentScale.Fit letterboxes the bitmap inside it by
                    // its aspect ratio, then graphicsLayer scales about the
                    // center and translates. Reproduce that to get the actual
                    // visible image rect so taps in the letterbox (a non-square
                    // image) dismiss too, not only taps outside the square.
                    val square = minOf(size.width, size.height).toFloat()
                    val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                    val baseWidth = if (aspect >= 1f) square else square * aspect
                    val baseHeight = if (aspect >= 1f) square / aspect else square
                    val scaledWidth = baseWidth * scale
                    val scaledHeight = baseHeight * scale

                    val left = centerX + offsetX - scaledWidth / 2f
                    val right = centerX + offsetX + scaledWidth / 2f
                    val top = centerY + offsetY - scaledHeight / 2f
                    val bottom = centerY + offsetY + scaledHeight / 2f

                    if (offset.x < left ||
                        offset.x > right ||
                        offset.y < top ||
                        offset.y > bottom
                    ) {
                        onDismiss()
                    }
                })
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "preview image",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 60.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = topEndContent,
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        ) {
            OverlayIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "reset zoom",
                onClick = {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                },
            )
        }

        if (showScaleIndicator) {
            Text(
                text = "${(scale * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
