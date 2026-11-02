package org.unitedsoftware.alexandrite.store.sqlite

import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement

/** [driver], failing a statement that uses what SQLite or Android's build of it gained after SQLite 3.18. */
internal class FloorDriver(private val driver: SqliteDriver) : SqliteDriver {
    override fun open(path: String): SqliteConnection = Connection(driver.open(path))

    private class Connection(private val connection: SqliteConnection) : SqliteConnection by connection {
        override fun prepare(sql: String): SqliteStatement {
            newerSql(sql)?.let { throw AssertionError("The store's SQL uses $it: $sql") }
            return connection.prepare(sql)
        }
    }
}

/** What [sql] uses that SQLite 3.18 with FTS4 lacks, null when nothing. */
internal fun newerSql(sql: String): String? {
    val code = sql.replace(STRING_LITERAL, "''")
    return NEWER_SQL.entries.firstOrNull { (pattern) -> pattern.containsMatchIn(code) }?.value
}

private fun words(pattern: String) =
    Regex("\\b(?:$pattern)\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private fun calls(names: String) = words("(?:$names)(?=\\s*\\()")

private val NEWER_SQL: Map<Regex, String> = mapOf(
    words("TRUE|FALSE") to "boolean literals (3.23)",
    words("DO\\s+(?:UPDATE|NOTHING)") to "UPSERT (3.24)",
    Regex("\\)\\s*OVER\\b|\\bWINDOW\\s+\\w+\\s+AS\\b", RegexOption.IGNORE_CASE) to "window functions (3.25)",
    words("RENAME\\s+COLUMN") to "RENAME COLUMN (3.25)",
    words("VACUUM\\s+INTO") to "VACUUM INTO (3.27)",
    Regex("\\)\\s*FILTER\\s*\\(|\\bNULLS\\s+(?:FIRST|LAST)\\b", RegexOption.IGNORE_CASE) to
        "FILTER and NULLS FIRST (3.30)",
    words("GENERATED\\s+ALWAYS") to "generated columns (3.31)",
    calls("IIF") to "IIF (3.32)",
    words("UPDATE\\s(?:(?!\\bSELECT\\b).)*\\bFROM") to "UPDATE FROM (3.33)",
    words("RETURNING") to "RETURNING (3.35)",
    words("DROP\\s+COLUMN") to "DROP COLUMN (3.35)",
    words("MATERIALIZED") to "MATERIALIZED (3.35)",
    words("STRICT") to "STRICT tables (3.37)",
    calls("UNIXEPOCH|FORMAT|JSON\\w*") to "functions of 3.38",
    Regex("->") to "the JSON operators (3.38)",
    words("RIGHT\\s+(?:OUTER\\s+)?JOIN|FULL\\s+(?:OUTER\\s+)?JOIN|IS\\s+(?:NOT\\s+)?DISTINCT\\s+FROM") to
        "the joins and operators of 3.39",
    calls("UNHEX|OCTET_LENGTH|TIMEDIFF|STRING_AGG|CONCAT|CONCAT_WS") to "functions of 3.41 to 3.44",
    words("FTS5|TRIGRAM|BM25") to "FTS5, which Android's SQLite lacks",
)

private val STRING_LITERAL = Regex("'(?:[^']|'')*'")
