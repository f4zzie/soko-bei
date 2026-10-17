package ke.sokobei.sms

import ke.sokobei.domain.Phone
import org.slf4j.LoggerFactory

interface SmsSender {
    suspend fun send(phoneE164: String, message: String)
}

/** Dev-only: logs the message, including the OTP code. The only place an OTP may be logged. Phone is masked. */
class ConsoleSmsSender : SmsSender {
    private val log = LoggerFactory.getLogger(ConsoleSmsSender::class.java)
    override suspend fun send(phoneE164: String, message: String) {
        log.info("[SMS to {}] {}", Phone.mask(phoneE164), message)
    }
}

object SmsSenders {
    /**
     * Selects the sender from `SMS_PROVIDER`. Only `console` is implemented.
     * To wire a real provider (e.g. Africa's Talking, Twilio), implement [SmsSender], add a branch here,
     * and read its credentials from environment variables. Unknown providers fail at startup
     * rather than silently dropping OTPs.
     */
    fun create(provider: String): SmsSender = when (provider.lowercase()) {
        "console" -> ConsoleSmsSender()
        else -> error("SMS provider '$provider' is not implemented. See README > SMS provider. Use SMS_PROVIDER=console for development.")
    }
}
