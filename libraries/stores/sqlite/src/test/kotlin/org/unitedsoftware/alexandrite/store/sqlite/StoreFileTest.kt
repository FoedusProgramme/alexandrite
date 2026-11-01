package org.unitedsoftware.alexandrite.store.sqlite

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.testkit.testChat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

/** The store file that the JDBC store wrote at schema version 6. */
class StoreFileTest {
    @TempDir
    lateinit var directory: Path

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `a store file written by the JDBC store reads back as it did then`() {
        val fixture = Paths.get(checkNotNull(javaClass.getResource("/store-file/data")).toURI())
        fixture.copyToRecursively(directory, followLinks = false, overwrite = false)

        val reads = withStore(directory, MutableClock(WRITTEN_AT)) { reads() }

        assertEquals(golden(), reads)
    }

    private fun golden(): String =
        checkNotNull(javaClass.getResource("/store-file/reads.txt")) { "No reads of the store file." }.readText()
}

/** When the store file was written. */
internal val WRITTEN_AT = MutableClock().instant

internal val coder: AgentChatKey = AgentChatKey(AgentId("coder"), testChat("dev", thread = "7"))

/** Everything the store's ports read of what it holds, one read per line. */
internal suspend fun TestStore.reads(): String = buildList {
    val conversationIds = rows("SELECT id FROM conversations ORDER BY rowid").map {
        ConversationId(it.single() as String)
    }
    val turnIds = rows("SELECT id FROM turns ORDER BY rowid").map { TurnId(it.single() as String) }
    val entryIds = rows("SELECT id FROM entries ORDER BY id").map { EntryId((it.single() as Number).toLong()) }
    val mediaIds = rows("SELECT id FROM media ORDER BY rowid").map { MediaId(it.single() as String) }
    for (key in listOf(main, coder)) {
        add("current $key: ${conversations.current(key).id}")
        add("chats ${key.agent}: ${conversations.chats(key.agent)}")
    }
    add("heartbeat base $main: ${conversations.heartbeatBase(main).id}")
    for (id in conversationIds) {
        add("conversation $id: ${conversations.conversation(id)}")
        add("taint $id: ${conversations.taint(id)}")
        add("entries $id: ${transcripts.entries(id).map { it.record?.id }}")
        add("tail $id: ${transcripts.tail(id, 2).map { it.record?.id }}")
        add("entries after 3 $id: ${transcripts.entriesAfter(id, EntryId(3)).map { it.record?.id }}")
    }
    for (id in turnIds) {
        add("turn $id: ${conversations.turn(id)}")
        add("turn entries $id: ${transcripts.turnEntries(id).map { it.record?.id }}")
    }
    add("unended: ${conversations.unendedTurns().map { it.id }}")
    for (id in entryIds) add("entry $id: ${transcripts.entry(id)}")
    for (message in listOf(ChannelMessageRef(chat, "m-hello"), ChannelMessageRef(coder.chat, "m-fix"))) {
        add("message $message: ${transcripts.entries(message).map { it.record?.id }}")
    }
    for (query in listOf("rocket", "ro", "fix the build")) {
        val found = transcripts.search(query, setOf(main, coder), 10)
        add("search '$query': ${found.map { "${it.entry.record?.id} ${it.key} ${it.snippet}" }}")
    }
    for (id in mediaIds) {
        add("media $id: ${media.info(id)}")
        add("media bytes $id: ${media.read(id)?.let(::sha256Hex)}")
    }
    for (agent in listOf(null, AgentId.MAIN, coder.agent)) {
        for (address in listOf(chat, coder.chat)) {
            add("chat state $agent $address: ${chatStates.read("probe", "mode", agent, address)}")
        }
    }
    add("records: ${records.list("probe", "grants")}")
    add("record: ${records.get("probe", "grants", "b")}")
}.joinToString("\n", postfix = "\n")
