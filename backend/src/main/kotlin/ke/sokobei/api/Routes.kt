package ke.sokobei.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ke.sokobei.Services
import ke.sokobei.domain.StoredReport
import ke.sokobei.domain.ValidationException
import ke.sokobei.service.ApiException
import ke.sokobei.shared.*
import ke.sokobei.time.TimeUuids
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.YearMonth
import java.util.UUID


fun ApplicationCall.userId(): UUID =
    principal<JWTPrincipal>()?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ApiException.unauthorized()

private fun ApplicationCall.pageParams(): Pair<Int, String?> {
    val raw = request.queryParameters["limit"]
    val limit = if (raw == null) Limits.DEFAULT_PAGE_LIMIT else raw.toIntOrNull()
        ?: throw ValidationException(mapOf("limit" to "must be an integer"))
    if (limit !in 1..Limits.MAX_PAGE_LIMIT) throw ValidationException(mapOf("limit" to "must be between 1 and ${Limits.MAX_PAGE_LIMIT}"))
    return limit to request.queryParameters["page_token"]?.takeIf { it.isNotBlank() }
}

private fun StoredReport.toDto() = ReportDto(
    reportId = reportId.toString(), reportedAtMs = TimeUuids.timestampMs(reportedAt), receivedAtMs = receivedAt.toEpochMilli(),
    marketId = marketId, commodityId = commodityId, grade = grade, unit = unit, quantity = quantity, priceKes = priceKes,
    kgPerUnit = kgPerUnit, conversionVersion = conversionVersion, pricePerKgKes = pricePerKgKes,
)

fun Route.apiRoutes(s: Services, metrics: PrometheusMeterRegistry) {
    get("/health") {
        val report = healthReport(
            cassandraVersion = { s.db.session.execute("SELECT release_version FROM system.local").one()?.getString(0) },
            mongoPing = { s.mongo.runCommand(org.bson.Document("ping", 1)) },
        )
        call.respond(report.httpStatus, report.body)
    }
    get("/metrics") { call.respondText(metrics.scrape(), ContentType.parse("text/plain; version=0.0.4")) }

    route("/v1") {
        authRoutes(s)
        catalogRoutes(s)
        priceRoutes(s)
        reportRoutes(s)
        meRoutes(s)
        adminRoutes(s)
    }
}

private fun Route.authRoutes(s: Services) = rateLimit(RateLimitName("auth")) {
    route("/auth") {
        post("/otp/request") {
            s.auth.requestOtp(call.receive<OtpRequest>().phone)
            call.respond(buildJsonObject { put("status", "sent") })
        }
        post("/otp/verify") {
            val r = call.receive<OtpVerifyRequest>()
            call.respond(s.auth.verifyOtp(r.phone, r.code))
        }
        post("/refresh") { call.respond(s.auth.refresh(call.receive<RefreshRequest>().refreshToken)) }
        post("/logout") {
            s.auth.logout(call.receive<RefreshRequest>().refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.catalogRoutes(s: Services) {
    get("/markets") {
        val (limit, token) = call.pageParams()
        val (items, next) = s.markets.page(limit, token)
        call.respond(Page(items.map { MarketDto(it.marketId, it.name, it.county, it.lat, it.lng) }, next))
    }
    get("/commodities") {
        val (limit, token) = call.pageParams()
        val (items, next) = s.commodities.page(limit, token)
        val dtos = items.map { c ->
            CommodityDto(c.commodityId, c.nameEn, c.nameSw, c.defaultUnit, c.grades,
                s.units.latestForCommodity(c.commodityId).map { UnitDto(it.unit, it.kgPerUnit, it.version, it.verified) })
        }
        call.respond(Page(dtos, next))
    }
}

private fun Route.priceRoutes(s: Services) {
    get("/markets/{market_id}/prices") {
        val id = call.parameters["market_id"]!!
        s.markets.get(id) ?: throw ApiException.notFound("market")
        val (limit, token) = call.pageParams()
        val (rows, next) = s.latestByMarket.page(id, limit, token)
        call.respond(Page(rows.map {
            MarketPriceDto(it.commodityId, it.nameEn, it.nameSw, it.grade, it.median, it.last, it.count, it.updatedAt.toEpochMilli())
        }, next))
    }
    get("/commodities/{commodity_id}/prices") {
        val id = call.parameters["commodity_id"]!!
        s.commodities.get(id) ?: throw ApiException.notFound("commodity")
        val (limit, token) = call.pageParams()
        val (rows, next) = s.latestByCommodity.page(id, limit, token)
        call.respond(Page(rows.map {
            CommodityPriceDto(it.marketId, it.marketName, it.county, it.grade, it.median, it.last, it.count, it.updatedAt.toEpochMilli())
        }, next))
    }
    get("/commodities/{commodity_id}/markets/{market_id}/reports") {
        val c = call.parameters["commodity_id"]!!
        val m = call.parameters["market_id"]!!
        s.commodities.get(c) ?: throw ApiException.notFound("commodity")
        s.markets.get(m) ?: throw ApiException.notFound("market")
        val month = call.request.queryParameters["month"] ?: s.calendar.monthKey(s.calendar.today())
        if (runCatching { YearMonth.parse(month) }.isFailure || !month.matches(Regex("\\d{4}-\\d{2}"))) {
            throw ValidationException(mapOf("month" to "must be YYYY-MM"))
        }
        val (limit, token) = call.pageParams()
        val (rows, next) = s.reports.page(c, m, month, limit, token)
        call.respond(Page(rows.map { it.toDto() }, next))
    }
    get("/commodities/{commodity_id}/markets/{market_id}/trend") {
        val c = call.parameters["commodity_id"]!!
        val m = call.parameters["market_id"]!!
        s.commodities.get(c) ?: throw ApiException.notFound("commodity")
        s.markets.get(m) ?: throw ApiException.notFound("market")
        val days = call.request.queryParameters["days"]?.let {
            it.toIntOrNull() ?: throw ValidationException(mapOf("days" to "must be an integer"))
        } ?: 30
        if (days !in 1..Limits.MAX_TREND_DAYS) throw ValidationException(mapOf("days" to "must be between 1 and ${Limits.MAX_TREND_DAYS}"))
        val grade = call.request.queryParameters["grade"] ?: Limits.DEFAULT_GRADE
        call.respond(s.trends.trend(c, m, days, grade))
    }
}

private fun Route.reportRoutes(s: Services) = authenticate("auth-jwt") {
    rateLimit(RateLimitName("reports")) {
        post("/reports") {
            val result = s.reportService.submit(call.userId(), call.receive<ReportRequest>())
            call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.report.toDto())
        }
    }
    post("/reports/{report_id}/flags") {
        val id = runCatching { UUID.fromString(call.parameters["report_id"]) }.getOrNull()
            ?: throw ValidationException(mapOf("report_id" to "must be a UUID"))
        val reason = runCatching { call.receive<FlagRequest>().reason }.getOrDefault("wrong_price")
        val created = s.flags.flag(call.userId(), id, reason)
        call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, buildJsonObject { put("status", "flagged") })
    }
}

private fun Route.meRoutes(s: Services) = authenticate("auth-jwt") {
    route("/me") {
        get { call.respond(s.account.me(call.userId())) }
        post("/consent") {
            s.account.acceptConsent(call.userId(), call.receive<ConsentRequest>().consentVersion)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/reports") {
            val uid = call.userId()
            val year = call.request.queryParameters["year"]?.let {
                it.toIntOrNull()?.takeIf { y -> y in 2000..2200 } ?: throw ValidationException(mapOf("year" to "must be YYYY"))
            } ?: s.calendar.year(s.calendar.now())
            val (limit, token) = call.pageParams()
            val page = s.byUser.page(uid, year, limit, token)
            call.respond(Page(page.rows.map {
                ReportDto(
                    reportId = it.getUuid("report_id")!!.toString(), reportedAtMs = TimeUuids.timestampMs(it.getUuid("reported_at")!!),
                    marketId = it.getString("market_id")!!, commodityId = it.getString("commodity_id")!!, grade = it.getString("grade")!!,
                    unit = it.getString("unit")!!, quantity = it.getBigDecimal("quantity")!!, priceKes = it.getInt("price_kes"),
                )
            }, page.nextToken))
        }
        delete {
            s.account.delete(call.userId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.adminRoutes(s: Services) = route("/admin") {
    fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    fun ApplicationCall.requireAdmin() {
        val expected = s.config.adminToken
        // With no token configured the admin API is closed for every request, including an empty header.
        if (expected.isEmpty()) throw ApiException(403, ErrorCodes.FORBIDDEN, "admin API is disabled")
        val given = request.headers["X-Admin-Token"] ?: ""
        // Compare fixed-length digests in constant time so neither content nor length leaks through timing.
        if (!MessageDigest.isEqual(sha(given), sha(expected))) throw ApiException(403, ErrorCodes.FORBIDDEN, "admin token required")
    }
    post("/rebuild") {
        call.requireAdmin()
        val from = runCatching { java.time.LocalDate.parse(call.request.queryParameters["from"]) }.getOrNull()
            ?: throw ValidationException(mapOf("from" to "must be YYYY-MM-DD"))
        val to = runCatching { java.time.LocalDate.parse(call.request.queryParameters["to"]) }.getOrNull()
            ?: throw ValidationException(mapOf("to" to "must be YYYY-MM-DD"))
        if (to.isBefore(from)) throw ValidationException(mapOf("to" to "must not be before from"))
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) throw ValidationException(mapOf("to" to "range is limited to 366 days"))
        val pairs = s.recompute.rebuildDaily(from, to)
        s.recompute.rebuildLatestToday()
        call.respond(RebuildResult(from.toString(), to.toString(), (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1).toInt(), pairs))
    }
    post("/jobs/daily-stats") {
        call.requireAdmin()
        val ok = s.dailyStatsJob.run()
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError, buildJsonObject { put("success", ok) })
    }
}


class HealthReport(val httpStatus: HttpStatusCode, val body: kotlinx.serialization.json.JsonObject)

/** Always yields a JSON body with a per-database status; 200 only when both stores answer, else 503. */
suspend fun healthReport(cassandraVersion: suspend () -> String?, mongoPing: suspend () -> Unit): HealthReport {
    val cassandra = runCatching { cassandraVersion() }
    val mongoUp = runCatching { mongoPing() }.isSuccess
    val healthy = cassandra.isSuccess && mongoUp
    val body = buildJsonObject {
        put("status", if (healthy) "ok" else "degraded")
        put("cassandra", if (cassandra.isSuccess) "up" else "down")
        put("mongodb", if (mongoUp) "up" else "down")
        cassandra.getOrNull()?.let { put("cassandra_version", it) }
    }
    return HealthReport(if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, body)
}
