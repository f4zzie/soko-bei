package ke.sokobei.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.authProviders
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import ke.sokobei.shared.AuthTokens
import ke.sokobei.shared.CommodityDto
import ke.sokobei.shared.CommodityPriceDto
import ke.sokobei.shared.ConsentRequest
import ke.sokobei.shared.ErrorEnvelope
import ke.sokobei.shared.Limits
import ke.sokobei.shared.MarketDto
import ke.sokobei.shared.MarketPriceDto
import ke.sokobei.shared.OtpRequest
import ke.sokobei.shared.OtpVerifyRequest
import ke.sokobei.shared.Page
import ke.sokobei.shared.RefreshRequest
import ke.sokobei.shared.ReportRequest
import ke.sokobei.shared.TrendResponse
import ke.sokobei.shared.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.SerializationException
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** The server answered with a non-2xx status. [code] comes from the error envelope. */
    data class HttpError(val status: Int, val code: String, val message: String) : ApiResult<Nothing>

    /** No usable response (offline, timeout, DNS, malformed body). Always retryable. */
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>
}

enum class SubmitStatus { CREATED, ALREADY_EXISTS }

class SokoBeiApi(private val client: HttpClient) {

    // ---- auth ----
    suspend fun requestOtp(phone: String): ApiResult<Unit> =
        call { client.post("v1/auth/otp/request") { json(OtpRequest(phone)) } }.unit()

    suspend fun verifyOtp(phone: String, code: String): ApiResult<AuthTokens> =
        call { client.post("v1/auth/otp/verify") { json(OtpVerifyRequest(phone, code)) } }.decode()

    suspend fun logout(refreshToken: String): ApiResult<Unit> =
        call { client.post("v1/auth/logout") { json(RefreshRequest(refreshToken)) } }.unit()

    // ---- public reads (all pages are fetched) ----
    suspend fun markets(): ApiResult<List<MarketDto>> = allPages("v1/markets", MarketDto.serializer())
    suspend fun commodities(): ApiResult<List<CommodityDto>> = allPages("v1/commodities", CommodityDto.serializer())
    suspend fun marketPrices(marketId: String): ApiResult<List<MarketPriceDto>> =
        allPages("v1/markets/$marketId/prices", MarketPriceDto.serializer())

    suspend fun commodityPrices(commodityId: String): ApiResult<List<CommodityPriceDto>> =
        allPages("v1/commodities/$commodityId/prices", CommodityPriceDto.serializer())

    suspend fun trend(commodityId: String, marketId: String, days: Int, grade: String): ApiResult<TrendResponse> =
        call {
            client.get("v1/commodities/$commodityId/markets/$marketId/trend") {
                parameter("days", days)
                parameter("grade", grade)
            }
        }.decode()

    // ---- writes ----
    /** 201 -> CREATED, 200 -> ALREADY_EXISTS (idempotent retry); other statuses are [ApiResult.HttpError]. */
    suspend fun submitReport(request: ReportRequest): ApiResult<SubmitStatus> =
        try {
            val resp = client.post("v1/reports") { json(request) }
            when {
                resp.status == HttpStatusCode.Created -> ApiResult.Success(SubmitStatus.CREATED)
                resp.status == HttpStatusCode.OK -> ApiResult.Success(SubmitStatus.ALREADY_EXISTS)
                resp.status.isSuccess() -> ApiResult.Success(SubmitStatus.CREATED)
                else -> resp.toHttpError()
            }
        } catch (e: Exception) {
            e.toNetworkError()
        }

    // ---- me ----
    suspend fun me(): ApiResult<UserDto> = call { client.get("v1/me") }.decode()

    suspend fun postConsent(version: Int = Limits.CURRENT_CONSENT_VERSION): ApiResult<Unit> =
        call { client.post("v1/me/consent") { json(ConsentRequest(version)) } }.unit()

    suspend fun deleteAccount(): ApiResult<Unit> = call { client.delete("v1/me") }.unit()

    /** Drops Ktor's in-memory bearer cache; call after login / logout. */
    fun resetAuthCache() {
        client.authProviders.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
    }

    // ---- plumbing ----
    private suspend fun <T> allPages(path: String, serializer: KSerializer<T>): ApiResult<List<T>> {
        val out = ArrayList<T>()
        var token: String? = null
        val seen = HashSet<String>()
        do {
            val result = call {
                client.get(path) {
                    parameter("limit", Limits.MAX_PAGE_LIMIT)
                    token?.let { parameter("page_token", it) }
                }
            }
            when (result) {
                is Raw.Ok -> {
                    val page = try {
                        ApiJson.decodeFromString(Page.serializer(serializer), result.body)
                    } catch (e: SerializationException) {
                        return ApiResult.NetworkError(e)
                    }
                    out += page.items
                    token = page.nextPageToken?.takeIf { it.isNotEmpty() }
                    // Guard against a server that keeps returning the same cursor.
                    if (token != null && !seen.add(token)) return ApiResult.NetworkError(IOException("Repeated page token"))
                }
                is Raw.Err -> return result.error
            }
        } while (token != null)
        return ApiResult.Success(out)
    }

    private sealed interface Raw {
        data class Ok(val status: Int, val body: String) : Raw
        data class Err(val error: ApiResult<Nothing>) : Raw
    }

    private suspend fun call(block: suspend () -> HttpResponse): Raw =
        try {
            val resp = block()
            if (resp.status.isSuccess()) Raw.Ok(resp.status.value, resp.bodyAsText()) else Raw.Err(resp.toHttpError())
        } catch (e: Exception) {
            Raw.Err(e.toNetworkError())
        }

    private fun Raw.unit(): ApiResult<Unit> = when (this) {
        is Raw.Ok -> ApiResult.Success(Unit)
        is Raw.Err -> error
    }

    private inline fun <reified T> Raw.decode(): ApiResult<T> = when (this) {
        is Raw.Ok -> try {
            ApiResult.Success(ApiJson.decodeFromString<T>(body))
        } catch (e: SerializationException) {
            ApiResult.NetworkError(e)
        }
        is Raw.Err -> error
    }

    private fun Exception.toNetworkError(): ApiResult.NetworkError {
        if (this is CancellationException) throw this
        return ApiResult.NetworkError(this)
    }

    private inline fun <reified B : Any> io.ktor.client.request.HttpRequestBuilder.json(body: B) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.toHttpError(): ApiResult.HttpError {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val envelope = runCatching { ApiJson.decodeFromString(ErrorEnvelope.serializer(), text) }.getOrNull()
        return ApiResult.HttpError(
            status = status.value,
            code = envelope?.error?.code ?: "HTTP_${status.value}",
            message = envelope?.error?.message ?: status.description,
        )
    }
}
