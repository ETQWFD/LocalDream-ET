package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.ui.theme.Motion

/** Result tab of the run screen: latest image, quick actions and recent thumbnails. */
@Composable
internal fun ModelRunResultPage(
    currentBitmap: Bitmap?,
    imageVersion: Int,
    generationParams: GenerationParameters?,
    // Newest few items only (bounded query); drives the thumbnail strip.
    recentHistory: List<HistoryItem>,
    showReportButton: Boolean,
    showUpscaleButton: Boolean,
    upscaleEnabled: Boolean,
    showUltrafixButton: Boolean,
    ultrafixEnabled: Boolean,
    // Long-pressing either the upscale or the UltraFix button opens the
    // local-image import dialog for UltraFix.
    onUpscaleLongClick: () -> Unit,
    onUltrafixLongClick: () -> Unit,
    // null when the displayed image has no history row to mark (hides the
    // favorite button).
    isFavorite: Boolean?,
    onFavoriteClick: () -> Unit,
    onReportClick: () -> Unit,
    onUpscaleClick: () -> Unit,
    onUltrafixClick: () -> Unit,
    onSaveClick: (Bitmap) -> Unit,
    onPreviewClick: () -> Unit,
    onShowParameters: () -> Unit,
    onHistoryThumbClick: (HistoryItem) -> Unit,
    onGoToGenerate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Crossfade(
            targetState = currentBitmap != null,
            label = "result_crossfade",
        ) { hasResult ->
            if (!hasResult) {
                ElevatedCard(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val iconScale = remember { Animatable(1f) }
                        LaunchedEffect(Unit) {
                            iconScale.animateTo(
                                targetValue = 1.1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .graphicsLayer(
                                    scaleX = iconScale.value,
                                    scaleY = iconScale.value,
                                ),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.no_results),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.no_results_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onGoToGenerate,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.go_to_generate))
                        }
                    }
                }
            } else {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.result_tab),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            currentBitmap?.let { bitmap ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(
                                        8.dp,
                                    ),
                                ) {
                                    if (isFavorite != null) {
                                        FilledTonalIconButton(
                                            onClick = onFavoriteClick,
                                        ) {
                                            Icon(
                                                imageVector = if (isFavorite) {
                                                    Icons.Default.Favorite
                                                } else {
                                                    Icons.Default.FavoriteBorder
                                                },
                                                contentDescription = "toggle favorite",
                                            )
                                        }
                                    }

                                    if (showReportButton) {
                                        FilledTonalIconButton(
                                            onClick = onReportClick,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Report,
                                                contentDescription = "report inappropriate content",
                                            )
                                        }
                                    }

                                    if (showUpscaleButton) {
                                        LongPressableTonalIconButton(
                                            enabled = upscaleEnabled,
                                            onClick = onUpscaleClick,
                                            onLongClick = onUpscaleLongClick,
                                            icon = Icons.Default.AutoFixHigh,
                                            contentDescription = "upscale image",
                                        )
                                    }

                                    if (showUltrafixButton) {
                                        LongPressableTonalIconButton(
                                            enabled = ultrafixEnabled,
                                            onClick = onUltrafixClick,
                                            onLongClick = onUltrafixLongClick,
                                            icon = Icons.Default.AutoAwesome,
                                            contentDescription = "ultrafix image",
                                        )
                                    }

                                    FilledTonalIconButton(
                                        onClick = { onSaveClick(bitmap) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Save,
                                            contentDescription = "save image",
                                        )
                                    }
                                }
                            }
                        }

                        // The shadowed Surface stays outside AnimatedContent: a
                        // drop shadow rendered inside the crossfade's alpha layer
                        // composites with a rectangular outline and flashes square
                        // corners. Keeping it static lets only the image crossfade,
                        // clipped to the rounded shape.
                        Surface(
                            onClick = onPreviewClick,
                            enabled = currentBitmap != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            shape = MaterialTheme.shapes.medium,
                            shadowElevation = 4.dp,
                        ) {
                            AnimatedContent(
                                targetState = imageVersion to currentBitmap,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    fadeIn(animationSpec = Motion.Fade) togetherWith
                                        fadeOut(animationSpec = Motion.FadeOut)
                                },
                                label = "ImagePreviewCrossfade",
                            ) { (_, bitmap) ->
                                bitmap?.let {
                                    AsyncImage(
                                        model = ImageRequest.Builder(
                                            LocalContext.current,
                                        )
                                            .data(it)
                                            .size(coil.size.Size.ORIGINAL)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "generated image",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        if (recentHistory.size > 1) {
                            val thumbListState = rememberLazyListState()
                            val newestId = recentHistory.firstOrNull()?.id
                            LaunchedEffect(newestId) {
                                // New items are inserted at the head and the keyed
                                // lazy list anchors to the currently visible thumb,
                                // which is right mid-scroll but leaves a new thumb
                                // hidden off-screen when the row is parked at the
                                // far left. Index <= 1 covers reading the position
                                // both before (0) and after (1) the anchoring
                                // measure pass; the frame wait orders the snap
                                // after that pass.
                                if (newestId != null &&
                                    thumbListState.firstVisibleItemIndex <= 1 &&
                                    thumbListState.firstVisibleItemScrollOffset == 0
                                ) {
                                    withFrameNanos {}
                                    thumbListState.scrollToItem(0)
                                }
                            }
                            LazyRow(
                                state = thumbListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp,
                                ),
                            ) {
                                items(
                                    recentHistory.take(20),
                                    key = { it.id },
                                ) { item ->
                                    Card(
                                        onClick = { onHistoryThumbClick(item) },
                                        modifier = Modifier.size(72.dp),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(
                                                LocalContext.current,
                                            )
                                                .data(item.imageFile)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = "thumb",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }

                        Card(
                            onClick = onShowParameters,
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.generation_params),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "view details",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                generationParams?.let { params ->
                                    Text(
                                        stringResource(
                                            R.string.result_params,
                                            params.steps,
                                            params.cfg,
                                            params.seed.toString(),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.result_params_2,
                                            params.width,
                                            params.height,
                                            params.generationTime
                                                ?: "unknown",
                                            if (params.runOnCpu) {
                                                if (params.useOpenCL) "GPU" else "CPU"
                                            } else {
                                                "NPU"
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** History tab of the run screen: filter bar, image grid and selection toolbar. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModelRunHistoryPage(
    historyFilter: HistoryFilter,
    currentModelId: String?,
    pagedItems: LazyPagingItems<HistoryItem>,
    totalCount: Int,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    isBatchSaving: Boolean,
    onFilterChange: (HistoryFilter) -> Unit,
    onShowFilterSheet: () -> Unit,
    onItemClick: (HistoryItem) -> Unit,
    onItemLongClick: (HistoryItem) -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onBatchSave: () -> Unit,
    onBatchDelete: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val timestampFormat = remember(locale) {
        java.text.SimpleDateFormat("MM/dd HH:mm", locale)
    }
    // Handle back button in selection mode
    BackHandler(enabled = isSelectionMode && !isBatchSaving) {
        onExitSelection()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // The global history screen hosts its filter entry in the top app
        // bar; only the model run screen (which has no app bar of its own for
        // this tab) shows the inline current/all + filter bar.
        if (currentModelId != null) {
            HistoryFilterBar(
                filter = historyFilter,
                currentModelId = currentModelId,
                onShowFilterSheet = onShowFilterSheet,
                onSetCurrentModelOnly = {
                    onFilterChange(historyFilter.copy(modelIds = setOf(currentModelId)))
                },
                onSetAllModels = {
                    onFilterChange(historyFilter.copy(modelIds = null))
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // Only show the empty state once the initial load has settled, so
            // it never flashes while the first page is still being fetched.
            val isEmpty = pagedItems.itemCount == 0 &&
                pagedItems.loadState.refresh is LoadState.NotLoading
            if (isEmpty) {
                var emptyVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { emptyVisible = true }
                val emptyAlpha by animateFloatAsState(
                    targetValue = if (emptyVisible) 1f else 0f,
                    animationSpec = Motion.Fade,
                    label = "emptyAlpha",
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(emptyAlpha),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.offset(y = (-60).dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.no_history),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.no_history_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = pagedItems.itemCount,
                        key = pagedItems.itemKey { it.id },
                    ) { index ->
                        // With placeholders disabled, get() returns non-null for
                        // every index below itemCount; guard anyway for safety.
                        val item = pagedItems[index] ?: return@items
                        val isSelected = item.id in selectedIds
                        Card(
                            modifier = Modifier.aspectRatio(1f),
                            shape = MaterialTheme.shapes.medium,
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = 2.dp,
                            ),
                        ) {
                            // Clickable inside the card so its ripple is clipped
                            // to the rounded shape (square corners otherwise).
                            Box(
                                modifier = Modifier.combinedClickable(
                                    onClick = { onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                ),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(item.imageFile)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Generated image",
                                    modifier = Modifier.fillMaxSize(),
                                )

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(
                                                    alpha = 0.2f,
                                                ),
                                            ),
                                    )
                                }

                                if (item.favorite) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "favorited",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp)
                                            .size(16.dp),
                                    )
                                }

                                // Timestamp overlay
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomStart),
                                    shape = RoundedCornerShape(
                                        topStart = 0.dp,
                                        topEnd = 4.dp,
                                        bottomStart = 12.dp,
                                        bottomEnd = 0.dp,
                                    ),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        .copy(alpha = 0.8f),
                                ) {
                                    Text(
                                        text = remember(item.timestamp, locale) {
                                            timestampFormat.format(java.util.Date(item.timestamp))
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(
                                            horizontal = 6.dp,
                                            vertical = 3.dp,
                                        ),
                                    )
                                }

                                // Selection indicator
                                if (isSelectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .size(24.dp)
                                            .background(
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)
                                                },
                                                shape = CircleShape,
                                            )
                                            .border(
                                                width = 2.dp,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    Color.White
                                                },
                                                shape = CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating selection mode bottom bar
            if (isSelectionMode) {
                val isAllSelected = totalCount > 0 && selectedIds.size >= totalCount
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    leadingContent = {
                        IconButton(
                            onClick = onExitSelection,
                            enabled = !isBatchSaving,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Exit selection mode",
                            )
                        }
                    },
                    trailingContent = {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                        ) {
                            IconButton(
                                onClick = onToggleSelectAll,
                                enabled = !isBatchSaving,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(
                                    imageVector = if (isAllSelected) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.CheckCircleOutline
                                    },
                                    contentDescription = if (isAllSelected) "Deselect all" else "Select all",
                                )
                            }
                            IconButton(
                                onClick = onBatchSave,
                                enabled = selectedIds.isNotEmpty() && !isBatchSaving,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save selected",
                                )
                            }
                            IconButton(
                                onClick = onBatchDelete,
                                enabled = selectedIds.isNotEmpty() && !isBatchSaving,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete selected",
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.selected_items_count,
                            selectedIds.size,
                            selectedIds.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

// FilledTonalIconButton look-alike with a long-press slot (M3 icon buttons
// expose none). Both result-page image actions use it so a long press on
// either can open the UltraFix local-image import dialog.
@Composable
private fun LongPressableTonalIconButton(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    val colors = IconButtonDefaults.filledTonalIconButtonColors()
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            // Standard M3 icon-button container.
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (enabled) colors.containerColor else colors.disabledContainerColor,
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.contentColor else colors.disabledContentColor,
        )
    }
}
