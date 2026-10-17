package ke.sokobei.config

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** MongoDB holds accounts and credentials (users, OTP codes, refresh tokens). All price data stays in Cassandra. */
object MongoModule {
    private val log = LoggerFactory.getLogger(MongoModule::class.java)

    const val USERS = "users"
    const val OTP_CODES = "otp_codes"
    const val REFRESH_TOKENS = "refresh_tokens"

    fun connect(cfg: MongoConfig): Pair<MongoClient, MongoDatabase> {
        val client = MongoClient.create(cfg.uri)
        return client to client.getDatabase(cfg.database)
    }

    /** Idempotent. Waits for MongoDB with backoff (up to [timeoutSeconds]), then ensures indexes. */
    fun prepare(db: MongoDatabase, timeoutSeconds: Long = 120) = runBlocking {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000
        var wait = 1_000L
        while (true) {
            try {
                db.runCommand(Document("ping", 1))
                break
            } catch (e: Exception) {
                if (System.nanoTime() + wait * 1_000_000 > deadline) throw IllegalStateException("MongoDB not reachable", e)
                log.warn("MongoDB not ready: {}. Retrying in {} ms", e.message?.take(120), wait)
                delay(wait); wait = (wait * 2).coerceAtMost(10_000)
            }
        }
        // One account per phone: replaces the Cassandra LWT.
        db.getCollection<Document>(USERS).createIndex(Indexes.ascending("phone"), IndexOptions().unique(true))
        // Expired documents are reaped by Mongo's TTL monitor (runs ~every 60s); reads also check expiry explicitly.
        db.getCollection<Document>(OTP_CODES).createIndex(Indexes.ascending("expires_at"), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
        db.getCollection<Document>(REFRESH_TOKENS).createIndex(Indexes.ascending("expires_at"), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
        db.getCollection<Document>(REFRESH_TOKENS).createIndex(Indexes.ascending("user_id"))
    }
}
