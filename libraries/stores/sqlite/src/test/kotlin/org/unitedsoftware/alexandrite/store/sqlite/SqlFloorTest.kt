package org.unitedsoftware.alexandrite.store.sqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlFloorTest {
    @Test
    fun `the floor names the SQL that SQLite gained later, outside string literals`() {
        assertEquals("RETURNING (3.35)", newerSql("INSERT INTO t (v) VALUES (1) RETURNING id"))
        assertEquals("UPSERT (3.24)", newerSql("INSERT INTO t (v) VALUES (1) ON CONFLICT (v) DO UPDATE SET v = 2"))
        assertEquals("UPDATE FROM (3.33)", newerSql("UPDATE t SET v = s.v FROM s WHERE s.id = t.id"))
        assertEquals("window functions (3.25)", newerSql("SELECT row_number() OVER (ORDER BY v) FROM t"))
        assertEquals("functions of 3.38", newerSql("SELECT json_extract(v, '$.a') FROM t"))
        assertEquals("FTS5, which Android's SQLite lacks", newerSql("CREATE VIRTUAL TABLE f USING fts5(text)"))
        assertNull(newerSql("UPDATE t SET v = (SELECT format FROM s) WHERE v = 'returning true'"))
        assertNull(newerSql("SELECT DISTINCT sha256 FROM media WHERE id NOT IN (SELECT media_id FROM entry_media)"))
    }
}
