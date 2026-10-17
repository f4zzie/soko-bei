package ke.sokobei.config

import io.ktor.server.config.ApplicationConfig
import java.math.BigDecimal

data class CassandraConfig(
    val contactPoints: List<String> = listOf("localhost:9042"),
    val localDc: String = "datacenter1",
    val keyspace: String = "soko_bei",
    /** "dc:rf,dc:rf" — rendered into a NetworkTopologyStrategy replication map. */
    val replication: String = "datacenter1:1",
    val startupTimeoutSeconds: Long = 120,
) {
    fun replicationCql(): String {
        val entries = replication.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
            val (dc, rf) = it.split(":").map(String::trim)
            require(dc.matches(Regex("[A-Za-z0-9_\\-]+")) && rf.toInt() > 0) { "Bad replication entry '$it'" }
            "'$dc': $rf"
        }
        require(entries.isNotEmpty()) { "replication must not be empty" }
        return "{'class': 'NetworkTopologyStrategy', ${entries.joinToString(", ")}}"
    }
}

data class MongoConfig(val uri: String = "mongodb://localhost:27017", val database: String = "soko_bei")

data class AppConfig(
    val cassandra: CassandraConfig = CassandraConfig(),
    val mongo: MongoConfig = MongoConfig(),
    val devMode: Boolean = false,
    val jwtSecret: String = "change-me-change-me-change-me-change-me",
    val jwtIssuer: String = "soko-bei",
    val otpSecret: String = "change-me-otp",
    val adminToken: String = "",
    val reportMaxAgeDays: Int = 14,
    val minPricePerKg: BigDecimal = BigDecimal(1),
    val maxPricePerKg: BigDecimal = BigDecimal(100_000),
    val consentVersion: Int = 1,
    val reportsPerHour: Int = 30,
    val otpRequestsPer15Min: Int = 3,
    val jobsEnabled: Boolean = true,
    val smsProvider: String = "console",
) {
    /**
     * Refuses unsafe settings outside DEV_MODE: default or short (< 32 chars) JWT/OTP secrets, or a short admin token.
     * An empty ADMIN_TOKEN is allowed: it disables the admin API.
     */
    fun requireSafeForProduction() {
        if (devMode) return
        val problems = buildList {
            if (jwtSecret in KNOWN_DEFAULT_SECRETS || jwtSecret.length < MIN_SECRET_LENGTH)
                add("JWT_SECRET must be set to a non-default value of at least $MIN_SECRET_LENGTH characters")
            if (otpSecret in KNOWN_DEFAULT_SECRETS || otpSecret.length < MIN_SECRET_LENGTH)
                add("OTP_SECRET must be set to a non-default value of at least $MIN_SECRET_LENGTH characters")
            if (adminToken.isNotEmpty() && adminToken.length < MIN_SECRET_LENGTH)
                add("ADMIN_TOKEN must be at least $MIN_SECRET_LENGTH characters when set (or leave it empty to disable the admin API)")
        }
        if (problems.isNotEmpty()) {
            throw IllegalStateException("Refusing to start with DEV_MODE=false: " + problems.joinToString("; "))
        }
    }

    companion object {
        const val MIN_SECRET_LENGTH = 32

        /** Every placeholder value that ships in application.conf, docker-compose.yml or .env.example. */
        val KNOWN_DEFAULT_SECRETS = setOf(
            "change-me-change-me-change-me-change-me", "change-me-otp",
            "dev-only-secret-change-me-dev-only-secret", "dev-only-otp-secret-change-me",
        )

        fun from(config: ApplicationConfig): AppConfig {
            fun str(path: String) = config.property("soko.$path").getString()
            return AppConfig(
                cassandra = CassandraConfig(
                    contactPoints = str("cassandra.contact_points").split(",").map { it.trim() },
                    localDc = str("cassandra.local_dc"),
                    keyspace = str("cassandra.keyspace"),
                    replication = str("cassandra.replication"),
                    startupTimeoutSeconds = str("cassandra.startup_timeout_seconds").toLong(),
                ),
                mongo = MongoConfig(str("mongo.uri"), str("mongo.database")),
                devMode = str("dev_mode").toBoolean(),
                jwtSecret = str("jwt_secret"),
                otpSecret = str("otp_secret"),
                adminToken = str("admin_token"),
                reportMaxAgeDays = str("report_max_age_days").toInt(),
                minPricePerKg = BigDecimal(str("min_price_per_kg")),
                maxPricePerKg = BigDecimal(str("max_price_per_kg")),
                consentVersion = str("consent_version").toInt(),
                reportsPerHour = str("reports_per_hour").toInt(),
                otpRequestsPer15Min = str("otp_requests_per_15min").toInt(),
                jobsEnabled = str("jobs_enabled").toBoolean(),
                smsProvider = str("sms_provider"),
            )
        }
    }
}
