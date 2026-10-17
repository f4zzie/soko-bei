package ke.sokobei.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import ke.sokobei.config.AppConfig
import ke.sokobei.domain.Phone
import ke.sokobei.domain.ValidationException
import ke.sokobei.repository.OtpRepository
import ke.sokobei.repository.RefreshTokenRepository
import ke.sokobei.repository.UserRepository
import ke.sokobei.shared.AccessTokenResponse
import ke.sokobei.shared.AuthTokens
import ke.sokobei.sms.SmsSender
import ke.sokobei.time.BusinessCalendar
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TokenService(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    val verifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).build()!!

    fun issueAccess(userId: UUID): String = JWT.create()
        .withIssuer(config.jwtIssuer).withSubject(userId.toString())
        .withIssuedAt(Date()).withExpiresAt(Date(System.currentTimeMillis() + ACCESS_TTL_SECONDS * 1000L))
        .sign(algorithm)

    companion object {
        const val ACCESS_TTL_SECONDS = 15 * 60
    }
}

class AuthService(
    private val config: AppConfig,
    private val calendar: BusinessCalendar,
    private val tokens: TokenService,
    private val users: UserRepository,
    private val otps: OtpRepository,
    private val refresh: RefreshTokenRepository,
    private val sms: SmsSender,
    private val limiter: SlidingWindowLimiter,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val random = SecureRandom()
    private val phoneLocks = ConcurrentHashMap<String, Mutex>()

    private fun phoneOrThrow(raw: String): String =
        Phone.normalize(raw) ?: throw ValidationException(mapOf("phone" to "must be a valid Kenyan mobile number"))

    suspend fun requestOtp(rawPhone: String) {
        val phone = phoneOrThrow(rawPhone)
        if (!limiter.tryAcquire("otp:$phone", config.otpRequestsPer15Min, Duration.ofMinutes(15))) {
            throw ApiException.tooMany("Too many code requests. Try again later.")
        }
        val code = "%06d".format(random.nextInt(1_000_000))
        val salt = ByteArray(16).also(random::nextBytes)
        otps.put(phone, hashOtp(salt, phone, code))
        sms.send(phone, "Your Soko Bei code is $code. It expires in 5 minutes.")
        log.info("OTP issued for {}", Phone.mask(phone))
    }

    suspend fun verifyOtp(rawPhone: String, code: String): AuthTokens {
        val phone = phoneOrThrow(rawPhone)
        phoneLocks.computeIfAbsent(phone) { Mutex() }.withLock {
            if (config.devMode && code == DEV_CODE) {
                log.warn("DEV_MODE login with fixed code for {}", Phone.mask(phone))
            } else {
                val otp = otps.get(phone)
                val stored = otp?.codeHash ?: throw ApiException.unauthorized("invalid or expired code")
                if (otp.attempts >= MAX_ATTEMPTS) throw ApiException.tooMany("Too many attempts. Request a new code.")
                otps.setAttempts(phone, otp.attempts + 1)
                if (!matches(stored, phone, code)) throw ApiException.unauthorized("invalid or expired code")
            }
            otps.delete(phone)
        }
        val userId = users.claimPhone(phone, UUID.randomUUID(), calendar.now())
        return issueTokens(userId)
    }

    private suspend fun issueTokens(userId: UUID): AuthTokens {
        val raw = ByteArray(32).also(random::nextBytes)
        val refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val expires = calendar.now().plus(REFRESH_TTL)
        refresh.store(sha256(refreshToken), userId, expires)
        return AuthTokens(tokens.issueAccess(userId), refreshToken, TokenService.ACCESS_TTL_SECONDS)
    }

    suspend fun refresh(refreshToken: String): AccessTokenResponse {
        val (userId, expiresAt) = refresh.find(sha256(refreshToken)) ?: throw ApiException.unauthorized("invalid refresh token")
        if (!expiresAt.isAfter(calendar.now())) throw ApiException.unauthorized("refresh token expired")
        if (users.get(userId) == null) throw ApiException.unauthorized("account no longer exists")
        return AccessTokenResponse(tokens.issueAccess(userId), TokenService.ACCESS_TTL_SECONDS)
    }

    suspend fun logout(refreshToken: String) {
        val hash = sha256(refreshToken)
        refresh.find(hash)?.let { refresh.revoke(hash) }
    }

    private fun hashOtp(salt: ByteArray, phone: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(config.otpSecret.toByteArray(), "HmacSHA256")) }
        mac.update(salt)
        val h = mac.doFinal("$phone:$code".toByteArray())
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(h)
    }

    private fun matches(stored: String, phone: String, code: String): Boolean {
        val salt = Base64.getDecoder().decode(stored.substringBefore(':'))
        return MessageDigest.isEqual(hashOtp(salt, phone, code).toByteArray(), stored.toByteArray())
    }

    companion object {
        const val DEV_CODE = "000000"
        const val MAX_ATTEMPTS = 5
        val REFRESH_TTL: Duration = Duration.ofDays(30)
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

