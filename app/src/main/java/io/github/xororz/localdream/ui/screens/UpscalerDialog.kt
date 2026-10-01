package io.github.xororz.localdream.ui.screens

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DownloadProgress
import io.github.xororz.localdream.data.UpscalerModel
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.ui.components.SmoothLinearWavyProgressIndicator
import io.github.xororz.localdream.utils.UPSCALER_NATIVE_SCALE

/**
 * Upscaler picker with download handling. Tracks ModelDownloadService state for
 * in-dialog progress, persists the confirmed choice under
 * "<modelId>_selected_upscaler", and only invokes [onUpscalerConfirmed] for a
 * model that is already downloaded.
 *
 * [upscalersOverride] replaces the local repository's list (connected-device
 * mode: the host's installed upscalers, always isDownloaded = true, so the
 * download path is unreachable).
 */
@Composable
fun UpscalerPickerFlow(
    modelId: String,
    upscalerRepository: UpscalerRepository,
    upscalerPreferences: SharedPreferences,
    onDismiss: () -> Unit,
    onUpscalerConfirmed: (UpscalerModel, Int) -> Unit,
    upscalersOverride: List<UpscalerModel>? = null,
) {
    val context = LocalContext.current
    val msgDownloadDone = stringResource(R.string.download_done)
    val msgErrorDownloadFailed = stringResource(R.string.error_download_failed)
    val msgDownloadModelFirst = stringResource(R.string.download_model_first)

    LaunchedEffect(Unit) {
        if (upscalersOverride == null) upscalerRepository.ensureLoaded()
    }
    val upscalers = upscalersOverride ?: upscalerRepository.upscalers
    var tempSelectedUpscalerId by remember {
        mutableStateOf(upscalerPreferences.getString("${modelId}_selected_upscaler", null))
    }
    var tempSelectedScale by remember {
        mutableStateOf(
            upscalerPreferences.getInt("${modelId}_upscale_scale", UPSCALER_NATIVE_SCALE),
        )
    }
    var downloadingUpscalerId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

    LaunchedEffect(Unit) {
        ModelDownloadService.downloadState.collect { state ->
            when (state) {
                is ModelDownloadService.DownloadState.Downloading -> {
                    val upscaler =
                        upscalerRepository.upscalers.find { it.id == state.modelId }
                    if (upscaler != null) {
                        downloadingUpscalerId = upscaler.id
                        downloadProgress = DownloadProgress(
                            progress = state.progress,
                            downloadedBytes = state.downloadedBytes,
                            totalBytes = state.totalBytes,
                        )
                    }
                }

                is ModelDownloadService.DownloadState.Success -> {
                    upscalerRepository.refreshUpscalerState(state.modelId)
                    downloadingUpscalerId = null
                    downloadProgress = null
                    Toast.makeText(
                        context,
                        msgDownloadDone,
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                is ModelDownloadService.DownloadState.Error -> {
                    downloadingUpscalerId = null
                    downloadProgress = null
                    Toast.makeText(
                        context,
                        msgErrorDownloadFailed.format(state.message),
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                is ModelDownloadService.DownloadState.Extracting -> {
                    val upscaler =
                        upscalerRepository.upscalers.find { it.id == state.modelId }
                    if (upscaler != null) {
                        downloadingUpscalerId = upscaler.id
                        downloadProgress = null // Indeterminate progress during extraction
                    }
                }

                is ModelDownloadService.DownloadState.Idle -> {
                    if (downloadingUpscalerId != null && downloadProgress == null) {
                        downloadingUpscalerId = null
                    }
                }
            }
        }
    }

    UpscalerSelectDialog(
        upscalers = upscalers,
        selectedUpscalerId = tempSelectedUpscalerId,
        selectedScale = tempSelectedScale,
        downloadingUpscalerId = downloadingUpscalerId,
        downloadProgress = downloadProgress,
        onDismiss = onDismiss,
        onSelectUpscaler = { upscalerId ->
            tempSelectedUpscalerId = upscalerId
        },
        onSelectScale = { scale ->
            tempSelectedScale = scale
        },
        onConfirm = {
            val selectedUpscaler =
                upscalers.find { it.id == tempSelectedUpscalerId }
            if (selectedUpscaler != null && selectedUpscaler.isDownloaded) {
                upscalerPreferences.edit {
                    putString("${modelId}_selected_upscaler", selectedUpscaler.id)
                    putInt("${modelId}_upscale_scale", tempSelectedScale)
                }
                onUpscalerConfirmed(selectedUpscaler, tempSelectedScale)
            } else if (selectedUpscaler != null) {
                Toast.makeText(
                    context,
                    msgDownloadModelFirst,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onDownload = { upscaler ->
            downloadingUpscalerId = upscaler.id
            downloadProgress = null
            upscaler.startDownload(context)
        },
    )
}

@Composable
fun UpscalerSelectDialog(
    upscalers: List<UpscalerModel>,
    selectedUpscalerId: String?,
    selectedScale: Int,
    downloadingUpscalerId: String?,
    downloadProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onSelectScale: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDownload: (UpscalerModel) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_upscaler_model)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(upscalers) { upscaler ->
                    UpscalerModelCard(
                        upscaler = upscaler,
                        isSelected = upscaler.id == selectedUpscalerId,
                        isDownloading = upscaler.id == downloadingUpscalerId,
                        downloadProgress = if (upscaler.id == downloadingUpscalerId) downloadProgress else null,
                        onSelect = { onSelectUpscaler(upscaler.id) },
                        onDownload = { onDownload(upscaler) },
                    )
                }
                item {
                    UpscaleScaleSelector(
                        selectedScale = selectedScale,
                        onSelectScale = onSelectScale,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = selectedUpscalerId != null,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
    )
}

// Selectable upscale ratios. The model runs natively at UPSCALER_NATIVE_SCALE;
// smaller ratios are produced by downscaling its result.
private val upscaleScaleOptions = listOf(2, 3, UPSCALER_NATIVE_SCALE)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpscaleScaleSelector(
    selectedScale: Int,
    onSelectScale: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.upscale_scale_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                ButtonGroupDefaults.ConnectedSpaceBetween,
            ),
        ) {
            upscaleScaleOptions.forEachIndexed { index, scale ->
                val shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()

                    upscaleScaleOptions.lastIndex ->
                        ButtonGroupDefaults.connectedTrailingButtonShapes()

                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
                ToggleButton(
                    checked = selectedScale == scale,
                    onCheckedChange = { checked -> if (checked) onSelectScale(scale) },
                    shapes = shapes,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.upscale_factor, scale))
                }
            }
        }
        Text(
            text = stringResource(R.string.upscale_scale_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpscalerModelCard(
    upscaler: UpscalerModel,
    isSelected: Boolean,
    isDownloading: Boolean,
    downloadProgress: DownloadProgress?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = upscaler.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = upscaler.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (!upscaler.isDownloaded) {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.download),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.download))
                    }
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Show progress bar when downloading
            if (isDownloading && downloadProgress != null) {
                SmoothLinearWavyProgressIndicator(
                    progress = downloadProgress.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}
