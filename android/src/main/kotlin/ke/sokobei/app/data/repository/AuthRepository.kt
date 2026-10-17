package ke.sokobei.app.data.repository

import ke.sokobei.app.data.api.ApiResult
import ke.sokobei.app.data.api.SokoBeiApi
import ke.sokobei.app.data.api.TokenStore
import ke.sokobei.app.data.db.ReportDao
import ke.sokobei.app.data.sync.SyncScheduler
import ke.sokobei.shared.Limits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val kind: Kind, val serverMessage: String? = null) : AuthResult
    enum class Kind { INVALID_INPUT, NETWORK, RATE_LIMITED, SERVER }
}

interface AuthRepository {
    val loggedIn: Flow<Boolean>
    suspend fun requestOtp(phone: String): AuthResult
    suspend fun verifyOtp(phone: String, code: String): AuthResult
    suspend fun logout()
    /** Deletes the server account then wipes local data. */
    suspend fun deleteAccount(): AuthResult
}

class AuthRepositoryImpl(
    private val api: SokoBeiApi,
    private val tokens: TokenStore,
    private val settings: AppSettings,
    private val reports: ReportDao,
    private val scheduler: SyncScheduler,
) : AuthRepository {
    override val loggedIn = tokens.loggedIn

    override suspend fun requestOtp(phone: String): AuthResult = api.requestOtp(phone).toAuth()

    override suspend fun verifyOtp(phone: String, code: String): AuthResult {
        val r = api.verifyOtp(phone, code)
        if (r !is ApiResult.Success) return r.toAuth()
        tokens.save(r.value.accessToken, r.value.refreshToken)
        api.resetAuthCache()
        // Tell the server about the consent already given on the welcome screen. Best effort:
        // if it fails the sync worker retries it when the server answers CONSENT_REQUIRED.
        if (settings.consentVersion.first() != null) api.postConsent(Limits.CURRENT_CONSENT_VERSION)
        scheduler.enqueueNow()
        return AuthResult.Success
    }

    override suspend fun logout() {
        tokens.refresh()?.let { api.logout(it) } // best effort; local logout always happens
        tokens.clear()
        api.resetAuthCache()
    }

    override suspend fun deleteAccount(): AuthResult {
        if (tokens.refresh() == null) return AuthResult.Failure(AuthResult.Kind.INVALID_INPUT)
        val r = api.deleteAccount()
        if (r !is ApiResult.Success) return r.toAuth()
        tokens.clear()
        api.resetAuthCache()
        reports.clear()
        settings.clear()
        return AuthResult.Success
    }

    private fun ApiResult<*>.toAuth(): AuthResult = when (this) {
        is ApiResult.Success -> AuthResult.Success
        is ApiResult.NetworkError -> AuthResult.Failure(AuthResult.Kind.NETWORK)
        is ApiResult.HttpError -> AuthResult.Failure(
            when {
                status == 429 -> AuthResult.Kind.RATE_LIMITED
                status in 400..499 -> AuthResult.Kind.INVALID_INPUT
                else -> AuthResult.Kind.SERVER
            },
            message,
        )
    }
}
