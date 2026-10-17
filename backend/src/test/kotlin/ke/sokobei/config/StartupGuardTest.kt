package ke.sokobei.config

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import ke.sokobei.module
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StartupGuardTest {
    private val good = "g".repeat(32)
    private val safe = AppConfig(devMode = false, jwtSecret = good + "1", otpSecret = good + "2", adminToken = good + "3")

    private fun message(c: AppConfig) = assertFailsWith<IllegalStateException> { c.requireSafeForProduction() }.message!!

    @Test fun `safe production config starts`() = safe.requireSafeForProduction()
    @Test fun `empty admin token is allowed and disables the admin API`() = safe.copy(adminToken = "").requireSafeForProduction()
    @Test fun `exactly 32 characters is accepted`() =
        safe.copy(jwtSecret = "a".repeat(32), otpSecret = "b".repeat(32), adminToken = "c".repeat(32)).requireSafeForProduction()

    @Test fun `dev mode skips the checks`() = AppConfig(devMode = true).requireSafeForProduction()

    @Test fun `default secrets are refused when DEV_MODE is false`() {
        assertContains(message(AppConfig(devMode = false)), "JWT_SECRET")
        assertContains(message(AppConfig(devMode = false)), "OTP_SECRET")
        AppConfig.KNOWN_DEFAULT_SECRETS.forEach {
            assertContains(message(safe.copy(jwtSecret = it)), "JWT_SECRET")
            assertContains(message(safe.copy(otpSecret = it)), "OTP_SECRET")
        }
    }

    @Test fun `the compose and env-example defaults are covered even though they are long`() {
        val composeDefault = "dev-only-secret-change-me-dev-only-secret"
        assertTrue(composeDefault.length >= 32)
        assertContains(message(safe.copy(jwtSecret = composeDefault)), "JWT_SECRET")
    }

    @Test fun `short secrets are refused`() {
        assertContains(message(safe.copy(jwtSecret = "a".repeat(31))), "JWT_SECRET")
        assertContains(message(safe.copy(otpSecret = "short")), "OTP_SECRET")
    }

    @Test fun `short admin token is refused when set`() {
        val m = message(safe.copy(adminToken = "a".repeat(31)))
        assertContains(m, "ADMIN_TOKEN"); assertContains(m, "32")
    }

    @Test fun `all problems are reported together`() {
        val m = message(AppConfig(devMode = false, adminToken = "x"))
        assertContains(m, "JWT_SECRET"); assertContains(m, "OTP_SECRET"); assertContains(m, "ADMIN_TOKEN")
        assertContains(m, "Refusing to start")
    }

    private fun hocon(devMode: Boolean, jwt: String, otp: String, admin: String) = MapApplicationConfig(
        "soko.cassandra.contact_points" to "127.0.0.1:1", "soko.cassandra.local_dc" to "datacenter1",
        "soko.cassandra.keyspace" to "soko_bei", "soko.cassandra.replication" to "datacenter1:1",
        "soko.cassandra.startup_timeout_seconds" to "1", "soko.mongo.uri" to "mongodb://127.0.0.1:1", "soko.mongo.database" to "x",
        "soko.dev_mode" to devMode.toString(), "soko.jwt_secret" to jwt, "soko.otp_secret" to otp, "soko.admin_token" to admin,
        "soko.report_max_age_days" to "14", "soko.min_price_per_kg" to "1", "soko.max_price_per_kg" to "100000",
        "soko.consent_version" to "1", "soko.reports_per_hour" to "30", "soko.otp_requests_per_15min" to "3",
        "soko.jobs_enabled" to "false", "soko.sms_provider" to "console",
    )

    @Test fun `the real application module refuses to start before touching any database`() {
        val ex = assertFailsWith<Throwable> {
            testApplication {
                environment { config = hocon(false, "change-me-change-me-change-me-change-me", "change-me-otp", "") }
                application { module() }
                startApplication()
            }
        }
        var cause: Throwable? = ex
        while (cause != null && cause !is IllegalStateException) cause = cause.cause
        assertTrue(cause?.message?.contains("Refusing to start with DEV_MODE=false") == true, "got: $ex")
    }
}
