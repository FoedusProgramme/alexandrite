package org.unitedsoftware.alexandrite.store.sqlite

import org.unitedsoftware.alexandrite.sdk.store.searchText
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteException
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptCodec
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/** Adds the search text of [entry], stored as the row [id] of `entries`, to the full-text index. */
internal fun Tx.indexText(id: Long, entry: TranscriptEntry) {
    val text = entry.searchText()
    if (text.isNotBlank()) execute("INSERT INTO entry_grams (docid, grams) VALUES (?, ?)", id, grams(text))
}

internal fun Tx.unindexText(id: Long) {
    execute("DELETE FROM entry_grams WHERE docid = ?", id)
}

/** Adds every stored entry that this version reads to the full-text index. */
internal fun Tx.indexAllText() {
    var after = 0L
    while (true) {
        val batch = query("SELECT id, format, body FROM entries WHERE id > ? ORDER BY id LIMIT ?", after, BATCH) {
            Stored(getLong(1), getInt(2), getString(3))
        }
        for (stored in batch) decodeStoredEntry(stored.format, stored.body)?.let { indexText(stored.id, it) }
        after = batch.lastOrNull()?.id ?: return
    }
}

/** Drops the FTS5 index of version 6, also where this SQLite has no FTS5 to drop it with. */
internal fun Tx.dropFts5Index() {
    if (queryOne("SELECT 1 FROM sqlite_master WHERE name = 'entry_text'") { true } == null) return
    try {
        execute("DROP TABLE entry_text")
    } catch (e: SqliteException) {
        for (shadow in FTS5_SHADOW_TABLES) execute("DROP TABLE IF EXISTS entry_text_$shadow")
        execute("PRAGMA writable_schema = ON")
        execute("DELETE FROM sqlite_master WHERE type = 'table' AND name = 'entry_text'")
        execute("PRAGMA writable_schema = OFF")
    }
}

/**
 * The entry stored as [body] in [format], null when this version does not read it.
 *
 * [unreadable] hears why a body in a known format does not decode.
 */
internal fun decodeStoredEntry(
    format: Int,
    body: String,
    unreadable: (IllegalArgumentException) -> Unit = {},
): TranscriptEntry? {
    if (format > TranscriptCodec.FORMAT_VERSION) return null
    return try {
        TranscriptCodec.decode(body)
    } catch (e: IllegalArgumentException) {
        unreadable(e)
        null
    }
}

/**
 * The tokens of [text] in `entry_grams`, one at each character that is no whitespace: the folded characters from there
 * up to the third or to a whitespace.
 */
internal fun grams(text: String): String {
    val folded = fold(text)
    return buildString {
        for (start in folded.indices) {
            if (folded[start].isSpace()) continue
            if (isNotEmpty()) append(' ')
            var at = start
            while (at < folded.size && at < start + GRAM && !folded[at].isSpace()) spell(folded[at++])
        }
    }
}

/**
 * The `MATCH` query of `entry_grams` for the text that holds each of [words]: a word's trigrams in a row, or the tokens
 * that begin with a shorter word.
 */
internal fun gramQuery(words: List<String>): String = words.joinToString(" ") { word ->
    val folded = fold(word)
    if (folded.size < GRAM) {
        buildString {
            folded.forEach { spell(it) }
            append('*')
        }
    } else {
        (0..folded.size - GRAM).joinToString(" ", "\"", "\"") { start ->
            buildString { for (at in start until start + GRAM) spell(folded[at]) }
        }
    }
}

/** The BM25 weight of a row from its `matchinfo(entry_grams, 'pcnalx')`, the greater the better. */
internal fun bm25(matchinfo: ByteArray): Double {
    val info = ByteBuffer.wrap(matchinfo).order(ByteOrder.nativeOrder()).asIntBuffer()
    val phrases = info[0]
    val columns = info[1]
    val rows = info[2].toUInt().toDouble()
    val averageLength = info[3].toUInt().toDouble().coerceAtLeast(1.0)
    val length = info[3 + columns].toUInt().toDouble()
    val hits = 3 + 2 * columns
    return (0 until phrases).sumOf { phrase ->
        val inRow = info[hits + 3 * phrase * columns].toUInt().toDouble()
        val rowsWith = info[hits + 3 * phrase * columns + 2].toUInt().toDouble()
        val idf = ln((rows - rowsWith + 0.5) / (rowsWith + 0.5)).takeIf { it > 0 } ?: MIN_IDF
        idf * inRow * (K1 + 1) / (inRow + K1 * (1 - B + B * length / averageLength))
    }
}

/** The code points of [text] in lower case, with a lone surrogate as U+FFFD. */
private fun fold(text: String): IntArray = text.codePoints().map { c ->
    if (c in SURROGATES) REPLACEMENT else Character.toLowerCase(Character.toUpperCase(c))
}.toArray()

/** Whether this is a character that splits the words of a search. */
private fun Int.isSpace(): Boolean = this == ' '.code || this in '\t'.code..'\r'.code

/** Appends [c] in the token characters of FTS's simple tokenizer, spelling no two characters alike. */
private fun StringBuilder.spell(c: Int) {
    when (c) {
        'z'.code -> append("zz")
        in 'a'.code..'y'.code, in '0'.code..'9'.code -> append(c.toChar())
        in 0 until 0x80 -> append('z').append(HEX[c shr 4]).append(HEX[c and 0xF])
        else -> appendCodePoint(c)
    }
}

private class Stored(val id: Long, val format: Int, val body: String)

private const val BATCH = 500

private const val GRAM = 3

private const val HEX = "0123456789abcdef"

private const val REPLACEMENT = 0xFFFD

private val SURROGATES = Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code

private const val K1 = 1.2

private const val B = 0.75

/** The weight of a word that half the rows or more hold. */
private const val MIN_IDF = 1e-6

private val FTS5_SHADOW_TABLES = listOf("data", "idx", "content", "docsize", "config")
