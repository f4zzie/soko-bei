package ke.sokobei.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CqlSplitterTest {
    @Test fun `splits statements`() = assertEquals(listOf("A 1", "B 2"), CqlSplitter.split("A 1;\nB 2;\n"))

    @Test fun `ignores semicolons in line and block comments`() {
        val sql = "-- hi; there\nCREATE TABLE t (a int); /* x; y */ INSERT INTO t (a) VALUES (1); // z; w\n"
        assertEquals(listOf("CREATE TABLE t (a int)", "INSERT INTO t (a) VALUES (1)"), CqlSplitter.split(sql))
    }

    @Test fun `keeps semicolons inside string literals`() =
        assertEquals(listOf("INSERT INTO t (a) VALUES ('x;y''z;--w')"), CqlSplitter.split("INSERT INTO t (a) VALUES ('x;y''z;--w');"))

    @Test fun `trailing statement without semicolon`() = assertEquals(listOf("A"), CqlSplitter.split("A"))

    @Test fun `real migration files split without empties`() {
        val migrations = MigrationRunner.loadClasspathMigrations()
        assertEquals(listOf(1, 2, 3, 4), migrations.map { it.version })
        migrations.forEach { m -> assertTrue(m.statements.all { it.isNotBlank() }) }
        assertEquals(16, migrations[0].statements.size) // 16 tables in V001
    }
}
