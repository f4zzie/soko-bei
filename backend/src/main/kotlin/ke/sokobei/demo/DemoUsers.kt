package ke.sokobei.demo

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.Updates.setOnInsert
import ke.sokobei.Services
import ke.sokobei.domain.Phone
import ke.sokobei.repository.UserRepository
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.util.Date
import java.util.UUID

object DemoUsers {
    suspend fun ensure(s: Services): List<UUID> {
        DemoCli.requireDev(s.config)
        val markers = s.mongo.getCollection<Document>("demo_large_users")
        val users = UserRepository(s.mongo)
        for (raw in DemoScenario.PHONES) {
            val phone = Phone.normalize(raw)!!
            check(markers.find(eq("_id", phone)).firstOrNull() != null || users.userIdByPhone(phone) == null) {
                "Synthetic demo phone range overlaps an existing unmarked account; refusing to adopt it"
            }
        }
        return DemoScenario.PHONES.map { raw ->
            val phone = Phone.normalize(raw)!!
            markers.updateOne(eq("_id", phone), setOnInsert("created_at", Date.from(s.calendar.now())), UpdateOptions().upsert(true))
            val marker = markers.find(eq("_id", phone)).firstOrNull()!!
            val userId = marker.getString("user_id")?.let(UUID::fromString) ?: run {
                val tokens = s.auth.verifyOtp(raw, "000000")
                val id = UUID.fromString(s.tokens.verifier.verify(tokens.accessToken).subject)
                markers.updateOne(eq("_id", phone), set("user_id", id.toString()))
                id
            }
            val user = users.get(userId) ?: error("A marked demo user was removed; refusing to recreate its report identity")
            check(user.phone == phone) { "Demo user marker does not match its account" }
            if (user.consentVersion != s.config.consentVersion) s.account.acceptConsent(userId, s.config.consentVersion)
            userId
        }
    }
}
