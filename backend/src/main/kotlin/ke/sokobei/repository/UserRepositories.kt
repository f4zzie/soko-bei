package ke.sokobei.repository

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import ke.sokobei.config.MongoModule
import ke.sokobei.domain.UserRecord
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.time.Instant
import java.util.Date
import java.util.UUID

class UserRepository(db: MongoDatabase) {
    private val users = db.getCollection<Document>(MongoModule.USERS)

    /**
     * Creates the account for [phone] if none exists. A unique index on `phone` makes this atomic
     * (this replaces the Cassandra LWT). Returns the winning user_id: ours, or the pre-existing one.
     */
    suspend fun claimPhone(phone: String, candidate: UUID, now: Instant): UUID {
        val doc = Document("_id", candidate.toString()).append("phone", phone).append("created_at", Date.from(now))
        return try {
            users.insertOne(doc)
            candidate
        } catch (e: MongoWriteException) {
            if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
            userIdByPhone(phone) ?: throw e
        }
    }

    suspend fun userIdByPhone(phone: String): UUID? =
        users.find(Filters.eq("phone", phone)).firstOrNull()?.getString("_id")?.let(UUID::fromString)

    suspend fun get(userId: UUID): UserRecord? = users.find(Filters.eq("_id", userId.toString())).firstOrNull()?.let {
        UserRecord(UUID.fromString(it.getString("_id")), it.getString("phone") ?: "", it.getString("display_name"),
            it.getInteger("consent_version"), it.getDate("consent_at")?.toInstant(),
            it.getDate("created_at")?.toInstant() ?: Instant.EPOCH)
    }

    suspend fun acceptConsent(userId: UUID, version: Int, at: Instant) {
        users.updateOne(Filters.eq("_id", userId.toString()),
            Updates.combine(Updates.set("consent_version", version), Updates.set("consent_at", Date.from(at))))
    }

    suspend fun delete(userId: UUID, @Suppress("UNUSED_PARAMETER") phone: String) {
        users.deleteOne(Filters.eq("_id", userId.toString()))
    }

    suspend fun count(): Long = users.countDocuments()
}

class OtpRepository(db: MongoDatabase) {
    private val col = db.getCollection<Document>(MongoModule.OTP_CODES)

    data class Otp(val codeHash: String?, val attempts: Int)

    /** Valid for 300 seconds of real time (TTL index + explicit check; never the injectable business clock). */
    suspend fun put(phone: String, codeHash: String) {
        val doc = Document("_id", phone).append("code_hash", codeHash).append("attempts", 0)
            .append("expires_at", Date(System.currentTimeMillis() + 300_000))
        col.replaceOne(Filters.eq("_id", phone), doc, ReplaceOptions().upsert(true))
    }

    suspend fun get(phone: String): Otp? {
        val d = col.find(Filters.eq("_id", phone)).firstOrNull() ?: return null
        if (d.getDate("expires_at").time <= System.currentTimeMillis()) return null
        return Otp(d.getString("code_hash"), d.getInteger("attempts") ?: 0)
    }

    suspend fun setAttempts(phone: String, attempts: Int) { col.updateOne(Filters.eq("_id", phone), Updates.set("attempts", attempts)) }
    suspend fun delete(phone: String) { col.deleteOne(Filters.eq("_id", phone)) }
}

class RefreshTokenRepository(db: MongoDatabase) {
    private val col = db.getCollection<Document>(MongoModule.REFRESH_TOKENS)

    /** [hash] is the SHA-256 of the token; the raw token is never stored. Expiry is enforced by the TTL index and by callers. */
    suspend fun store(hash: String, userId: UUID, expiresAt: Instant) {
        col.insertOne(Document("_id", hash).append("user_id", userId.toString()).append("expires_at", Date.from(expiresAt)))
    }

    suspend fun find(hash: String): Pair<UUID, Instant>? = col.find(Filters.eq("_id", hash)).firstOrNull()?.let {
        UUID.fromString(it.getString("user_id")) to it.getDate("expires_at").toInstant()
    }

    suspend fun revoke(hash: String) { col.deleteOne(Filters.eq("_id", hash)) }
    suspend fun revokeAllForUser(userId: UUID) { col.deleteMany(Filters.eq("user_id", userId.toString())) }
    suspend fun forUser(userId: UUID): List<String> = col.find(Filters.eq("user_id", userId.toString())).toList().map { it.getString("_id") }
}
