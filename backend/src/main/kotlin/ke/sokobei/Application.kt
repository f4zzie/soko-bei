package ke.sokobei

import com.datastax.oss.driver.api.core.CqlSession
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ke.sokobei.api.apiRoutes
import ke.sokobei.api.installErrorHandling
import ke.sokobei.api.respondError
import ke.sokobei.config.AppConfig
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import ke.sokobei.config.CassandraModule
import ke.sokobei.config.MongoModule
import ke.sokobei.domain.ReportValidator
import ke.sokobei.jobs.DailyStatsJob
import ke.sokobei.jobs.JobRunner
import ke.sokobei.jobs.JobScheduler
import ke.sokobei.jobs.ReconciliationJob
import ke.sokobei.repository.*
import ke.sokobei.service.*
import ke.sokobei.shared.ErrorCodes
import ke.sokobei.sms.SmsSender
import ke.sokobei.sms.SmsSenders
import ke.sokobei.time.BusinessCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Wires repositories (statements are prepared once, here) and services. */
class Services(
    val config: AppConfig,
    val db: Db,
    val mongo: MongoDatabase,
    val calendar: BusinessCalendar,
    sms: SmsSender,
    val metrics: PrometheusMeterRegistry,
) {
    val markets = MarketRepository(db)
    val commodities = CommodityRepository(db)
    val units = CommodityUnitRepository(db)
    private val users = UserRepository(mongo)
    private val otps = OtpRepository(mongo)
    private val refreshTokens = RefreshTokenRepository(mongo)
    val reports = ReportSourceRepository(db)
    val byUser = ReportsByUserRepository(db)
    private val locator = ReportLocatorRepository(db)
    val latestByCommodity = LatestPriceByCommodityRepository(db)
    val latestByMarket = LatestPriceByMarketRepository(db)
    val dailyStats = DailyStatsRepository(db)
    private val flagRepo = FlagRepository(db)
    private val jobState = JobStateRepository(db)

    val tokens = TokenService(config)
    val recompute = PriceRecomputeService(db, calendar, reports, markets, commodities, latestByCommodity, latestByMarket, dailyStats)
    val trends = TrendService(calendar, dailyStats)
    val auth = AuthService(config, calendar, tokens, users, otps, refreshTokens, sms, SlidingWindowLimiter { calendar.now() })
    val reportService = ReportService(
        db, calendar, ReportValidator(config.reportMaxAgeDays, config.minPricePerKg, config.maxPricePerKg), config.consentVersion,
        users, markets, commodities, units, reports, byUser, locator, recompute,
        onSubmitted = { created -> metrics.counter("soko_reports_submitted_total", "created", created.toString()).increment() },
    )
    val flags = FlagService(calendar, locator, flagRepo)
    /** Test seam, see [AccountService]. */
    @Volatile var accountDeletionHook: suspend () -> Unit = {}
    val account = AccountService(calendar, config.consentVersion, users, byUser, reports, locator, refreshTokens, flagRepo, otps) { accountDeletionHook() }

    val jobRunner = JobRunner(jobState, calendar, metrics)
    val dailyStatsJob = DailyStatsJob(recompute, jobRunner, calendar, config.reportMaxAgeDays)
    val reconciliationJob = ReconciliationJob(recompute, jobRunner, calendar)
}

/** Production entry point, referenced from application.conf. */
fun Application.module() {
    val config = AppConfig.from(environment.config)
    config.requireSafeForProduction()
    val log = LoggerFactory.getLogger("ke.sokobei.Application")
    if (config.devMode) log.warn("DEV_MODE is ON: OTP code 000000 is accepted. Never run production like this.")
    if (config.smsProvider == "console" && !config.devMode) log.warn("SMS_PROVIDER=console in non-dev mode: OTP codes are written to the log")
    val session = CassandraModule.connectAndMigrate(config.cassandra)
    val (mongoClient, mongo) = MongoModule.connect(config.mongo)
    MongoModule.prepare(mongo)
    monitor.subscribe(ApplicationStopped) { session.close(); mongoClient.close() }
    sokoApp(config, session, mongo)
}

/** Installs everything. Takes its dependencies so tests can supply a container-backed session and a controlled clock. */
fun Application.sokoApp(
    config: AppConfig,
    session: CqlSession,
    mongo: MongoDatabase,
    calendar: BusinessCalendar = BusinessCalendar(),
    sms: SmsSender = SmsSenders.create(config.smsProvider),
    metrics: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
): Services {
    val services = Services(config, Db(session), mongo, calendar, sms, metrics)

    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }) }
    install(CallLogging)
    install(MicrometerMetrics) { registry = metrics }
    installErrorHandling()
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "soko-bei"
            verifier(services.tokens.verifier)
            validate { cred -> if (cred.payload.subject != null) JWTPrincipal(cred.payload) else null }
            challenge { _, _ -> call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "authentication required") }
        }
    }
    install(RateLimit) {
        register(RateLimitName("auth")) { rateLimiter(limit = 60, refillPeriod = 1.minutes) }
        register(RateLimitName("reports")) {
            rateLimiter(limit = config.reportsPerHour, refillPeriod = 1.hours)
            requestKey { call -> call.principal<JWTPrincipal>()?.subject ?: call.request.origin.remoteHost }
        }
    }
    routing { apiRoutes(services, metrics) }

    if (config.jobsEnabled) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        monitor.subscribe(ApplicationStopped) { scope.cancel() }
        JobScheduler(scope, calendar, services.jobRunner, services.dailyStatsJob, services.reconciliationJob).start()
    }
    return services
}
