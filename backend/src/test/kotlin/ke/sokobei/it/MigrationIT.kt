package ke.sokobei.it

import ke.sokobei.config.CassandraModule
import ke.sokobei.config.MigrationChecksumException
import ke.sokobei.config.MigrationRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationIT {
    @Test fun `migrations apply from scratch, rerun is a no-op, seed is queryable`() {
        val cfg = TestCassandra.newConfig()
        CassandraModule.connectWithRetry(cfg).use { s ->
            try {
                assertEquals(listOf(1, 2, 3, 4), MigrationRunner(s, cfg).run())
                assertEquals(emptyList(), MigrationRunner(s, cfg).run())
                val rows = s.execute("SELECT count(*) FROM ${cfg.keyspace}.markets").one()!!.getLong(0)
                assertEquals(5, rows)
                assertEquals(10, s.execute("SELECT count(*) FROM ${cfg.keyspace}.commodities").one()!!.getLong(0))
                val units = s.execute("SELECT unit, kg_per_unit, verified FROM ${cfg.keyspace}.commodity_units WHERE commodity_id = 'tomatoes'").all()
                assertTrue(units.any { it.getString("unit") == "crate" && !it.getBoolean("verified") })
                val grades = s.execute("SELECT grades FROM ${cfg.keyspace}.commodities WHERE commodity_id = 'maize'").one()!!.getList("grades", String::class.java)
                assertEquals(listOf("standard"), grades)
            } finally { s.execute("DROP KEYSPACE IF EXISTS ${cfg.keyspace}") }
        }
    }

    @Test fun `tampered checksum blocks startup`() {
        val cfg = TestCassandra.newConfig()
        try {
            CassandraModule.connectAndMigrate(cfg).close()
            CassandraModule.connectWithRetry(cfg).use { s ->
                s.execute("UPDATE ${cfg.keyspace}.schema_migrations SET checksum = 'tampered' WHERE version = 1")
                assertFailsWith<MigrationChecksumException> { MigrationRunner(s, cfg).run() }
            }
            assertFailsWith<MigrationChecksumException> { CassandraModule.connectAndMigrate(cfg) }
        } finally {
            TestCassandra.admin().use { it.execute("DROP KEYSPACE IF EXISTS ${cfg.keyspace}") }
        }
    }
}
