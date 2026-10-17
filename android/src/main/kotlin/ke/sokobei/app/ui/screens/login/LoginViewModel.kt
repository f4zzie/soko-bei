package ke.sokobei.app.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.repository.AuthRepository
import ke.sokobei.app.data.repository.AuthResult
import ke.sokobei.shared.Limits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginStep { PHONE, CODE }

enum class LoginError { INVALID_PHONE, INVALID_CODE, NETWORK, RATE_LIMITED, SERVER }

data class LoginUiState(
    val step: LoginStep = LoginStep.PHONE,
    val phone: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: LoginError? = null,
    val serverMessage: String? = null,
)

object PhoneInput {
    private val pattern = Regex("^(\\+?254|0)[17]\\d{8}$")
    fun clean(raw: String) = raw.filter { it.isDigit() || it == '+' }
    fun isValid(raw: String) = pattern.matches(clean(raw))
}

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    fun onPhoneChange(v: String) = _state.update { it.copy(phone = v.take(16), error = null) }
    fun onCodeChange(v: String) = _state.update { it.copy(code = v.filter(Char::isDigit).take(Limits.OTP_LENGTH), error = null) }
    fun backToPhone() = _state.update { it.copy(step = LoginStep.PHONE, code = "", error = null) }

    fun sendCode() {
        val s = _state.value
        if (s.busy) return
        if (!PhoneInput.isValid(s.phone)) {
            _state.update { it.copy(error = LoginError.INVALID_PHONE) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = auth.requestOtp(PhoneInput.clean(s.phone))
            _state.update {
                when (r) {
                    AuthResult.Success -> it.copy(busy = false, step = LoginStep.CODE)
                    is AuthResult.Failure -> it.copy(busy = false, error = r.kind.toError(forPhone = true), serverMessage = r.serverMessage)
                }
            }
        }
    }

    fun verify() {
        val s = _state.value
        if (s.busy) return
        if (s.code.length != Limits.OTP_LENGTH) {
            _state.update { it.copy(error = LoginError.INVALID_CODE) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = auth.verifyOtp(PhoneInput.clean(s.phone), s.code)) {
                AuthResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    _done.send(Unit)
                }
                is AuthResult.Failure ->
                    _state.update { it.copy(busy = false, error = r.kind.toError(forPhone = false), serverMessage = r.serverMessage) }
            }
        }
    }

    private fun AuthResult.Kind.toError(forPhone: Boolean) = when (this) {
        AuthResult.Kind.INVALID_INPUT -> if (forPhone) LoginError.INVALID_PHONE else LoginError.INVALID_CODE
        AuthResult.Kind.NETWORK -> LoginError.NETWORK
        AuthResult.Kind.RATE_LIMITED -> LoginError.RATE_LIMITED
        AuthResult.Kind.SERVER -> LoginError.SERVER
    }
}
