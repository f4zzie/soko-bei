package ke.sokobei.it

import com.datastax.oss.driver.api.core.CqlSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import ke.sokobei.Services
import ke.sokobei.config.AppConfig
import ke.sokobei.config.CassandraConfig
import ke.sokobei.config.CassandraModule
import ke.sokobei.sms.SmsSender
import ke.sokobei.shared.*
import ke.sokobei.sokoApp
import ke.sokobei.time.BusinessCalendar
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import ke.sokobei.config.MongoConfig
import ke.sokobei.config.MongoModule
import org.bson.Document
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.mongodb.MongoDBContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * One Cassandra per test run: a Testcontainers container, or an external node when SOKO_TEST_CASSANDRA=host:port is set
 * (for machines without Docker). Each test gets its own keyspace, so tests are isolated.
 */
object TestCassandra {
    private val base: CassandraConfig by lazy {
        val external = System.getenv("SOKO_TEST_CASSANDRA")
        if (external != null) {
            CassandraConfig(contactPoints = listOf(external), localDc = System.getenv("SOKO_TEST_CASSANDRA_DC") ?: "datacenter1", startupTimeoutSeconds = 60)
        } else {
            val c = CassandraContainer("cassandra:5.0").withEnv("MAX_HEAP_SIZE", "768M").withEnv("HEAP_NEWSIZE", "128M")
                .withStartupTimeout(Duration.ofMinutes(4))
            c.start()
            Runtime.getRuntime().addShutdownHook(Thread { c.stop() })
            CassandraConfig(contactPoints = listOf("${c.host}:${c.getMappedPort(9042)}"), localDc = c.localDatacenter, startupTimeoutSeconds = 60)
        }
    }

    fun newConfig(): CassandraConfig = base.copy(keyspace = "t_" + UUID.randomUUID().toString().replace("-", "").take(16))
    fun admin(): CqlSession = CassandraModule.connectWithRetry(base)
}

/** MongoDB: a Testcontainers container, or an external server when SOKO_TEST_MONGO=mongodb://host:port is set. */
object TestMongo {
    private val uri: String by lazy {
        System.getenv("SOKO_TEST_MONGO") ?: MongoDBContainer("mongo:8").also {
            it.start(); Runtime.getRuntime().addShutdownHook(Thread { it.stop() })
        }.replicaSetUrl.substringBefore("/test")
    }
    fun newConfig() = MongoConfig(uri, "t_" + UUID.randomUUID().toString().replace("-", "").take(16))
}

class MutableClock(start: Instant) : Clock() {
    private val millis = AtomicLong(start.toEpochMilli())
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
    fun set(i: Instant) = millis.set(i.toEpochMilli())
    fun advance(d: Duration) { millis.addAndGet(d.toMillis()) }
}

class CapturingSms : SmsSender {
    val sent = mutableListOf<Pair<String, String>>()
    override suspend fun send(phoneE164: String, message: String) { sent += phoneE164 to message }
    fun lastCode(): String = Regex("\\d{6}").find(sent.last().second)!!.value
}

val NOON_EAT_OCT_17: Instant = Instant.parse("2026-10-17T09:00:00Z")

class Env(
    val client: HttpClient,
    val services: Services,
    val session: CqlSession,
    val mongo: MongoDatabase,
    val clock: MutableClock,
    val sms: CapturingSms,
    val config: AppConfig,
) {
    val cal get() = services.calendar
    private var phoneCounter = 0

    fun HttpRequestBuilder.bearer(token: String) = header("Authorization", "Bearer $token")

    suspend fun login(phone: String = "07${(10000000 + (++phoneCounter) * 7919 % 89999999)}", consent: Boolean = true): Login {
        val tokens: AuthTokens = if (config.devMode) {
            client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, "000000")) }.body()
        } else {
            client.post("/v1/auth/otp/request") { jsonBody(OtpRequest(phone)) }
            client.post("/v1/auth/otp/verify") { jsonBody(OtpVerifyRequest(phone, sms.lastCode())) }.body()
        }
        if (consent) {
            val r = client.post("/v1/me/consent") { bearer(tokens.accessToken); jsonBody(ConsentRequest(config.consentVersion)) }
            check(r.status.value == 204) { "consent failed: ${r.status}" }
        }
        return Login(phone, tokens)
    }

    fun req(
        msAgo: Long = 0, price: Int = 4500, market: String = "wakulima", commodity: String = "tomatoes", grade: String = "standard",
        unit: String = "crate", qty: String = "1", id: UUID = UUID.randomUUID(), atMs: Long? = null,
    ) = ReportRequest(id.toString(), atMs ?: (clock.millis() - msAgo), market, commodity, grade, unit, BigDecimal(qty), price)

    suspend fun submit(token: String, r: ReportRequest): HttpResponse =
        client.post("/v1/reports") { bearer(token); jsonBody(r) }

    suspend fun get(path: String, token: String? = null): HttpResponse = client.get(path) { token?.let { bearer(it) } }
    suspend fun del(path: String, token: String): HttpResponse = client.delete(path) { bearer(token) }

    fun mcount(collection: String): Long = kotlinx.coroutines.runBlocking { mongo.getCollection<Document>(collection).countDocuments() }
    fun mdocs(collection: String): List<Document> = kotlinx.coroutines.runBlocking {
        mongo.getCollection<Document>(collection).find().toList()
    }

    fun count(table: String, where: String = "", vararg args: Any): Long =
        session.execute("SELECT count(*) FROM $table $where", *args).one()!!.getLong(0)
}

data class Login(val phone: String, val tokens: AuthTokens) { val token get() = tokens.accessToken }

fun HttpRequestBuilder.jsonBody(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(IT_JSON.encodeToString(kotlinx.serialization.serializer(body.javaClass), body))
}

val IT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

suspend inline fun <reified T> HttpResponse.json(): T = IT_JSON.decodeFromString(body<String>())

/** Runs [block] against a fully wired app on a fresh keyspace. */
fun withEnv(
    devMode: Boolean = true,
    clockStart: Instant = NOON_EAT_OCT_17,
    tweak: (AppConfig) -> AppConfig = { it },
    block: suspend Env.() -> Unit,
) {
    val cassandra = TestCassandra.newConfig()
    val config = tweak(AppConfig(cassandra = cassandra, mongo = MongoConfig(), devMode = devMode, adminToken = "test-admin", jobsEnabled = false,
        jwtSecret = "test-secret-test-secret-test-secret-1234", reportsPerHour = 10_000, otpRequestsPer15Min = 3))
    val session = CassandraModule.connectAndMigrate(cassandra)
    val mongoCfg = TestMongo.newConfig()
    val (mongoClient, mongo) = MongoModule.connect(mongoCfg)
    MongoModule.prepare(mongo, 30)
    val clock = MutableClock(clockStart)
    val sms = CapturingSms()
    try {
        testApplication {
            lateinit var services: Services
            application { services = sokoApp(config, session, mongo, BusinessCalendar(clock), sms) }
            val client = createClient { install(ContentNegotiation) { json(IT_JSON) } }
            startApplication()
            Env(client, services, session, mongo, clock, sms, config).let { env -> kotlinx.coroutines.runBlocking { env.block() } }
        }
    } finally {
        runCatching { TestCassandra.admin().use { it.execute("DROP KEYSPACE IF EXISTS ${cassandra.keyspace}") } }
        session.close()
        runCatching { kotlinx.coroutines.runBlocking { mongo.drop() } }
        mongoClient.close()
    }
}

