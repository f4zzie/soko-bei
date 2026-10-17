package ke.sokobei.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import ke.sokobei.domain.ValidationException
import ke.sokobei.repository.InvalidPageTokenException
import ke.sokobei.service.ApiException
import ke.sokobei.shared.ErrorBody
import ke.sokobei.shared.ErrorCodes
import ke.sokobei.shared.ErrorEnvelope
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String, details: Map<String, String> = emptyMap()) =
    respond(status, ErrorEnvelope(ErrorBody(code, message, details)))

fun Application.installErrorHandling() {
    val log = LoggerFactory.getLogger("ke.sokobei.api.Errors")
    install(StatusPages) {
        exception<ValidationException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION_FAILED, "Validation failed", e.details)
        }
        exception<ApiException> { call, e ->
            call.respondError(HttpStatusCode.fromValue(e.status), e.code, e.message, e.details)
        }
        exception<InvalidPageTokenException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION_FAILED, "Validation failed", mapOf("page_token" to "invalid"))
        }
        exception<SerializationException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION_FAILED, "Malformed or invalid request body")
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION_FAILED, "Malformed or invalid request body")
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {} {}", call.request.local.method.value, call.request.local.uri, e)
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "Unexpected server error")
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respondError(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED, "Too many requests")
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "Resource not found")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondError(HttpStatusCode.MethodNotAllowed, ErrorCodes.VALIDATION_FAILED, "Method not allowed")
        }
    }
}
