package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.xororz.localdream.data.TagAutocompleteRepository
import io.github.xororz.localdream.data.TagSuggestion
import io.github.xororz.localdream.ui.components.PromptTagTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Prompt undo/redo: cap on stored steps, and the window within which continuous
// typing collapses into a single step.
private const val HISTORY_LIMIT = 100
private const val HISTORY_COALESCE_MS = 600L

/**
 * State holder for one prompt input field: text and selection, tag-autocomplete
 * suggestions, undo/redo history and token-count info. The run screen creates
 * one instance for the prompt and one for the negative prompt; everything that
 * used to be a duplicated pair of state vars and functions lives here once.
 */
@Stable
internal class PromptFieldController(
    private val scope: CoroutineScope,
    private val repository: TagAutocompleteRepository,
    private val suggestionCount: Int,
    private val embeddingSuggestionsFor: (String) -> List<TagSuggestion>,
) {
    /** Refreshed from composition; gates suggestion lookups. */
    var autocompleteAvailable: Boolean = false

    /** Invoked whenever the text changes; the screen hooks persistence here. */
    var onTextCommitted: () -> Unit = {}

    var fieldValue by mutableStateOf(TextFieldValue(""))
        private set
    val text: String get() = fieldValue.text

    var suggestions by mutableStateOf<List<TagSuggestion>>(emptyList())
        private set
    var activeQuery by mutableStateOf<String?>(null)
        private set
    var isFocused by mutableStateOf(false)
        private set

    // Set when the back gesture dismisses the popup; reset on the next edit so the
    // popup stays closed until the user actually does something again.
    var popupDismissed by mutableStateOf(false)
        private set

    // Undo/redo history of the text. Rapid typing coalesces into a single step;
    // suggestion picks and toolbar edits are discrete steps.
    private var undoStack by mutableStateOf<List<String>>(emptyList())
    private var redoStack by mutableStateOf<List<String>>(emptyList())
    private var historyAt = 0L
    val undoEnabled: Boolean get() = undoStack.isNotEmpty()
    val redoEnabled: Boolean get() = redoStack.isNotEmpty()

    var tokenCount by mutableIntStateOf(2)
    var tokenMax by mutableIntStateOf(77)

    // UTF-16 index from which the text exceeds the token limit, or -1 when it
    // fits. Drives the greyed-out overflow hint in the field.
    var overflowOffset by mutableIntStateOf(-1)

    private var suggestJob: Job? = null

    // Records `snapshot` as an undo checkpoint. Continuous typing within the
    // coalesce window collapses into one step; discrete edits (suggestion picks,
    // toolbar actions) pass coalesce = false to always start a new step.
    private fun pushHistory(snapshot: String, coalesce: Boolean) {
        val now = System.currentTimeMillis()
        val skip = coalesce && undoStack.isNotEmpty() && now - historyAt < HISTORY_COALESCE_MS
        if (!skip) {
            undoStack = (undoStack + snapshot).takeLast(HISTORY_LIMIT)
        }
        redoStack = emptyList()
        // Discrete edits leave the window closed so the next keystroke opens a
        // fresh step instead of merging into the discrete one.
        historyAt = if (coalesce) now else 0L
    }

    fun update(value: TextFieldValue, recordHistory: Boolean = true) {
        val previousText = fieldValue.text
        val textChanged = value.text != previousText
        val selectionChanged = value.selection != fieldValue.selection
        if (textChanged && recordHistory) {
            pushHistory(previousText, coalesce = true)
        }
        if (textChanged || selectionChanged) {
            popupDismissed = false
        }
        fieldValue = value
        if (textChanged) {
            onTextCommitted()
        }
        if (!autocompleteAvailable || !isFocused) {
            suggestJob?.cancel()
            suggestions = emptyList()
            activeQuery = null
            return
        }
        if (!textChanged && !selectionChanged) return
        val activeTag =
            TagAutocompleteRepository.extractActiveTag(value.text, value.selection.start)
        if (activeTag == null) {
            suggestJob?.cancel()
            suggestions = emptyList()
            activeQuery = null
            return
        }
        activeQuery = activeTag.token
        suggestJob?.cancel()
        suggestJob = scope.launch {
            delay(200)
            val embeddings = embeddingSuggestionsFor(activeTag.token)
            val results = repository.suggest(activeTag.token, suggestionCount)
            // Embeddings always pinned to the top; their relevance is local to
            // this user, so they outrank dictionary suggestions by construction.
            suggestions = embeddings + results
        }
    }

    fun applySuggestion(suggestion: TagSuggestion) {
        // Discrete history step so an accidental pick can be undone. The popup is
        // left up (suggestions cleared, but the toolbar persists) for follow-up.
        pushHistory(fieldValue.text, coalesce = false)
        val (updatedText, updatedSelection) = TagAutocompleteRepository.applySuggestion(
            fieldValue.text,
            fieldValue.selection.start,
            suggestion,
        )
        fieldValue = TextFieldValue(updatedText, TextRange(updatedSelection))
        suggestions = emptyList()
        activeQuery = null
        popupDismissed = false
        onTextCommitted()
    }

    // Runs one of the suggestion-toolbar text edits against the field as a
    // discrete undo step, then routes the result back through update so the
    // suggestions (and the popup) refresh against the new caret position.
    fun runTagAction(action: (String, Int) -> Pair<String, Int>?) {
        val (updatedText, updatedSelection) = action(
            fieldValue.text,
            fieldValue.selection.start,
        ) ?: return
        pushHistory(fieldValue.text, coalesce = false)
        update(
            TextFieldValue(updatedText, TextRange(updatedSelection)),
            recordHistory = false,
        )
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.last()
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + fieldValue.text).takeLast(HISTORY_LIMIT)
        historyAt = 0L
        update(
            TextFieldValue(previous, TextRange(previous.length)),
            recordHistory = false,
        )
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.last()
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + fieldValue.text).takeLast(HISTORY_LIMIT)
        historyAt = 0L
        update(
            TextFieldValue(next, TextRange(next.length)),
            recordHistory = false,
        )
    }

    /**
     * Replaces the text wholesale (initial load, reset, reproduce, import),
     * bypassing undo history and clearing any open suggestions. Persistence is
     * the caller's responsibility, matching the previous inline assignments.
     */
    fun replaceText(newText: String) {
        fieldValue = TextFieldValue(newText, TextRange(newText.length))
        suggestions = emptyList()
    }

    fun onFocusChanged(focused: Boolean) {
        isFocused = focused
        if (!focused) suggestions = emptyList()
    }

    fun dismissPopup() {
        popupDismissed = true
    }
}

@Composable
internal fun rememberPromptFieldController(
    repository: TagAutocompleteRepository,
    suggestionCount: Int,
    embeddingSuggestionsFor: (String) -> List<TagSuggestion>,
): PromptFieldController {
    val scope = rememberCoroutineScope()
    return remember {
        PromptFieldController(scope, repository, suggestionCount, embeddingSuggestionsFor)
    }
}

/**
 * Debounced backend tokenizer sync for one prompt field. Only runs once the
 * backend is reachable; re-runs whenever the text changes. [backendHost]
 * selects the local backend or a remote host's generation port.
 */
@Composable
internal fun PromptTokenCountEffect(
    controller: PromptFieldController,
    backendReady: Boolean,
    backendHost: String,
) {
    LaunchedEffect(controller.text, backendReady, backendHost) {
        if (!backendReady) return@LaunchedEffect
        delay(400)
        val result = tokenizePromptRequest(controller.text, backendHost) ?: return@LaunchedEffect
        controller.tokenCount = result.count
        controller.tokenMax = result.maxLength
        controller.overflowOffset = result.overflowOffset
    }
}

/** PromptTagTextField wired to a [PromptFieldController]. */
@Composable
internal fun ControlledPromptTagTextField(
    controller: PromptFieldController,
    autocompleteAvailable: Boolean,
    label: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
) {
    val popupVisible =
        autocompleteAvailable && controller.isFocused && !controller.popupDismissed
    PromptTagTextField(
        value = controller.fieldValue,
        onValueChange = { controller.update(it) },
        modifier = modifier,
        label = label,
        suggestions = controller.suggestions,
        onSuggestionClick = controller::applySuggestion,
        showSuggestions = popupVisible,
        // Toolbar stays up even on an empty prompt so undo/redo remain reachable.
        showToolbar = popupVisible,
        highlightQuery = controller.activeQuery,
        overflowOffset = controller.overflowOffset,
        onFocusChanged = controller::onFocusChanged,
        onDismissSuggestions = controller::dismissPopup,
        onUndo = controller::undo,
        onRedo = controller::redo,
        undoEnabled = controller.undoEnabled,
        redoEnabled = controller.redoEnabled,
        onAddTag = {
            controller.runTagAction(TagAutocompleteRepository::appendTagAfterActive)
        },
        onClearTag = {
            controller.runTagAction(TagAutocompleteRepository::clearActiveTag)
        },
        onIncreaseWeight = {
            controller.runTagAction { text, sel ->
                TagAutocompleteRepository.adjustActiveTagWeight(text, sel, 0.1)
            }
        },
        onDecreaseWeight = {
            controller.runTagAction { text, sel ->
                TagAutocompleteRepository.adjustActiveTagWeight(text, sel, -0.1)
            }
        },
    )
}
