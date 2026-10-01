package io.github.xororz.localdream.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.FuzzyMatcher
import io.github.xororz.localdream.data.TagMatchType
import io.github.xororz.localdream.data.TagSuggestion
import io.github.xororz.localdream.data.tagUnderscoresToSpaces
import kotlin.math.roundToInt

// Fixed height of the pinned action toolbar, subtracted from the suggestion
// list's height budget so the list never pushes the toolbar off the region.
private val SuggestionToolbarHeight = 48.dp

@Composable
fun PromptTagTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: @Composable (() -> Unit),
    suggestions: List<TagSuggestion>,
    onSuggestionClick: (TagSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showSuggestions: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    onDismissSuggestions: () -> Unit = {},
    showToolbar: Boolean = false,
    onAddTag: () -> Unit = {},
    onClearTag: () -> Unit = {},
    onIncreaseWeight: () -> Unit = {},
    onDecreaseWeight: () -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    undoEnabled: Boolean = false,
    redoEnabled: Boolean = false,
    highlightQuery: String? = null,
    overflowOffset: Int = -1,
    maxCollapsedLines: Int = 2,
    minCollapsedLines: Int = 2,
    minExpandedLines: Int = 3,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    var anchorTopPx by remember { mutableFloatStateOf(0f) }
    // Caret tracking: the text layout plus the inner text field's window position
    // and height let us locate the exact pixel line of the caret, so the popup can
    // sit just above the line being typed rather than above the whole (tall) field.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var innerTopPx by remember { mutableFloatStateOf(0f) }
    var innerHeightPx by remember { mutableFloatStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val outlinedColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    )
    val inputTextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    val cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)

    // Grey out the portion of the prompt past the CLIP token limit. The offset
    // is a UTF-16 index from the backend; it may lag the latest keystroke, so
    // the transformation clamps it to the current text length.
    val overflowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val overflowTransformation = remember(overflowOffset, overflowColor) {
        VisualTransformation { text ->
            if (overflowOffset in 0 until text.length) {
                val styled = buildAnnotatedString {
                    append(text.subSequence(0, overflowOffset))
                    withStyle(SpanStyle(color = overflowColor)) {
                        append(text.subSequence(overflowOffset, text.length))
                    }
                }
                TransformedText(styled, OffsetMapping.Identity)
            } else {
                TransformedText(text, OffsetMapping.Identity)
            }
        }
    }

    // BasicTextField (instead of OutlinedTextField) so onTextLayout exposes the
    // caret rectangle; the outlined look is reproduced with DecorationBox.
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .onGloballyPositioned { coords ->
                    anchorWidthPx = coords.size.width
                    anchorTopPx = coords.positionInWindow().y
                },
            enabled = enabled,
            textStyle = inputTextStyle,
            visualTransformation = overflowTransformation,
            maxLines = if (expanded) Int.MAX_VALUE else maxCollapsedLines,
            minLines = if (expanded) minExpandedLines else minCollapsedLines,
            interactionSource = interactionSource,
            cursorBrush = cursorBrush,
            onTextLayout = { textLayout = it },
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text,
                    innerTextField = {
                        // Measure the inner text field so the caret's local rect can
                        // be converted into window coordinates.
                        Box(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                innerTopPx = coords.positionInWindow().y
                                innerHeightPx = coords.size.height.toFloat()
                            },
                        ) {
                            innerTextField()
                        }
                    },
                    enabled = enabled,
                    singleLine = false,
                    visualTransformation = overflowTransformation,
                    interactionSource = interactionSource,
                    label = label,
                    trailingIcon = {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    colors = outlinedColors,
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = outlinedColors,
                            shape = MaterialTheme.shapes.medium,
                        )
                    },
                )
            },
        )

        // The popup stays up for the toolbar even once the dictionary has no matches
        // (e.g. right after a completion is applied, or after a weight is wrapped),
        // so it does not blink away between edits. It lives inside this Column (not
        // as a sibling) so the zero-size popup node does not add an extra gap under
        // the parent's spacedBy arrangement, which pushed the next field down.
        if (showSuggestions && (suggestions.isNotEmpty() || showToolbar) && anchorWidthPx > 0) {
            // Back gesture closes the suggestion popup. The Popup itself is not
            // focusable (so it never steals IME focus), so dismissOnBackPress never
            // fires; this BackHandler is what actually catches the gesture.
            BackHandler(enabled = true) { onDismissSuggestions() }

            // Reset the scroll to the top whenever the suggestion list changes. This
            // has to fire after the new list is applied: LazyColumn keyed items keep
            // the previously-anchored row pinned, so a freshly promoted top match
            // (e.g. "underwater" overtaking "underwear") would otherwise stay scrolled
            // off the top of the viewport.
            val listState = rememberLazyListState()
            LaunchedEffect(suggestions) {
                listState.scrollToItem(0)
            }

            val widthDp = with(density) { anchorWidthPx.toDp() }
            val gapDp = 8.dp
            val gapPx = with(density) { gapDp.toPx() }

            // Insets observed live so the popup tracks IME open/close animations.
            val imeBottomPx = WindowInsets.ime.getBottom(density)
            val statusTopPx = WindowInsets.statusBars.getTop(density)
            val navBottomPx = WindowInsets.navigationBars.getBottom(density)
            val bottomInsetPx = maxOf(imeBottomPx, navBottomPx)

            // Window Y of the caret's line top. The popup bottom sits just above it, so
            // it may cover the field's upper lines but never the line being typed.
            // - When the text fits the field, the caret rect maps directly.
            // - When the field is scrolled, the caret is kept on the last visible line,
            //   so anchor to (viewport bottom - line height).
            // - Before the first layout, fall back to the whole field's top.
            val caretLineTopPx = run {
                val layout = textLayout
                if (layout != null && innerHeightPx > 0f && layout.size.height <= innerHeightPx + 0.5f) {
                    // Text fits the field (expanded grows to fit, or collapsed short
                    // text): the caret rect maps directly to its on-screen line.
                    val offset = value.selection.start.coerceIn(0, layout.layoutInput.text.length)
                    innerTopPx + layout.getCursorRect(offset).top
                } else {
                    // Field is internally scrolled (collapsed long text) or not laid out
                    // yet: the caret line cannot be derived reliably, so anchor above the
                    // whole field. The field is only a couple of lines, so little is lost
                    // and no line is ever covered, whichever one the caret is on.
                    anchorTopPx
                }
            }
            val popupBottomPx = (caretLineTopPx - gapPx).roundToInt()

            // One text line, used to keep the popup above the caret even when the IME
            // pans the window up: positionInWindow does not reflect a pan, but the IME
            // inset does, so the provider clamps the popup to one line above the IME.
            val lineHeightPx = run {
                val layout = textLayout
                if (layout != null && layout.lineCount > 0) {
                    layout.getLineBottom(0) - layout.getLineTop(0)
                } else {
                    with(density) {
                        val lh = inputTextStyle.lineHeight
                        if (lh.isSp) lh.toPx() else 24.sp.toPx()
                    }
                }
            }.roundToInt()

            // Space above the caret line that is visible (excludes the status bar). The
            // toolbar and divider are fixed chrome below the list, so the list cap is
            // that budget minus the chrome.
            val availableAbovePx = (caretLineTopPx - statusTopPx - gapPx).coerceAtLeast(0f)
            val capDp = with(density) { minOf(280.dp, availableAbovePx.toDp()) }
            val maxHeightDp = (capDp - SuggestionToolbarHeight - 1.dp).coerceAtLeast(0.dp)
            Popup(
                // Bottom-anchored to the caret line. popupBottomPx only changes when the
                // caret moves to another line, so during same-line typing and list
                // resizes the provider instance is stable and nothing jumps.
                popupPositionProvider = remember(popupBottomPx, lineHeightPx, statusTopPx, bottomInsetPx) {
                    CaretAnchorPositionProvider(
                        popupBottomPx = popupBottomPx,
                        lineHeightPx = lineHeightPx,
                        safeTopPx = statusTopPx,
                        bottomInsetPx = bottomInsetPx,
                    )
                },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                ),
            ) {
                // Fixed-size box spanning the whole reserved region, so the popup
                // window never resizes as the list grows/shrinks (that resize is what
                // made it jump). The card is bottom-aligned, hugging the caret line;
                // the list grows upward inside, with transparent space above.
                Box(
                    modifier = Modifier
                        .width(widthDp)
                        .height(capDp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Card(
                        modifier = Modifier.width(widthDp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    ) {
                        if (suggestions.isNotEmpty()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = maxHeightDp),
                            ) {
                                items(
                                    items = suggestions,
                                    key = { it.replacementTag },
                                ) { suggestion ->
                                    SuggestionRow(
                                        suggestion = suggestion,
                                        highlightQuery = highlightQuery,
                                        onClick = { onSuggestionClick(suggestion) },
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        // Action row at the bottom of the card, right above the caret,
                        // so it keeps a stable position no matter how the suggestion
                        // list above it resizes.
                        TagActionToolbar(
                            onAddTag = onAddTag,
                            onClearTag = onClearTag,
                            onIncreaseWeight = onIncreaseWeight,
                            onDecreaseWeight = onDecreaseWeight,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onClose = onDismissSuggestions,
                            undoEnabled = undoEnabled,
                            redoEnabled = redoEnabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagActionToolbar(
    onAddTag: () -> Unit,
    onClearTag: () -> Unit,
    onIncreaseWeight: () -> Unit,
    onDecreaseWeight: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClose: () -> Unit,
    undoEnabled: Boolean,
    redoEnabled: Boolean,
) {
    // Tonal groups read apart at a glance: tag edits (secondary), weight steps
    // (tertiary), undo/redo history (primary) and a neutral close. This mirrors
    // the MD3 Expressive button-group look without depending on experimental APIs.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SuggestionToolbarHeight)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarAction(
            icon = Icons.Default.Add,
            contentDescription = "add tag",
            container = MaterialTheme.colorScheme.secondaryContainer,
            onColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onAddTag,
        )
        ToolbarAction(
            icon = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "clear tag",
            container = MaterialTheme.colorScheme.secondaryContainer,
            onColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onClearTag,
        )
        ToolbarAction(
            icon = Icons.Default.ArrowUpward,
            contentDescription = "increase weight",
            container = MaterialTheme.colorScheme.tertiaryContainer,
            onColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onIncreaseWeight,
        )
        ToolbarAction(
            icon = Icons.Default.ArrowDownward,
            contentDescription = "decrease weight",
            container = MaterialTheme.colorScheme.tertiaryContainer,
            onColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onDecreaseWeight,
        )
        ToolbarAction(
            icon = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "undo",
            container = MaterialTheme.colorScheme.primaryContainer,
            onColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onUndo,
            enabled = undoEnabled,
        )
        ToolbarAction(
            icon = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "redo",
            container = MaterialTheme.colorScheme.primaryContainer,
            onColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onRedo,
            enabled = redoEnabled,
        )
        ToolbarAction(
            icon = Icons.Default.Close,
            contentDescription = "close suggestions",
            container = MaterialTheme.colorScheme.surfaceVariant,
            onColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onClose,
        )
    }
}

@Composable
private fun RowScope.ToolbarAction(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    onColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val disabledAlpha = 0.38f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = if (enabled) container else container.copy(alpha = disabledAlpha),
        contentColor = if (enabled) onColor else onColor.copy(alpha = disabledAlpha),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: TagSuggestion, highlightQuery: String?, onClick: () -> Unit) {
    // Embedding names round-trip into the prompt verbatim; spaces would break
    // the lookup in PromptProcessor (it keys on the lowercase filename stem).
    val displayPrimary = if (suggestion.matchType == TagMatchType.Embedding) {
        suggestion.primaryText
    } else {
        tagUnderscoresToSpaces(suggestion.primaryText)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (suggestion.matchType == TagMatchType.Embedding) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        categoryColor(suggestion.category)
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = highlightMatches(displayPrimary, highlightQuery, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            suggestion.secondaryText?.takeIf { it.isNotBlank() }?.let { secondary ->
                Text(
                    text = highlightMatches(secondary, highlightQuery, MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (suggestion.postCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatPostCount(suggestion.postCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MatchTypeBadge(suggestion.matchType)
    }
}

@Composable
private fun MatchTypeBadge(matchType: TagMatchType) {
    val label = when (matchType) {
        TagMatchType.Alias -> stringResource(R.string.tag_alias_label)
        TagMatchType.Correction -> stringResource(R.string.tag_correction_label)
        TagMatchType.Embedding -> stringResource(R.string.tag_embedding_label)
        else -> return
    }
    val container = when (matchType) {
        TagMatchType.Correction -> MaterialTheme.colorScheme.errorContainer
        TagMatchType.Embedding -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (matchType) {
        TagMatchType.Correction -> MaterialTheme.colorScheme.onErrorContainer
        TagMatchType.Embedding -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Spacer(Modifier.width(8.dp))
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun categoryColor(category: Int): Color = when (category) {
    1 -> Color(0xFFE53935)

    // artist
    3 -> Color(0xFFAB47BC)

    // copyright
    4 -> Color(0xFF43A047)

    // character
    5 -> Color(0xFFFB8C00)

    // meta
    else -> MaterialTheme.colorScheme.outline // general / unknown
}

private fun formatPostCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1_000}k"
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun normalizeForHighlight(value: String): String = value.lowercase().replace(' ', '_').replace('-', '_')

private fun highlightMatches(text: String, query: String?, highlightColor: Color): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val normQuery = normalizeForHighlight(query.trim())
    if (normQuery.isEmpty()) return AnnotatedString(text)
    // normalizeForHighlight only swaps single chars (no trimming or collapsing),
    // so positions into normText line up one-to-one with text.
    val normText = normalizeForHighlight(text)
    val positions = FuzzyMatcher.positions(normQuery.toCharArray(), normText)
    if (positions == null || positions.isEmpty()) return AnnotatedString(text)
    val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor)
    return buildAnnotatedString {
        var idx = 0
        var p = 0
        while (idx < text.length) {
            if (p < positions.size && positions[p] == idx) {
                val start = idx
                while (p < positions.size && positions[p] == idx) {
                    p++
                    idx++
                }
                withStyle(matchStyle) {
                    append(text.substring(start, idx))
                }
            } else {
                val start = idx
                while (idx < text.length && (p >= positions.size || positions[p] != idx)) idx++
                append(text.substring(start, idx))
            }
        }
    }
}

private class CaretAnchorPositionProvider(
    private val popupBottomPx: Int,
    private val lineHeightPx: Int,
    private val safeTopPx: Int,
    private val bottomInsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 8
        // Visible bottom is the lowest screen Y that is not occluded by the IME
        // or the navigation bar. Falling back to windowSize.height keeps the math
        // safe if insets are reported as 0 on some OEMs.
        val visibleBottom = (windowSize.height - bottomInsetPx).coerceAtLeast(0)

        // Place the popup so its bottom edge lands on popupBottomPx (just above the
        // caret line). When the IME pans the window up, the caret is lifted to just
        // above the keyboard but positionInWindow does not reflect the pan; the IME
        // inset does, so clamp the bottom to one line above the IME. That keeps the
        // popup above the (panned) caret line instead of covering it or spilling
        // onto the keyboard.
        val bottom = minOf(popupBottomPx, visibleBottom - lineHeightPx - gap)
        val aboveY = bottom - popupContentSize.height
        // Fall back below the field (read fresh from anchorBounds) when there is
        // not enough room above, e.g. the field is near the top of the window.
        val belowY = anchorBounds.bottom + gap

        val y = when {
            aboveY >= safeTopPx -> aboveY
            belowY + popupContentSize.height <= visibleBottom -> belowY
            else -> aboveY.coerceAtLeast(safeTopPx)
        }
        val x = anchorBounds.left.coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0),
        )
        return IntOffset(x, y)
    }
}
