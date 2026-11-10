package io.github.xororz.localdream.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelTest {
    private fun m(author: String, ts: Long, text: String = "hi") =
        ChatMessage(author = author, text = text, ts = ts)

    @Test
    fun appendKeepsOrderAndDropsDuplicate() {
        val existing = listOf(m("a", 300), m("b", 100))
        val added = ChatMerge.append(existing, m("a", 300))
        assertEquals(2, added.size)
        assertEquals(listOf(100L, 300L), added.map { it.ts })
        assertEquals(listOf("b", "a"), added.map { it.author })
    }

    @Test
    fun appendAddsNewerMessageAtTheEnd() {
        val out = ChatMerge.append(listOf(m("a", 100)), m("c", 400))
        assertEquals(2, out.size)
        assertEquals("c", out.last().author)
    }

    @Test
    fun latestTrimsToNewestEntries() {
        val list = (1..10).map { m("u$it", it.toLong()) }
        assertEquals(3, ChatMerge.latest(list, 3).size)
        assertEquals(listOf(8L, 9L, 10L), ChatMerge.latest(list, 3).map { it.ts })
    }

    @Test
    fun dedupeCollapsesIdenticalMessages() {
        val one = ChatMessage(author = "a", text = "x", ts = 5)
        val dup = ChatMessage(author = "a", text = "x", ts = 5)
        assertEquals(1, ChatMerge.dedupe(listOf(one, dup)).size)
    }

    @Test
    fun legacyImageLineIsParsedAsImageMessage() {
        val msg = ChatMessage.fromJson(
            JSONObject().put("author", "a").put("ts", 5)
                .put("text", "[图片] content://x/y.png"),
        )
        assertEquals(ChatKind.IMAGE, msg.kind)
        assertEquals("content://x/y.png", msg.mediaUrl)
    }

    @Test
    fun avatarRoundTripsThroughJson() {
        val original = ChatMessage(
            author = "a", text = "t", ts = 5,
            kind = ChatKind.TEXT, avatar = "https://avatars.githubusercontent.com/u/1",
        )
        val back = ChatMessage.fromJson(original.toJson())
        assertEquals(original.avatar, back.avatar)
        assertEquals(original.author, back.author)
        assertEquals(original.kind, back.kind)
    }

    @Test
    fun recentKeepsOnlySevenDays() {
        val now = 1_000_000L
        val list = listOf(
            m("old", now - 8 * ChatWindow.DAY_SECONDS),
            m("edge", now - 7 * ChatWindow.DAY_SECONDS),
            m("new", now - 60),
        )
        assertEquals(listOf("edge", "new"), ChatWindow.recent(list, now).map { it.author })
    }

    @Test
    fun policyRecognisesAdminsCaseInsensitively() {
        val policy = ChatPolicy(admins = setOf("ETQWFD"))
        assertTrue(policy.isAdmin("etqwfd"))
        assertFalse(policy.isAdmin("someone"))
        assertFalse(policy.isAdmin(null))
    }

    @Test
    fun muteRemainingCountsDownAndStopsAtZero() {
        val now = 1000L
        val policy = ChatPolicy(mutes = mapOf("bob" to ChatMute(until = 1600L, by = "ETQWFD")))
        assertEquals(600L, policy.muteRemaining("bob", now))
        assertEquals(600L, policy.muteRemaining("BOB", now))
        assertEquals(0L, policy.muteRemaining("bob", 2000L))
        assertEquals(0L, policy.muteRemaining("alice", now))
        assertEquals(0L, policy.muteRemaining(null, now))
    }

    @Test
    fun policyJsonParsesAdminsMutesAndFallsBackToOwner() {
        val raw = """{"admins":["bob"],"mutes":{"eve":{"until":1234,"by":"bob"},"old":99}}"""
        val parsed = ChatPolicyRepo.parse(raw)
        assertTrue(parsed.isAdmin("ETQWFD"))
        assertTrue(parsed.isAdmin("bob"))
        assertEquals(1234L, parsed.mutes["eve"]?.until)
        assertEquals(99L, parsed.mutes["old"]?.until)

        val broken = ChatPolicyRepo.parse("not json")
        assertTrue(broken.isAdmin("ETQWFD"))
        assertTrue(broken.mutes.isEmpty())
    }

    @Test
    fun muteRemainingHandlesNonPositiveUntil() {
        val policy = ChatPolicy(mutes = mapOf("bob" to ChatMute(until = 0L)))
        assertEquals(0L, policy.muteRemaining("bob", 100L))
    }
}
