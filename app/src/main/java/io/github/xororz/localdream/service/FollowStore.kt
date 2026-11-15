package io.github.xororz.localdream.service

import android.content.Context

/** 本地关注作者列表 + 每个作者上次看到的最新作品时间（用于新图提醒）。 */
object FollowStore {
    private fun sp(c: Context) = c.getSharedPreferences("gallery_follow_v1", Context.MODE_PRIVATE)

    fun all(c: Context): Set<String> =
        sp(c).getStringSet("authors", emptySet())?.toSet() ?: emptySet()

    fun isFollowing(c: Context, author: String) = all(c).contains(author)

    fun toggle(c: Context, author: String): Boolean {
        val cur = all(c).toMutableSet()
        val now = if (cur.contains(author)) { cur.remove(author); false } else { cur.add(author); true }
        sp(c).edit().putStringSet("authors", cur).apply()
        return now
    }

    fun lastSeen(c: Context, author: String) = sp(c).getLong("seen_$author", 0L)

    fun markSeen(c: Context, author: String, ts: Long) {
        if (ts > lastSeen(c, author)) sp(c).edit().putLong("seen_$author", ts).apply()
    }
}
