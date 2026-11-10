package ke.sokobei.demo

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ke.sokobei.Services
import ke.sokobei.config.AppConfig
import ke.sokobei.config.CassandraModule
import ke.sokobei.config.MongoModule
import ke.sokobei.repository.Db
import ke.sokobei.sms.SmsSender
import ke.sokobei.time.BusinessCalendar
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class DemoPlan(
    val demo: Boolean = true,
    val seed: Long,
    val day: String,
    val generatedAtMs: Long,
    val users: List<String>,
    val reports: List<DemoEvent>,
    val templates: List<DemoEvent>,
)

object DemoCli {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val actions = setOf("demo-catalog", "demo-backfill", "demo-plan", "demo-jobs")

    fun requireDev(config: AppConfig) {
        require(config.devMode) { "Refusing demo command: DEV_MODE=true is required" }
    }

    fun main(args: Array<String>) = run(args, AppConfig.from(HoconApplicationConfig(ConfigFactory.load())))

    fun run(args: Array<String>, config: AppConfig) {
        requireDev(config)
        require(args.firstOrNull() in actions) { "Commands: ${actions.joinToString()}" }
        val action = args[0]
        require((args.size - 1) % 2 == 0) { "Options require values" }
        val entries = args.drop(1).chunked(2)
        require(entries.map { it[0] }.distinct().size == entries.size) { "Repeated option" }
        val options = entries.associate { it[0] to it[1] }
        require(options.keys.all { it in setOf("--days", "--seed", "--through", "--markets", "--concurrency", "--batch-size", "--output") }) { "Unknown option" }
        val calendar = BusinessCalendar()
        val days = (options["--days"] ?: "90").toInt()
        require(days in 1..365) { "--days must be 1..365" }
        val seed = (options["--seed"] ?: "42").toLong()
        val through = options["--through"]?.let(LocalDate::parse) ?: calendar.today()
        require(!through.isAfter(calendar.today())) { "--through cannot be a future EAT date" }
        val from = through.minusDays(days - 1L)
        val oldest = ChronoUnit.DAYS.between(from, calendar.today()).toInt() + 1
        require(oldest <= 366) { "The selected range is too old" }
        val privateConfig = if (action == "demo-backfill") config.copy(reportMaxAgeDays = maxOf(config.reportMaxAgeDays, oldest)) else config
        CassandraModule.connectWithRetry(config.cassandra, config.cassandra.keyspace).use { session ->
            val (mongoClient, mongo) = MongoModule.connect(config.mongo)
            mongoClient.use {
                MongoModule.prepare(mongo)
                val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                try {
                    val sms = object : SmsSender {
                        override suspend fun send(phoneE164: String, message: String) = error("Demo commands must not send SMS")
                    }
                    val s = Services(privateConfig, Db(session), mongo, calendar, sms, metrics,
                        deferReportRecompute = true, preserveDerivedRows = true)
                    runBlocking {
                        when (action) {
                            "demo-catalog" -> println("Demo catalog: inserted=${DemoCatalog.apply(s)}; markets=${s.markets.all().size}; commodities=${s.commodities.all().size}")
                            "demo-backfill" -> {
                                val historyThrough = minOf(through, calendar.today().minusDays(1))
                                require(!from.isAfter(historyThrough)) { "No past day selected; use demo-plan and the API for today" }
                                val summary = DemoBackfill(s).load(seed, from, historyThrough,
                                    options["--markets"]?.split(',')?.toSet(),
                                    (options["--concurrency"] ?: "2").toInt(), (options["--batch-size"] ?: "64").toInt())
                                val text = json.encodeToString(summary)
                                options["--output"]?.let { Files.writeString(Path.of(it), text) }
                                println(text)
                            }
                            "demo-plan" -> {
                                require(through == calendar.today()) { "demo-plan prepares today's API reports" }
                                DemoUsers.ensure(s)
                                val scenario = DemoScenario.load(s, seed)
                                val trades = scenario.day(through)
                                val opening = scenario.opening(through)
                                val now = calendar.now().toEpochMilli()
                                val reports = (opening + trades).sortedWith(compareBy<DemoEvent> { it.report.reportedAtMs }.thenBy { it.report.reportId })
                                    .filter { it.report.reportedAtMs <= now }.take(1000)
                                val plan = DemoPlan(seed = seed, day = through.toString(), generatedAtMs = now,
                                    users = DemoScenario.PHONES, reports = reports, templates = (opening + trades).filter { !it.outlier })
                                val output = options["--output"] ?: error("demo-plan requires --output FILE")
                                Files.writeString(Path.of(output), json.encodeToString(plan))
                                println("Demo API plan: $output; reports=${reports.size}; templates=${plan.templates.size}; users=${plan.users.size}; generated demo prices only")
                            }
                            "demo-jobs" -> {
                                val dailyStart = System.nanoTime()
                                check(s.dailyStatsJob.run()) { "Daily stats job failed" }
                                val daily = (System.nanoTime() - dailyStart) / 1e9
                                val reconciliationStart = System.nanoTime()
                                check(s.reconciliationJob.run()) { "Reconciliation job failed" }
                                val result = mapOf("daily_stats_seconds" to daily, "reconciliation_seconds" to (System.nanoTime() - reconciliationStart) / 1e9)
                                val text = json.encodeToString(result)
                                options["--output"]?.let { Files.writeString(Path.of(it), text) }
                                println(text)
                            }
                        }
                    }
                } finally {
                    metrics.close()
                }
            }
        }
    }
}
