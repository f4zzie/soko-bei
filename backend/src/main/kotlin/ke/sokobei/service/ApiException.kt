package ke.sokobei.service

import ke.sokobei.shared.ErrorCodes

/** Business-level failure mapped to the JSON error envelope by the API layer. */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message) {
    companion object {
        fun notFound(what: String) = ApiException(404, ErrorCodes.NOT_FOUND, "$what not found")
        fun unauthorized(msg: String = "authentication required") = ApiException(401, ErrorCodes.UNAUTHORIZED, msg)
        fun badRequest(field: String, reason: String) =
            ApiException(400, ErrorCodes.VALIDATION_FAILED, "Validation failed", mapOf(field to reason))
        fun tooMany(msg: String) = ApiException(429, ErrorCodes.RATE_LIMITED, msg)
    }
}
