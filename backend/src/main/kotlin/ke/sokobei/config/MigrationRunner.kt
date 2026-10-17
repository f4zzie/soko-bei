package ke.sokobei.config

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import org.slf4j.LoggerFactory
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant

class MigrationChecksumException(message: String) : IllegalStateException(message)

data class Migration(val version: Int, val name: String, val checksum: String, val statements: List<String>)

/** Splits CQL on `;` while ignoring semicolons inside comments and quoted strings. */
object CqlSplitter {
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '-' && next == '-' || c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) i++
                    i += 2
                }
                c == '\'' -> {
                    cur.append(c); i++
                    while (i < text.length) {
                        cur.append(text[i])
                        if (text[i] == '\'') {
                            if (text.getOrNull(i + 1) == '\'') { cur.append('\''); i += 2; continue }
                            break
                        }
                        i++
                    }
                    i++
                }
                c == '$' && next == '$' -> {
                    val end = text.indexOf("$$", i + 2).let { if (it < 0) text.length - 2 else it }
                    cur.append(text, i, end + 2); i = end + 2
                }
                c == ';' -> {
                    cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                    cur.clear(); i++
                }
                else -> { cur.append(c); i++ }
            }
        }
        cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }
}

class MigrationRunner(
    private val session: CqlSession,
    private val cfg: CassandraConfig,
    private val migrations: List<Migration> = loadClasspathMigrations(),
) {
    private val log = LoggerFactory.getLogger(MigrationRunner::class.java)
    private val ks = CqlIdentifier.fromCql(cfg.keyspace)

    init {
        require(cfg.keyspace.matches(Regex("[a-z][a-z0-9_]{0,47}"))) { "Invalid keyspace name '${cfg.keyspace}'" }
    }

    private fun stmt(cql: String): SimpleStatement = SimpleStatement.newInstance(cql).setKeyspace(ks)

    /** @return versions applied by this run (empty when everything was already applied). */
    fun run(): List<Int> {
        session.execute(
            "CREATE KEYSPACE IF NOT EXISTS ${cfg.keyspace} WITH replication = ${cfg.replicationCql()} AND durable_writes = true"
        )
        // Bootstrap: the runner must be able to record V001 before V001 itself has run.
        session.execute(
            stmt(
                "CREATE TABLE IF NOT EXISTS schema_migrations (version int PRIMARY KEY, name text, checksum text, applied_at timestamp)"
            )
        )
        val applied = session.execute(stmt("SELECT version, name, checksum FROM schema_migrations"))
            .associate { it.getInt("version") to it.getString("checksum") }

        val known = migrations.map { it.version }.toSet()
        applied.keys.filterNot { it in known }.forEach { log.warn("Applied migration V{} is not present on the classpath", it) }

        val done = mutableListOf<Int>()
        for (m in migrations.sortedBy { it.version }) {
            val recorded = applied[m.version]
            if (recorded != null) {
                if (recorded != m.checksum) {
                    throw MigrationChecksumException(
                        "Migration V${m.version} (${m.name}) was modified after being applied: recorded=$recorded current=${m.checksum}"
                    )
                }
                continue
            }
            log.info("Applying migration V{} {} ({} statements)", m.version, m.name, m.statements.size)
            m.statements.forEach { session.execute(stmt(it)) }
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)",
                    m.version, m.name, m.checksum, Instant.now(),
                ).setKeyspace(ks)
            )
            done += m.version
        }
        return done
    }

    companion object {
        private val NAME = Regex("V(\\d+)__(.+)\\.cql")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }

        fun parse(fileName: String, content: String): Migration {
            val m = NAME.matchEntire(fileName) ?: error("Bad migration file name: $fileName")
            return Migration(m.groupValues[1].toInt(), m.groupValues[2], sha256(content), CqlSplitter.split(content))
        }

        fun loadClasspathMigrations(loader: ClassLoader = MigrationRunner::class.java.classLoader): List<Migration> {
            val url = loader.getResource("cql") ?: error("cql/ resource directory missing")
            val names: List<String> = if (url.protocol == "jar") {
                (url.openConnection() as JarURLConnection).jarFile.entries().asSequence()
                    .map { it.name }.filter { it.startsWith("cql/V") && it.endsWith(".cql") }
                    .map { it.removePrefix("cql/") }.toList()
            } else {
                Files.list(Paths.get(url.toURI())).use { s -> s.map { it.fileName.toString() }.filter { NAME.matches(it) }.toList() }
            }
            return names.map { n -> parse(n, loader.getResourceAsStream("cql/$n")!!.readBytes().decodeToString()) }
                .sortedBy { it.version }
        }
    }
}
