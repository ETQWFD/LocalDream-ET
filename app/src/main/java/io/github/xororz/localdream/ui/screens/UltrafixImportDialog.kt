package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.xororz.localdream.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The long side of the working bitmap (and of the confirmed output) is capped
// here: it bounds decode memory and the base64 upload size for the tiled
// repair.
internal const val ULTRAFIX_IMPORT_MAX_SIDE = 4096

// Images whose long side is at or below this are upscale-eligible on the
// result page; the slider's lower bound lets any import shrink into that
// range, so no image is a dead end (upscale first, then UltraFix).
private const val UPSCALE_ELIGIBLE_LONG_SIDE = 1024

/**
 * Import-and-resize dialog for bringing a local image in for UltraFix.
 *
 * The user picks an image (huge sources are decoded pre-scaled to the cap)
 * and may adjust a scale slider. There is no minimum size: an import that
 * doesn't yet satisfy the UltraFix window (long side above 1024, short side
 * >= [tileSize]) simply lands on the result page upscale-eligible, and the
 * upscaled copy becomes UltraFix-able. The slider spans from
 * "shrink to upscale-eligible" up to the hard cap; output dimensions are
 * floor-aligned to multiples of 8 for the VAE.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun UltrafixImportDialog(
    tileSize: Int,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgImageLoadFailed = stringResource(R.string.image_load_failed)

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }

    fun decodeSource(uri: Uri) {
        working = true
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    // EXIF-aware decode; oversized sources are scaled down to
                    // the cap during decode (memory stays bounded by the
                    // target size, not the source size).
                    ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.contentResolver, uri),
                    ) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val w = info.size.width
                        val h = info.size.height
                        val longSide = maxOf(w, h)
                        if (longSide > ULTRAFIX_IMPORT_MAX_SIDE) {
                            val s = ULTRAFIX_IMPORT_MAX_SIDE.toFloat() / longSide
                            decoder.setTargetSize(
                                (w * s).roundToInt().coerceAtLeast(1),
                                (h * s).roundToInt().coerceAtLeast(1),
                            )
                        }
                    }
                }.getOrNull()
            }
            working = false
            if (decoded == null) {
                Toast.makeText(context, msgImageLoadFailed, Toast.LENGTH_SHORT).show()
            } else {
                sourceBitmap = decoded
                scale = 1f
            }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        PickVisualMedia(),
    ) { uri ->
        uri?.let { decodeSource(it) }
    }

    fun launchPicker() {
        pickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }

    // Scale window for the current source: down to the upscale-eligible range
    // (never a pointless enlargement of an already-small image), up to the
    // hard cap. Always non-empty since the decode pre-caps the long side.
    val source = sourceBitmap
    val longSide = source?.let { maxOf(it.width, it.height) } ?: 0
    val maxScale = if (longSide > 0) ULTRAFIX_IMPORT_MAX_SIDE.toFloat() / longSide else 1f
    val minScale = if (longSide > 0) {
        (UPSCALE_ELIGIBLE_LONG_SIDE.toFloat() / longSide).coerceAtMost(1f)
    } else {
        1f
    }
    if (source != null && scale !in minScale..maxScale) {
        scale = scale.coerceIn(minScale, maxScale)
    }
    // Floor-align both dimensions to multiples of 8; minScale guarantees the
    // aligned values still satisfy the window (its bounds are 8-aligned, and
    // rounding - not truncating - the product keeps exact-bound scales from
    // slipping one pixel below it).
    fun aligned(value: Int): Int = (value.coerceAtLeast(8) / 8) * 8
    val outWidth = source?.let { aligned((it.width * scale).roundToInt()) } ?: 0
    val outHeight = source?.let { aligned((it.height * scale).roundToInt()) } ?: 0

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.ultrafix_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(
                        R.string.ultrafix_import_requirement,
                        ULTRAFIX_IMPORT_MAX_SIDE,
                        tileSize,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (source == null) {
                    if (working) {
                        LoadingIndicator()
                    } else {
                        Button(onClick = { launchPicker() }) {
                            Text(stringResource(R.string.ultrafix_import_action))
                        }
                    }
                } else {
                    Image(
                        bitmap = source.asImageBitmap(),
                        contentDescription = "import preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        stringResource(R.string.ultrafix_import_output, outWidth, outHeight),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // A degenerate window (single valid scale) needs no
                    // slider; the output is already fixed.
                    if (maxScale - minScale > 0.001f) {
                        Text(
                            stringResource(
                                R.string.ultrafix_import_scale,
                                (scale * 100).roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = scale,
                            onValueChange = { scale = it },
                            valueRange = minScale..maxScale,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = { launchPicker() }, enabled = !working) {
                        Text(stringResource(R.string.ultrafix_import_action))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = source != null && !working,
                onClick = {
                    val bitmap = sourceBitmap ?: return@TextButton
                    working = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            if (bitmap.width == outWidth && bitmap.height == outHeight) {
                                bitmap
                            } else {
                                bitmap.scale(outWidth, outHeight)
                            }
                        }
                        working = false
                        onConfirm(result)
                    }
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
