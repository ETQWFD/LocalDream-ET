package io.github.xororz.localdream.chat

import org.json.JSONArray
import org.json.JSONObject

/** What a chat entry carries. Legacy entries only ever had text. */
enum class ChatKind {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    ;

    companion object {
        fun parse(raw: String?): ChatKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TEXT
    }
}

/**
 * One public-chat message.
 *
 * Wire format kept backward compatible with the messages written by et.167:
 * `{ "author": "...", "text": "...", "ts": 1699999999 }`. New fields are
 * additive, so an old client still renders the text line of a media post.
 */
data class ChatMessage(
    val author: String = "",
    val text: String = "",
    val ts: Long = 0L,
    val kind: ChatKind = ChatKind.TEXT,
    /** Canonical download URL (raw.githubusercontent). */
    val mediaUrl: String? = null,
    /** jsDelivr mirror, used first because it is reachable from mainland China. */
    val cdnUrl: String? = null,
    val durationSec: Int = 0,
    /** GitHub avatar URL captured at send time, so other users see the real face. */
    val avatar: String? = null,
) {
    /** Stable identity for de-duplication when two clients merge the same file. */
    val key: String get() = "$ts|$author|${kind.name}|${mediaUrl.orEmpty()}|$text"

    fun toJson(): JSONObject = JSONObject()
        .put("author", author)
        .put("text", text)
        .put("ts", ts)
        .put("kind", kind.name.lowercase())
        .apply {
            mediaUrl?.takeIf { it.isNotBlank() }?.let { put("media", it) }
            cdnUrl?.takeIf { it.isNotBlank() }?.let { put("cdn", it) }
            avatar?.takeIf { it.isNotBlank() }?.let { put("avatar", it) }
            if (durationSec > 0) put("dur", durationSec)
        }

    companion object {
        private val LEGACY_IMAGE = Regex("^\\[图片]\\s*(.+)$")

        fun fromJson(o: JSONObject): ChatMessage {
            val text = o.optString("text", "")
            val kindRaw = o.optString("kind", "")
            val media = o.optString("media", "").takeIf { it.isNotBlank() }
            val cdn = o.optString("cdn", "").takeIf { it.isNotBlank() }
            // et.167 wrote images as the literal text "[图片] content://…".
            val legacy = LEGACY_IMAGE.find(text)
            return if (media == null && legacy != null) {
                ChatMessage(
                    author = o.optString("author", "?"),
                    text = "",
                    ts = o.optLong("ts", 0L),
                    kind = ChatKind.IMAGE,
                    mediaUrl = legacy.groupValues[1],
                    durationSec = 0,
                    avatar = o.optString("avatar", "").takeIf { it.isNotBlank() },
                )
            } else {
                ChatMessage(
                    author = o.optString("author", "?"),
                    text = text,
                    ts = o.optLong("ts", 0L),
                    kind = if (media == null) ChatKind.TEXT else ChatKind.parse(kindRaw),
                    mediaUrl = media,
                    cdnUrl = cdn,
                    durationSec = o.optInt("dur", 0),
                    avatar = o.optString("avatar", "").takeIf { it.isNotBlank() },
                )
            }
        }

        fun listFromJson(raw: String): List<ChatMessage> = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<ChatMessage>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

/** Merge helpers, kept pure so they can be unit-tested without Android. */
object ChatMerge {
    /**
     * Append [new] unless an identical entry is already present, and keep the
     * list ordered oldest-first. Concurrent writers read-modify-write the same
     * file, so duplicates are expected and must be collapsed by the reader too.
     */
    fun append(existing: List<ChatMessage>, new: ChatMessage): List<ChatMessage> {
        val withoutDupes = dedupe(existing)
        if (withoutDupes.any { it.key == new.key }) return withoutDupes
        return (withoutDupes + new).sortedBy { it.ts }
    }

    fun dedupe(list: List<ChatMessage>): List<ChatMessage> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ChatMessage>(list.size)
        for (m in list.sortedBy { it.ts }) {
            if (seen.add(m.key)) out.add(m)
        }
        return out
    }

    /** Messages newest-last, trimmed to the newest [limit] entries. */
    fun latest(list: List<ChatMessage>, limit: Int = 300): List<ChatMessage> =
        dedupe(list).takeLast(limit.coerceAtLeast(1))
}

object ChatIdentity {
    /** True when this message was written by the signed-in user. */
    fun isMine(author: String?, me: String?): Boolean {
        if (author.isNullOrBlank() || me.isNullOrBlank()) return false
        return author.trim().equals(me.trim(), ignoreCase = true)
    }

    /** Display name fallback chain: login -> "匿名用户". */
    fun displayName(login: String?, displayName: String?): String =
        displayName?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() } ?: "匿名用户"
}


/** Moderation state published by the admin page: admins and timed mutes. */
data class ChatMute(val until: Long = 0L, val by: String = "", val reason: String = "")

data class ChatPolicy(
    val admins: Set<String> = emptySet(),
    val mutes: Map<String, ChatMute> = emptyMap(),
) {
    fun isAdmin(login: String?): Boolean =
        login != null && admins.any { it.equals(login, ignoreCase = true) }

    /** Remaining mute in seconds for [login] (0 = not muted). */
    fun muteRemaining(login: String?, now: Long = System.currentTimeMillis() / 1000L): Long {
        if (login == null) return 0L
        val m = mutes.entries.firstOrNull { it.key.equals(login, ignoreCase = true) }?.value
        return ((m?.until ?: 0L) - now).coerceAtLeast(0L)
    }
}

object ChatWindow {
    const val DAY_SECONDS = 24L * 60 * 60

    /**
     * Full history, newest last. The old build trimmed everything older than
     * 7 days, which made long-running shared chats look like they "lost" posts.
     * Cap only for rendering cost, never by date.
     */
    fun recent(list: List<ChatMessage>, now: Long = System.currentTimeMillis() / 1000L): List<ChatMessage> =
        list.sortedBy { it.ts }.takeLast(MAX_KEPT)

    private const val MAX_KEPT = 500
}
