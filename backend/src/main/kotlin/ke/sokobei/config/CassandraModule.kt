package ke.sokobei.config

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.time.Duration

/** Builds Cassandra sessions: LOCAL_QUORUM everywhere, LOCAL_SERIAL for the single LWT. */
object CassandraModule {
    private val log = LoggerFactory.getLogger(CassandraModule::class.java)

    fun driverConfig() = DriverConfigLoader.programmaticBuilder()
        .withString(DefaultDriverOption.REQUEST_CONSISTENCY, "LOCAL_QUORUM")
        .withString(DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY, "LOCAL_SERIAL")
        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
        .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
        .build()

    fun connect(cfg: CassandraConfig, keyspace: String? = null): CqlSession {
        val builder = CqlSession.builder()
            .withLocalDatacenter(cfg.localDc)
            .withConfigLoader(driverConfig())
        cfg.contactPoints.forEach {
            val (host, port) = it.split(":").let { p -> p[0] to (p.getOrNull(1)?.toInt() ?: 9042) }
            builder.addContactPoint(InetSocketAddress.createUnresolved(host, port))
        }
        if (keyspace != null) builder.withKeyspace(keyspace)
        return builder.build()
    }

    /** Waits for Cassandra with exponential backoff (default max ~2 min), then returns a keyspace-less session. */
    fun connectWithRetry(cfg: CassandraConfig, keyspace: String? = null): CqlSession {
        val deadline = System.nanoTime() + Duration.ofSeconds(cfg.startupTimeoutSeconds).toNanos()
        var delayMs = 1_000L
        var attempt = 0
        while (true) {
            attempt++
            try {
                val session = connect(cfg, keyspace)
                session.execute("SELECT release_version FROM system.local")
                log.info("Connected to Cassandra after {} attempt(s)", attempt)
                return session
            } catch (e: Exception) {
                if (System.nanoTime() + delayMs * 1_000_000 > deadline) {
                    throw IllegalStateException("Cassandra not reachable after $attempt attempts", e)
                }
                log.warn("Cassandra not ready (attempt {}): {}. Retrying in {} ms", attempt, e.message, delayMs)
                Thread.sleep(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(10_000)
            }
        }
    }

    /** Connect, run migrations on an admin session, then return a session bound to the keyspace. */
    fun connectAndMigrate(cfg: CassandraConfig): CqlSession {
        connectWithRetry(cfg).use { admin -> MigrationRunner(admin, cfg).run() }
        return connectWithRetry(cfg, cfg.keyspace)
    }
}
