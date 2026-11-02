package org.unitedsoftware.alexandrite.store.sqlite

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteSearchTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the entries where the words weigh most come first, and the newest of equal ones`() {
        withStore(directory) {
            val (long, dense, same, later) = append(
                message("rocket " + "filler words that say nothing at all ".repeat(20)),
                message("rocket rocket rocket"),
                message("the same rocket"),
                message("the same rocket", id = "m-later"),
            ).map { it.record!!.id.value }

            val found = transcripts.search("rocket", setOf(main), 10).map { it.entry.record!!.id.value }

            assertEquals(listOf(dense, later, same, long), found)
        }
    }

    @Test
    fun `a snippet is the part of a long text around the first match`() {
        withStore(directory) {
            append(message("${"before ".repeat(100)}the Rocket starts here${" after".repeat(100)}"))

            val snippet = transcripts.search("rocket", setOf(main), 1).single().snippet

            assertTrue(snippet.startsWith("…") && snippet.endsWith("…"), snippet)
            assertTrue("the Rocket starts here" in snippet, snippet)
            assertEquals(240, snippet.length)
        }
    }

    @Test
    fun `words of any script and length are found inside the text`() {
        withStore(directory) {
            val (chinese, ai) = append(message("我们明天部署火箭"), message("An AI wrote it")).map { it.record!!.id }

            suspend fun found(query: String) = transcripts.search(query, setOf(main), 10).map { it.entry.record!!.id }

            assertEquals(listOf(chinese), found("部署"))
            assertEquals(listOf(chinese), found("火箭 部署"))
            assertEquals(listOf(chinese), found("明天部署"))
            assertEquals(listOf(ai), found("ai wrote"))
            assertEquals(emptyList(), found("ai 部署"))
        }
    }

    @Test
    fun `the query's characters are taken as they are`() {
        withStore(directory) {
            val (percent) = append(message("100% done"), message("nothing"), message("a\\b")).map { it.record!!.id }

            suspend fun found(query: String) = transcripts.search(query, setOf(main), 10).map { it.entry.record!!.id }

            assertEquals(listOf(percent), found("%"))
            assertEquals(emptyList(), found("_"))
            assertEquals(1, found("\\").size)
            assertEquals(emptyList(), found("\"unbalanced"))
            assertEquals(emptyList(), found("done NEAR(x"))
            assertEquals(listOf(percent), found("DONE 100%"))
        }
    }

    @Test
    fun `a word is found only where it stands whole, in any case`() {
        withStore(directory) {
            val (foreign, split) = append(message("ÜBER Straße, zz z9 🚀go"), message("xabc bcdy")).map {
                it.record!!.id
            }

            suspend fun found(query: String) = transcripts.search(query, setOf(main), 10).map { it.entry.record!!.id }

            assertEquals(listOf(foreign), found("über straße,"))
            assertEquals(listOf(foreign), found("üB"))
            assertEquals(listOf(foreign), found("Z"))
            assertEquals(listOf(foreign), found("zz z9"))
            assertEquals(listOf(foreign), found("🚀GO"))
            assertEquals(emptyList(), found("zzz"))
            assertEquals(emptyList(), found(",z"))
            assertEquals(listOf(split), found("xabc bcdy"))
            assertEquals(emptyList(), found("abcd"))
        }
    }

    @Test
    fun `the index loses the entries of a deleted message`() {
        withStore(directory) {
            val (kept) = append(message("kept"), message("gone"))

            transcripts.deleteMessage(ChannelMessageRef(chat, "m-gone"))

            assertEquals(listOf(listOf<Any?>(kept.record!!.id.value)), rows("SELECT docid FROM entry_grams"))
        }
    }
}
