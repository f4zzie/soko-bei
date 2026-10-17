package ke.sokobei.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import ke.sokobei.shared.AccessTokenResponse
import ke.sokobei.shared.RefreshRequest
import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Builds the Ktor client. The Auth plugin attaches the bearer access token to
 * write / account endpoints and, on a 401, refreshes the access token ONCE and
 * replays the request (Ktor's Auth plugin guarantees a single refresh per call).
 * If the refresh is rejected the stored session is cleared (user must log in again).
 */
object ApiClientFactory {
    fun create(engine: HttpClientEngine, baseUrl: String, tokens: TokenStore): HttpClient {
        val root = baseUrl.trimEnd('/') + "/"
        // A bare client (no Auth plugin) is used for the refresh call to avoid recursion.
        val plain = HttpClient(engine) {
            install(ContentNegotiation) { json(ApiJson) }
            defaultRequest { url(root) }
            expectSuccess = false
        }
        return HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(ApiJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                url(root)
                accept(ContentType.Application.Json)
            }
            install(Auth) {
                bearer {
                    loadTokens {
                        tokens.access()?.let { BearerTokens(it, tokens.refresh()) }
                    }
                    refreshTokens {
                        val refresh = tokens.refresh() ?: return@refreshTokens null
                        val resp: HttpResponse = plain.post("v1/auth/refresh") {
                            markAsRefreshTokenRequest()
                            contentType(ContentType.Application.Json)
                            setBody(RefreshRequest(refresh))
                        }
                        when {
                            resp.status.isSuccess() -> {
                                val body: AccessTokenResponse = resp.body()
                                tokens.updateAccess(body.accessToken)
                                BearerTokens(body.accessToken, refresh)
                            }
                            // Refresh token itself is invalid/expired -> session is over.
                            resp.status == HttpStatusCode.Unauthorized ||
                                resp.status == HttpStatusCode.Forbidden ||
                                resp.status == HttpStatusCode.BadRequest -> {
                                tokens.clear()
                                null
                            }
                            else -> null // transient server problem: keep tokens, caller gets the 401
                        }
                    }
                    sendWithoutRequest { req -> requiresAuth(req.url.encodedPath) }
                }
            }
        }
    }

    internal fun requiresAuth(path: String): Boolean =
        // Matches with or without a leading slash: the Auth plugin may see the request path before
        // defaultRequest has resolved it against the base URL (relative "v1/reports").
        path.contains("v1/reports") || path.contains("v1/me") || path.endsWith("v1/auth/logout")
}
