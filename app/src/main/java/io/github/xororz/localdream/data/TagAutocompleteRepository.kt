package io.github.xororz.localdream.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TagAutocompleteRepository private constructor(private val context: Context) {
    private val loadMutex = Mutex()

    @Volatile
    private var loadedData: TagData? = null

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dictDir = File(context.filesDir, DICT_DIR).also { it.mkdirs() }
    private val mainFile = File(dictDir, MAIN_FILE_NAME)
    private val translationFile = File(dictDir, TRANSLATION_FILE_NAME)
    private val cacheFile = File(dictDir, CACHE_FILE_NAME)

    private val _state = MutableStateFlow(readStateFromDisk())
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    suspend fun warmUp() {
        if (!state.value.mainImported) return
        ensureLoaded()
    }

    suspend fun suggest(query: String, limit: Int = 12): List<TagSuggestion> {
        if (!state.value.mainImported) return emptyList()
        val isTranslationQuery = containsNonAsciiLetter(query)
        if (isTranslationQuery && !state.value.translationImported) return emptyList()

        val normalizedQuery = normalizeQuery(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val data = ensureLoaded() ?: return emptyList()

        return withContext(Dispatchers.Default) {
            if (isTranslationQuery) {
                suggestByTranslation(data, normalizeTranslation(query), limit)
            } else {
                suggestByEnglish(data, normalizedQuery, limit)
            }
        }
    }

    suspend fun importMainCsv(uri: Uri, displayName: String?): ImportResult = withContext(Dispatchers.IO) {
        runCatching {
            val lineCount = copyUriToFile(uri, mainFile) { cells ->
                cells.getOrNull(0)?.trim()?.removePrefix("\uFEFF").isNullOrEmpty().not()
            }
            if (lineCount == 0) {
                mainFile.delete()
                return@withContext ImportResult.Error("empty")
            }
            invalidate()
            prefs.edit {
                putString(KEY_MAIN_NAME, displayName ?: MAIN_FILE_NAME)
                putInt(KEY_MAIN_LINES, lineCount)
            }
            _state.value = readStateFromDisk()
            ImportResult.Success(lineCount)
        }.getOrElse {
            mainFile.delete()
            ImportResult.Error(it.message ?: "unknown")
        }
    }

    suspend fun importTranslationCsv(uri: Uri, displayName: String?): ImportResult = withContext(Dispatchers.IO) {
        runCatching {
            val lineCount = copyUriToFile(uri, translationFile) { cells ->
                if (cells.size < 2) return@copyUriToFile false
                val key = cells[0].trim().removePrefix("\uFEFF")
                if (key.isEmpty()) return@copyUriToFile false
                extractTranslation(cells) != null
            }
            if (lineCount == 0) {
                translationFile.delete()
                return@withContext ImportResult.Error("empty")
            }
            invalidate()
            prefs.edit {
                putString(KEY_TRANSLATION_NAME, displayName ?: TRANSLATION_FILE_NAME)
                putInt(KEY_TRANSLATION_LINES, lineCount)
            }
            _state.value = readStateFromDisk()
            ImportResult.Success(lineCount)
        }.getOrElse {
            translationFile.delete()
            ImportResult.Error(it.message ?: "unknown")
        }
    }

    fun clearMainCsv() {
        mainFile.delete()
        prefs.edit {
            remove(KEY_MAIN_NAME)
            remove(KEY_MAIN_LINES)
        }
        invalidate()
        _state.value = readStateFromDisk()
    }

    fun clearTranslationCsv() {
        translationFile.delete()
        prefs.edit {
            remove(KEY_TRANSLATION_NAME)
            remove(KEY_TRANSLATION_LINES)
        }
        invalidate()
        _state.value = readStateFromDisk()
    }

    private fun invalidate() {
        loadedData = null
        if (cacheFile.exists()) cacheFile.delete()
    }

    private fun readStateFromDisk(): DictionaryState {
        val mainImported = mainFile.exists() && mainFile.length() > 0
        val translationImported = translationFile.exists() && translationFile.length() > 0
        return DictionaryState(
            mainImported = mainImported,
            mainFileName = if (mainImported) prefs.getString(KEY_MAIN_NAME, null) else null,
            mainEntryCount = if (mainImported) prefs.getInt(KEY_MAIN_LINES, 0) else 0,
            translationImported = translationImported,
            translationFileName = if (translationImported) {
                prefs.getString(
                    KEY_TRANSLATION_NAME,
                    null,
                )
            } else {
                null
            },
            translationEntryCount = if (translationImported) {
                prefs.getInt(
                    KEY_TRANSLATION_LINES,
                    0,
                )
            } else {
                0
            },
        )
    }

    private suspend fun ensureLoaded(): TagData? {
        loadedData?.let { return it }

        return loadMutex.withLock {
            loadedData?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) { loadData() }
            loadedData = loaded
            loaded
        }
    }

    private fun loadData(): TagData? {
        if (!mainFile.exists()) return null
        val cached = loadEntriesFromCache()
        val entries: List<TagEntry>
        val englishBitmaps: LongArray
        if (cached != null) {
            entries = cached.first
            englishBitmaps = cached.second
        } else {
            val parsed = parseCsvEntries() ?: return null
            englishBitmaps = computeEnglishBitmaps(parsed)
            saveEntriesToCache(parsed, englishBitmaps)
            entries = parsed
        }
        return buildIndexes(entries, englishBitmaps)
    }

    // One presence bitmap per entry, covering the english name and every alias,
    // so the pre-filter can reject an entry for both fields at once.
    private fun computeEnglishBitmaps(entries: List<TagEntry>): LongArray {
        val bitmaps = LongArray(entries.size * CharBitmap.WORDS)
        for (i in entries.indices) {
            val offset = i * CharBitmap.WORDS
            val entry = entries[i]
            CharBitmap.addInto(bitmaps, offset, entry.normalizedEnglish)
            for (alias in entry.normalizedAliases) CharBitmap.addInto(bitmaps, offset, alias)
        }
        return bitmaps
    }

    private fun parseCsvEntries(): List<TagEntry>? {
        if (!mainFile.exists()) return null
        val translationMap = if (translationFile.exists()) loadTranslationMap() else emptyMap()
        val estimated = prefs.getInt(KEY_MAIN_LINES, 0).coerceAtLeast(16)
        val entries = ArrayList<TagEntry>(estimated)
        val englishSet = HashSet<String>(estimated * 2)

        mainFile.inputStream().use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val cells = parseCsvLine(line)
                    if (cells.isEmpty()) return@forEach

                    val english = cells.getOrNull(0)?.trim()?.removePrefix("\uFEFF").orEmpty()
                    if (english.isEmpty()) return@forEach
                    if (!englishSet.add(english)) return@forEach

                    val category = cells.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val postCount = cells.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    val aliases = cells.getOrNull(3)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()

                    val translation = translationMap[english]
                    entries += TagEntry(
                        english = english,
                        translation = translation,
                        category = category,
                        postCount = postCount,
                        aliases = aliases,
                        normalizedEnglish = normalizeQuery(english),
                        normalizedAliases = aliases.map(::normalizeQuery)
                            .filter { it.isNotEmpty() },
                        normalizedTranslation = translation?.let(::normalizeTranslation),
                    )
                }
            }
        }
        return entries
    }

    private fun buildIndexes(entries: List<TagEntry>, englishBitmaps: LongArray): TagData {
        // The head buckets are only consulted by the edit-distance correction
        // fallback; fuzzy matching itself scans the full entry list because a
        // query char may land anywhere in the target.
        val englishByHead = HashMap<Char, MutableList<TagEntry>>(64)
        val aliasByHead = HashMap<Char, MutableList<AliasRef>>(64)
        val translationEntries = ArrayList<TagEntry>(entries.size)

        for (entry in entries) {
            entry.normalizedEnglish.firstOrNull()?.let { head ->
                englishByHead.getOrPut(head) { mutableListOf() } += entry
            }
            for (alias in entry.normalizedAliases) {
                alias.firstOrNull()?.let { head ->
                    aliasByHead.getOrPut(head) { mutableListOf() } += AliasRef(entry, alias)
                }
            }
            if (!entry.normalizedTranslation.isNullOrEmpty()) {
                translationEntries += entry
            }
        }

        // Translation bitmaps are derived here rather than cached: the set is a
        // subset of all entries and recomputing them from the (already cached)
        // normalized translations costs only a couple of milliseconds.
        val translationBitmaps = LongArray(translationEntries.size * CharBitmap.WORDS)
        for (i in translationEntries.indices) {
            translationEntries[i].normalizedTranslation?.let { normalized ->
                CharBitmap.addInto(translationBitmaps, i * CharBitmap.WORDS, normalized)
            }
        }

        return TagData(
            entries = entries,
            englishByHead = englishByHead,
            aliasByHead = aliasByHead,
            translationEntries = translationEntries,
            englishBitmaps = englishBitmaps,
            translationBitmaps = translationBitmaps,
        )
    }

    private fun loadTranslationMap(): Map<String, String> {
        val estimated = prefs.getInt(KEY_TRANSLATION_LINES, 0).coerceAtLeast(16)
        val map = HashMap<String, String>(estimated * 2)
        translationFile.inputStream().use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val cells = parseCsvLine(line)
                    if (cells.size < 2) return@forEach
                    val english = cells[0].trim().removePrefix("\uFEFF")
                    if (english.isEmpty()) return@forEach
                    val translation = extractTranslation(cells) ?: return@forEach
                    map[english] = translation
                }
            }
        }
        return map
    }

    private fun loadEntriesFromCache(): Pair<List<TagEntry>, LongArray>? {
        if (!cacheFile.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(cacheFile.inputStream())).use { input ->
                if (input.readInt() != CACHE_MAGIC) return@use null
                if (input.readInt() != CACHE_VERSION) return@use null
                val cachedMainSize = input.readLong()
                val cachedMainMtime = input.readLong()
                val cachedTransSize = input.readLong()
                val cachedTransMtime = input.readLong()
                val currentMainSize = mainFile.length()
                val currentMainMtime = mainFile.lastModified()
                val currentTransSize =
                    if (translationFile.exists()) translationFile.length() else 0L
                val currentTransMtime =
                    if (translationFile.exists()) translationFile.lastModified() else 0L
                if (cachedMainSize != currentMainSize ||
                    cachedMainMtime != currentMainMtime ||
                    cachedTransSize != currentTransSize ||
                    cachedTransMtime != currentTransMtime
                ) {
                    return@use null
                }
                val count = input.readInt()
                if (count < 0) return@use null
                val list = ArrayList<TagEntry>(count)
                repeat(count) {
                    val english = input.readUTF()
                    val translation = if (input.readBoolean()) input.readUTF() else null
                    val category = input.readInt()
                    val postCount = input.readInt()
                    val aliasCount = input.readInt()
                    val aliases =
                        if (aliasCount == 0) {
                            emptyList()
                        } else {
                            ArrayList<String>(aliasCount).also {
                                repeat(aliasCount) { _ -> it += input.readUTF() }
                            }
                        }
                    val normalizedEnglish = input.readUTF()
                    val normalizedAliasCount = input.readInt()
                    val normalizedAliases =
                        if (normalizedAliasCount == 0) {
                            emptyList()
                        } else {
                            ArrayList<String>(
                                normalizedAliasCount,
                            ).also {
                                repeat(normalizedAliasCount) { _ -> it += input.readUTF() }
                            }
                        }
                    val normalizedTranslation = if (input.readBoolean()) input.readUTF() else null
                    list += TagEntry(
                        english = english,
                        translation = translation,
                        category = category,
                        postCount = postCount,
                        aliases = aliases,
                        normalizedEnglish = normalizedEnglish,
                        normalizedAliases = normalizedAliases,
                        normalizedTranslation = normalizedTranslation,
                    )
                }
                val bitmaps = LongArray(count * CharBitmap.WORDS)
                for (k in bitmaps.indices) bitmaps[k] = input.readLong()
                list to bitmaps
            }
        }.getOrElse {
            cacheFile.delete()
            null
        }
    }

    private fun saveEntriesToCache(entries: List<TagEntry>, englishBitmaps: LongArray) {
        runCatching {
            DataOutputStream(BufferedOutputStream(cacheFile.outputStream())).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeLong(mainFile.length())
                output.writeLong(mainFile.lastModified())
                output.writeLong(if (translationFile.exists()) translationFile.length() else 0L)
                output.writeLong(if (translationFile.exists()) translationFile.lastModified() else 0L)
                output.writeInt(entries.size)
                for (entry in entries) {
                    output.writeUTF(entry.english)
                    val translation = entry.translation
                    if (translation != null) {
                        output.writeBoolean(true)
                        output.writeUTF(translation)
                    } else {
                        output.writeBoolean(false)
                    }
                    output.writeInt(entry.category)
                    output.writeInt(entry.postCount)
                    output.writeInt(entry.aliases.size)
                    for (a in entry.aliases) output.writeUTF(a)
                    output.writeUTF(entry.normalizedEnglish)
                    output.writeInt(entry.normalizedAliases.size)
                    for (a in entry.normalizedAliases) output.writeUTF(a)
                    val normTr = entry.normalizedTranslation
                    if (normTr != null) {
                        output.writeBoolean(true)
                        output.writeUTF(normTr)
                    } else {
                        output.writeBoolean(false)
                    }
                }
                for (value in englishBitmaps) output.writeLong(value)
            }
        }.onFailure {
            cacheFile.delete()
        }
    }

    private fun extractTranslation(cells: List<String>): String? {
        for (i in 1 until cells.size) {
            val raw = cells[i].trim().removePrefix("\uFEFF")
            if (raw.isEmpty()) continue
            if (raw.toIntOrNull() != null) continue
            if (raw.toDoubleOrNull() != null) continue
            return raw
        }
        return null
    }

    private fun suggestByTranslation(data: TagData, normalizedQuery: String, limit: Int): List<TagSuggestion> {
        if (normalizedQuery.isEmpty() || limit <= 0) return emptyList()
        val pattern = normalizedQuery.toCharArray()
        val queryBitmap = CharBitmap.of(normalizedQuery)
        val q0 = queryBitmap[0]
        val q1 = queryBitmap[1]

        val entries = data.translationEntries
        val bitmaps = data.translationBitmaps
        val topK = TopKLongs(limit)
        for (i in entries.indices) {
            if (!CharBitmap.contains(bitmaps, i * CharBitmap.WORDS, q0, q1)) continue
            val normalized = entries[i].normalizedTranslation ?: continue
            val fuzzyScore = FuzzyMatcher.score(pattern, normalized)
            if (fuzzyScore == FuzzyMatcher.NO_MATCH) continue
            topK.offer(fuzzyScore + popularityBonus(entries[i].postCount), i)
        }

        val results = ArrayList<TagSuggestion>(limit)
        topK.forEach { score, index ->
            val entry = entries[index]
            results += TagSuggestion(
                replacementTag = entry.english,
                primaryText = entry.english,
                secondaryText = entry.translation,
                matchType = TagMatchType.Translation,
                category = entry.category,
                postCount = entry.postCount,
                score = score,
            )
        }

        return results
            .sortedWith(compareByDescending<TagSuggestion> { it.score }.thenByDescending { it.postCount })
            .take(limit)
    }

    private fun suggestByEnglish(data: TagData, normalizedQuery: String, limit: Int): List<TagSuggestion> {
        if (normalizedQuery.isEmpty() || limit <= 0) return emptyList()
        val pattern = normalizedQuery.toCharArray()
        val queryBitmap = CharBitmap.of(normalizedQuery)
        val q0 = queryBitmap[0]
        val q1 = queryBitmap[1]

        // The bitmap pre-filter rejects entries that cannot contain every query
        // char before the O(len) subsequence scan; the bounded top-K heap keeps
        // only the best [limit] so short queries don't build thousands of rows.
        val entries = data.entries
        val bitmaps = data.englishBitmaps
        val topK = TopKLongs(limit)
        for (i in entries.indices) {
            if (!CharBitmap.contains(bitmaps, i * CharBitmap.WORDS, q0, q1)) continue
            val entry = entries[i]
            var best = FuzzyMatcher.score(pattern, entry.normalizedEnglish)
            for (alias in entry.normalizedAliases) {
                val aliasScore = FuzzyMatcher.score(pattern, alias)
                if (aliasScore > best) best = aliasScore
            }
            if (best == FuzzyMatcher.NO_MATCH) continue
            topK.offer(best + popularityBonus(entry.postCount), i)
        }

        val results = ArrayList<TagSuggestion>(limit + limit)
        val matched = HashSet<String>()
        topK.forEach { score, index ->
            val entry = entries[index]
            results += buildEnglishSuggestion(entry, pattern, score)
            matched += entry.english
        }

        // Subsequence matching cannot catch transposed/substituted letters, so
        // when fuzzy hits are scarce we top up with edit-distance corrections.
        if (results.size < limit) {
            appendCorrections(data, normalizedQuery, matched, results)
        }

        return results
            .sortedWith(compareByDescending<TagSuggestion> { it.score }.thenByDescending { it.postCount })
            .take(limit)
    }

    // Re-derives which field (name vs alias) drove the score for one of the few
    // top-K survivors, to label the row and choose its secondary text.
    private fun buildEnglishSuggestion(entry: TagEntry, pattern: CharArray, score: Int): TagSuggestion {
        val englishScore = FuzzyMatcher.score(pattern, entry.normalizedEnglish)
        var aliasScore = FuzzyMatcher.NO_MATCH
        var aliasValue: String? = null
        for (alias in entry.normalizedAliases) {
            val candidateScore = FuzzyMatcher.score(pattern, alias)
            if (candidateScore > aliasScore) {
                aliasScore = candidateScore
                aliasValue = alias
            }
        }
        return if (englishScore >= aliasScore) {
            buildSuggestion(entry, TagMatchType.Prefix, score)
        } else {
            buildSuggestion(entry, TagMatchType.Alias, score, aliasValue)
        }
    }

    private fun appendCorrections(
        data: TagData,
        normalizedQuery: String,
        matched: Set<String>,
        out: MutableList<TagSuggestion>,
    ) {
        val head = normalizedQuery.first()
        val threshold = correctionThreshold(normalizedQuery.length)
        val candidates = HashSet<TagEntry>()
        candidates += data.englishByHead[head].orEmpty()
        for (ref in data.aliasByHead[head].orEmpty()) candidates += ref.entry

        for (entry in candidates) {
            if (entry.english in matched) continue

            val englishDistance =
                if (abs(entry.normalizedEnglish.length - normalizedQuery.length) <= threshold &&
                    entry.normalizedEnglish.firstOrNull() == head
                ) {
                    damerauLevenshtein(normalizedQuery, entry.normalizedEnglish, threshold)
                } else {
                    threshold + 1
                }
            var bestDistance = englishDistance
            var matchedAlias: String? = null

            if (bestDistance > threshold) {
                for (aliasValue in entry.normalizedAliases) {
                    if (abs(aliasValue.length - normalizedQuery.length) > threshold) continue
                    if (aliasValue.firstOrNull() != head) continue
                    val aliasDistance = damerauLevenshtein(normalizedQuery, aliasValue, threshold)
                    if (aliasDistance < bestDistance) {
                        bestDistance = aliasDistance
                        matchedAlias = aliasValue
                    }
                }
            }

            if (bestDistance <= threshold) {
                out += buildSuggestion(
                    entry,
                    TagMatchType.Correction,
                    CORRECTION_BASE - bestDistance * CORRECTION_DISTANCE_PENALTY + popularityBonus(entry.postCount),
                    matchedAlias,
                )
            }
        }
    }

    private fun buildSuggestion(
        entry: TagEntry,
        matchType: TagMatchType,
        score: Int,
        aliasValue: String? = null,
    ): TagSuggestion {
        val secondary = when (matchType) {
            TagMatchType.Alias -> aliasValue?.let(::tagUnderscoresToSpaces)
            else -> entry.translation
        }
        return TagSuggestion(
            replacementTag = entry.english,
            primaryText = entry.english,
            secondaryText = secondary,
            matchType = matchType,
            category = entry.category,
            postCount = entry.postCount,
            score = score,
        )
    }

    private fun copyUriToFile(uri: Uri, target: File, isValidRow: (List<String>) -> Boolean): Int {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open uri")
        var validRows = 0
        input.use { stream ->
            target.outputStream().use { out ->
                val reader = BufferedReader(InputStreamReader(stream))
                val writer = out.bufferedWriter()
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEachLine
                    val cells = parseCsvLine(line)
                    if (!isValidRow(cells)) return@forEachLine
                    writer.write(rawLine)
                    writer.newLine()
                    validRows++
                }
                writer.flush()
            }
        }
        return validRows
    }

    // Popularity bonus stays on the same scale as fuzzy scores: it breaks ties
    // between similar-quality matches without letting a popular tag outrank a
    // clearly better match on a less popular one.
    private fun popularityBonus(postCount: Int): Int = when {
        postCount >= 1_000_000 -> 30
        postCount >= 100_000 -> 22
        postCount >= 10_000 -> 15
        postCount >= 1_000 -> 9
        postCount >= 100 -> 4
        else -> 0
    }

    private fun correctionThreshold(length: Int): Int = when {
        length <= 4 -> 1
        length <= 8 -> 2
        else -> 3
    }

    // Plain holder (never compared), so the LongArray fields don't drag in
    // reference-based data-class equals/hashCode.
    private class TagData(
        val entries: List<TagEntry>,
        val englishByHead: Map<Char, List<TagEntry>>,
        val aliasByHead: Map<Char, List<AliasRef>>,
        val translationEntries: List<TagEntry>,
        val englishBitmaps: LongArray,
        val translationBitmaps: LongArray,
    )

    private data class AliasRef(val entry: TagEntry, val alias: String)

    companion object {
        private const val PREFS_NAME = "tag_autocomplete_prefs"
        private const val DICT_DIR = "tagcomplete"
        private const val MAIN_FILE_NAME = "main.csv"
        private const val TRANSLATION_FILE_NAME = "translation.csv"
        private const val CACHE_FILE_NAME = "dict.bin"
        private const val CACHE_MAGIC = 0x54444333 // "TDC3"
        private const val CACHE_VERSION = 3

        // Edit-distance corrections are a fallback below genuine fuzzy hits but
        // above sparse, heavily-gapped ones, so an obvious typo fix still surfaces.
        private const val CORRECTION_BASE = 60
        private const val CORRECTION_DISTANCE_PENALTY = 25
        private const val KEY_MAIN_NAME = "main_csv_name"
        private const val KEY_MAIN_LINES = "main_csv_lines"
        private const val KEY_TRANSLATION_NAME = "translation_csv_name"
        private const val KEY_TRANSLATION_LINES = "translation_csv_lines"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: TagAutocompleteRepository? = null

        fun getInstance(context: Context): TagAutocompleteRepository = instance ?: synchronized(this) {
            instance ?: TagAutocompleteRepository(context.applicationContext).also {
                instance = it
            }
        }

        fun extractActiveTag(text: String, selection: Int): ActiveTagContext? {
            if (selection <= 0 || selection > text.length) return null
            val segmentStart = text.lastIndexOf(',', startIndex = selection - 1).let {
                if (it == -1) 0 else it + 1
            }
            val segmentEnd = text.indexOf(',', startIndex = selection).let {
                if (it == -1) text.length else it
            }
            var trimmedStart = segmentStart
            while (trimmedStart < selection && text[trimmedStart].isWhitespace()) trimmedStart++
            val token = text.substring(trimmedStart, selection).trim()
            if (token.isEmpty()) return null
            return ActiveTagContext(
                token = token,
                trimmedStart = trimmedStart,
                trimmedEnd = selection,
                segmentEnd = segmentEnd,
            )
        }

        fun applySuggestion(text: String, selection: Int, suggestion: TagSuggestion): Pair<String, Int> {
            val context = extractActiveTag(text, selection) ?: return text to selection
            val prefix = text.substring(0, context.trimmedStart)
            val suffix = text.substring(context.segmentEnd)
            val separator = if (suffix.startsWith(",")) "" else ", "
            // Embeddings are matched verbatim by filename stem in PromptProcessor;
            // converting '_' to ' ' or escaping '()' would break the lookup.
            val core = if (suggestion.matchType == TagMatchType.Embedding) {
                suggestion.replacementTag
            } else {
                escapePromptParentheses(tagUnderscoresToSpaces(suggestion.replacementTag))
            }
            val replacement = core + separator
            val updated = prefix + replacement + suffix.trimStart()
            return updated to (prefix.length + replacement.length)
        }

        // The comma-delimited segment under the caret, with leading/trailing
        // whitespace stripped from its bounds. Unlike extractActiveTag (which
        // stops at the caret for completion) this spans the whole segment so the
        // toolbar actions operate on the full tag, weight wrapper included.
        private data class TagSegment(val start: Int, val end: Int, val content: String)

        private fun segmentBetween(text: String, rawStart: Int, rawEnd: Int): TagSegment? {
            var start = rawStart
            while (start < rawEnd && text[start].isWhitespace()) start++
            var end = rawEnd
            while (end > start && text[end - 1].isWhitespace()) end--
            if (start >= end) return null
            return TagSegment(start, end, text.substring(start, end))
        }

        private fun activeTagSegment(text: String, selection: Int): TagSegment? {
            if (selection < 0 || selection > text.length) return null
            val segmentStart = text.lastIndexOf(',', startIndex = selection - 1).let {
                if (it == -1) 0 else it + 1
            }
            val segmentEnd = text.indexOf(',', startIndex = selection).let {
                if (it == -1) text.length else it
            }
            return segmentBetween(text, segmentStart, segmentEnd)
        }

        // Like activeTagSegment, but when the caret sits in an empty slot (e.g.
        // right after the ", " that a completion just inserted) it falls back to
        // the previous, completed tag. This lets the weight/clear actions target
        // the tag the user just picked instead of doing nothing.
        private fun resolveTagSegment(text: String, selection: Int): TagSegment? {
            activeTagSegment(text, selection)?.let { return it }
            if (selection <= 0 || selection > text.length) return null
            val prevComma = text.lastIndexOf(',', startIndex = selection - 1)
            if (prevComma < 0) return null
            val start = text.lastIndexOf(',', startIndex = prevComma - 1).let {
                if (it == -1) 0 else it + 1
            }
            return segmentBetween(text, start, prevComma)
        }

        // Explicit "(tag:weight)" attention form, e.g. "(masterpiece:1.2)".
        private val explicitWeightRegex = Regex("""^\((.*):(-?\d+(?:\.\d+)?)\)$""")

        // True when `s` is fully enclosed by one matching open/close pair, honoring
        // backslash escapes so a tag's literal "\(...\)" is not mistaken for a
        // weighting group.
        private fun isBalancedWrap(s: String, open: Char, close: Char): Boolean {
            if (s.length < 2 || s.first() != open || s.last() != close) return false
            var depth = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    // A backslash escapes the next character, so skip the pair.
                    c == '\\' -> i++

                    c == open -> depth++

                    c == close -> {
                        depth--
                        if (depth == 0 && i != s.length - 1) return false
                    }
                }
                i++
            }
            return depth == 0
        }

        private fun roundToTenth(value: Double): Double = Math.round(value * 10.0) / 10.0

        // Resolves a tag segment into its bare inner text and an effective weight,
        // understanding the A1111 shorthands as well as the explicit form:
        //   "(tag)"   -> 1.1   "((tag))" -> 1.2   (each '()' layer is +0.1)
        //   "[tag]"   -> 0.9   "[[tag]]" -> 0.8   (each '[]' layer is -0.1)
        //   "(tag:n)" -> n
        // Layers are peeled outermost-first; an explicit weight short-circuits.
        private fun parseWeightedTag(content: String): Pair<String, Double> {
            var inner = content.trim()
            var delta = 0.0
            while (true) {
                val match = explicitWeightRegex.matchEntire(inner)
                if (match != null) {
                    val body = match.groupValues[1]
                    val weight = match.groupValues[2].toDoubleOrNull()
                    if (body.isNotEmpty() && weight != null) {
                        return body to roundToTenth(weight + delta)
                    }
                }
                when {
                    isBalancedWrap(inner, '(', ')') -> delta += 0.1
                    isBalancedWrap(inner, '[', ']') -> delta -= 0.1
                    else -> return inner to roundToTenth(1.0 + delta)
                }
                inner = inner.substring(1, inner.length - 1).trim()
            }
        }

        // One decimal place with a '.' separator regardless of locale, so the
        // prompt parser reads the weight back correctly.
        private fun formatTagWeight(weight: Double): String = String.format(Locale.US, "%.1f", weight)

        // Wraps (or rewraps) the active tag with an adjusted attention weight,
        // stepping by `delta`. A1111 shorthand wrappers are normalized to the
        // explicit form; when the weight lands back on 1.0 the wrapper is dropped
        // so the tag returns to its bare text.
        fun adjustActiveTagWeight(text: String, selection: Int, delta: Double): Pair<String, Int>? {
            val segment = resolveTagSegment(text, selection) ?: return null
            val (inner, weight) = parseWeightedTag(segment.content)
            val newWeight = roundToTenth(weight + delta)
            val replacement = if (newWeight == 1.0) inner else "($inner:${formatTagWeight(newWeight)})"
            val updated = text.substring(0, segment.start) + replacement + text.substring(segment.end)
            val lengthDelta = replacement.length - (segment.end - segment.start)
            // Keep the caret where it was: anchored to the tag's end when it sat
            // inside the tag, or shifted by the edit when it sat in a later slot.
            val newSelection = if (selection in segment.start..segment.end) {
                segment.start + replacement.length
            } else {
                (selection + lengthDelta).coerceIn(0, updated.length)
            }
            return updated to newSelection
        }

        // Removes the tag under the caret together with one adjacent comma. When
        // the caret instead sits in an empty slot (e.g. the ", " a just-added tag
        // created), it collapses that slot rather than reaching back and deleting
        // the previous, intact tag (which would be a destructive surprise).
        fun clearActiveTag(text: String, selection: Int): Pair<String, Int>? {
            val segment = activeTagSegment(text, selection)
            if (segment != null) {
                val commaBefore = text.lastIndexOf(',', startIndex = segment.start - 1)
                val commaAfter = text.indexOf(',', startIndex = segment.end)
                return when {
                    commaBefore >= 0 -> {
                        val prefix = text.substring(0, commaBefore)
                        val suffix = if (commaAfter >= 0) text.substring(commaAfter) else ""
                        (prefix + suffix) to prefix.length
                    }

                    commaAfter >= 0 -> text.substring(commaAfter + 1).trimStart() to 0

                    else -> "" to 0
                }
            }
            if (selection < 0 || selection > text.length) return null
            val prevComma = text.lastIndexOf(',', startIndex = selection - 1)
            val nextComma = text.indexOf(',', startIndex = selection)
            return when {
                prevComma >= 0 -> {
                    val end = if (nextComma >= 0) nextComma else text.length
                    text.removeRange(prevComma, end) to prevComma
                }

                nextComma >= 0 -> text.substring(nextComma + 1).trimStart() to 0

                else -> null
            }
        }

        // Inserts ", " right after the active tag and drops the caret into the
        // fresh, empty slot so the user can start typing the next tag.
        fun appendTagAfterActive(text: String, selection: Int): Pair<String, Int>? {
            val segment = activeTagSegment(text, selection) ?: return null
            val insertion = ", "
            val updated = text.substring(0, segment.end) + insertion + text.substring(segment.end)
            return updated to (segment.end + insertion.length)
        }
    }
}
