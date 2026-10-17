package ke.sokobei.it

import io.ktor.client.request.post
import ke.sokobei.shared.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthIT {
    @Test fun `otp login end to end with refresh and logout`() = withEnv(devMode = false) {
        val phone = "0712345678"
        assertEquals(200, client.post("/v1/auth/otp/request") { jsonBody(OtpRequest(phone)) }.status.value)
        assertEquals("+254712345678", sms.sent.last().first)
        val tokens: AuthTokens = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, sms.lastCode())) }.json()
        assertEquals(900, tokens.expiresIn)

        val me: UserDto = get("/v1/me", tokens.accessToken).json()
        assertTrue(me.phoneMasked.endsWith("678") && !me.phoneMasked.contains("71234"), me.phoneMasked)
        assertEquals(null, me.consentVersion)

        // a code works only once
        val reuse = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, sms.lastCode())) }
        assertEquals(401, reuse.status.value)

        val refreshed: AccessTokenResponse = client.post("/v1/auth/refresh") { jsonBody(RefreshRequest(tokens.refreshToken)) }.json()
        assertEquals(200, get("/v1/me", refreshed.accessToken).status.value)

        assertEquals(204, client.post("/v1/auth/logout") { jsonBody(RefreshRequest(tokens.refreshToken)) }.status.value)
        assertEquals(401, client.post("/v1/auth/refresh") { jsonBody(RefreshRequest(tokens.refreshToken)) }.status.value)
    }

    @Test fun `refresh token is stored hashed`() = withEnv {
        val l = login()
        val docs = mdocs("refresh_tokens")
        assertEquals(1, docs.size)
        assertNotEquals(l.tokens.refreshToken, docs[0].getString("_id"))
        assertEquals(64, docs[0].getString("_id").length)
    }

    @Test fun `five attempts per code then locked even for the right code`() = withEnv(devMode = false) {
        val phone = "0712345678"
        client.post("/v1/auth/otp/request") { jsonBody(OtpRequest(phone)) }
        val right = sms.lastCode()
        val wrong = if (right == "111111") "222222" else "111111"
        repeat(5) {
            val r = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, wrong)) }
            assertEquals(401, r.status.value)
        }
        val locked = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, right)) }
        assertEquals(429, locked.status.value)
        assertEquals("RATE_LIMITED", locked.json<ErrorEnvelope>().error.code)
    }

    @Test fun `fixed code 000000 is rejected when DEV_MODE is false`() = withEnv(devMode = false) {
        val phone = "0712345678"
        client.post("/v1/auth/otp/request") { jsonBody(OtpRequest(phone)) }
        val r = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, "000000")) }
        assertEquals(401, r.status.value)
        // also without ever requesting a code
        val r2 = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest("0722222222", "000000")) }
        assertEquals(401, r2.status.value)
        assertEquals(0, mcount("users"))
    }

    @Test fun `fixed code 000000 works in DEV_MODE`() = withEnv(devMode = true) {
        val r = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest("0712345678", "000000")) }
        assertEquals(200, r.status.value)
    }

    @Test fun `invalid phone numbers are rejected with the error envelope`() = withEnv {
        val r = client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("12345")) }
        assertEquals(400, r.status.value)
        val e = r.json<ErrorEnvelope>().error
        assertEquals("VALIDATION_FAILED", e.code)
        assertTrue("phone" in e.details)
    }

    @Test fun `otp requests are limited to 3 per 15 minutes per phone`() = withEnv(devMode = false) {
        repeat(3) { assertEquals(200, client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("0712345678")) }.status.value) }
        val limited = client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("+254712345678")) }
        assertEquals(429, limited.status.value)
        assertEquals("RATE_LIMITED", limited.json<ErrorEnvelope>().error.code)
        // other phones are unaffected
        assertEquals(200, client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("0799999999")) }.status.value)
        clock.advance(Duration.ofMinutes(16))
        assertEquals(200, client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("0712345678")) }.status.value)
    }

    @Test fun `phone formats map to one user, even when logging in concurrently`() = withEnv {
        val ids = coroutineScope {
            listOf("0712345678", "+254712345678", "254712345678", "0712 345 678").map { p ->
                async {
                    val t: AuthTokens = client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(p, "000000")) }.json()
                    get("/v1/me", t.accessToken).json<UserDto>().userId
                }
            }.awaitAll()
        }
        assertEquals(1, ids.toSet().size, ids.toString())
        assertEquals(1, mcount("users"))
    }

    @Test fun `consent is required and stored with version and timestamp`() = withEnv {
        val l = login(consent = false)
        val r = submit(l.token, req())
        assertEquals(403, r.status.value)
        assertEquals("CONSENT_REQUIRED", r.json<ErrorEnvelope>().error.code)
        assertEquals(400, client.post("/v1/me/consent") { bearer(l.token); jsonBody(ConsentRequest(99)) }.status.value)
        assertEquals(204, client.post("/v1/me/consent") { bearer(l.token); jsonBody(ConsentRequest(1)) }.status.value)
        val me: UserDto = get("/v1/me", l.token).json()
        assertEquals(1, me.consentVersion)
        val row = mdocs("users").single()
        assertEquals(1, row.getInteger("consent_version"))
        assertEquals(clock.instant(), row.getDate("consent_at").toInstant())
        assertEquals(201, submit(l.token, req()).status.value)
    }

    @Test fun `protected endpoints require a valid jwt`() = withEnv {
        assertEquals(401, get("/v1/me").status.value)
        assertEquals(401, get("/v1/me", "garbage").status.value)
        assertEquals(401, client.post("/v1/reports") { jsonBody(req()) }.status.value)
        val e = get("/v1/me").json<ErrorEnvelope>().error
        assertEquals("UNAUTHORIZED", e.code)
    }

    @Test fun `otp is stored with a ttl and hashed`() = withEnv(devMode = false) {
        client.post("/v1/auth/otp/request") { jsonBody(OtpRequest("0712345678")) }
        val doc = mdocs("otp_codes").single()
        val secondsLeft = (doc.getDate("expires_at").time - System.currentTimeMillis()) / 1000
        assertTrue(secondsLeft in 1..300, "expires in $secondsLeft s")
        assertTrue(doc.getString("code_hash").contains(":"), "salted hash expected")
        val ttlIndex = kotlinx.coroutines.runBlocking {
            mongo.getCollection<org.bson.Document>("otp_codes").listIndexes().toList().any { it.containsKey("expireAfterSeconds") }
        }
        assertTrue(ttlIndex, "otp_codes needs a TTL index")
    }
}
