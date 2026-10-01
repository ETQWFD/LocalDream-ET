package io.github.xororz.localdream.data

import android.content.Context
import androidx.core.content.edit

// Ordered list of pinned model ids, persisted in SharedPreferences. Order is
// most-recently-pinned first; the model list places pinned entries ahead of the
// rest within each tab, preserving this order. Ids never contain a newline
// (they are directory names), so newline is a safe separator.
object PinnedModels {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY = "pinned_model_ids"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): List<String> = prefs(context).getString(KEY, "").orEmpty()
        .split("\n")
        .filter { it.isNotEmpty() }

    private fun save(context: Context, ids: List<String>) {
        prefs(context).edit { putString(KEY, ids.joinToString("\n")) }
    }

    fun isPinned(context: Context, id: String): Boolean = id in get(context)

    // Newly pinned ids go to the front (most recent first), keeping the given
    // order among them; already-pinned ids keep their place.
    fun pin(context: Context, ids: Collection<String>) {
        val current = get(context)
        val toAdd = ids.filter { it !in current }
        if (toAdd.isEmpty()) return
        save(context, toAdd + current)
    }

    fun unpin(context: Context, ids: Collection<String>) {
        val remove = ids.toSet()
        val current = get(context)
        val next = current.filter { it !in remove }
        if (next.size != current.size) save(context, next)
    }

    // Keep a pin across a model id change, preserving its position.
    fun rename(context: Context, oldId: String, newId: String) {
        val current = get(context)
        if (oldId !in current) return
        save(context, current.map { if (it == oldId) newId else it })
    }

    // Stable ordering: pinned entries first in pin order, then everything else
    // in its original order.
    fun sort(models: List<Model>, pinnedIds: List<String>): List<Model> {
        if (pinnedIds.isEmpty()) return models
        val rank = pinnedIds.withIndex().associate { (i, id) -> id to i }
        val (pinned, others) = models.partition { it.id in rank }
        return pinned.sortedBy { rank.getValue(it.id) } + others
    }
}
