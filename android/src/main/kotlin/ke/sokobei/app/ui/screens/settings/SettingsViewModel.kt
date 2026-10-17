package ke.sokobei.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.repository.AuthRepository
import ke.sokobei.app.data.repository.AuthResult
import ke.sokobei.app.ui.LanguageController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val loggedIn: Boolean = false,
    val language: String = "en",
    val confirmingDelete: Boolean = false,
    val busy: Boolean = false,
    val deleteError: AuthResult.Kind? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val language: LanguageController,
) : ViewModel() {
    private data class Local(val language: String, val confirming: Boolean, val busy: Boolean, val error: AuthResult.Kind?)

    private val local = MutableStateFlow(Local(language.current(), false, false, null))

    private val _accountDeleted = Channel<Unit>(Channel.BUFFERED)
    val accountDeleted = _accountDeleted.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(auth.loggedIn, local) { loggedIn, l ->
        SettingsUiState(loggedIn, l.language, l.confirming, l.busy, l.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(language = language.current()))

    fun setLanguage(tag: String) {
        local.value = local.value.copy(language = tag)
        language.set(tag)
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }

    fun askDelete() { local.value = local.value.copy(confirming = true, error = null) }
    fun cancelDelete() { local.value = local.value.copy(confirming = false) }

    fun confirmDelete() {
        if (local.value.busy) return
        local.value = local.value.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val r = auth.deleteAccount()) {
                AuthResult.Success -> {
                    local.value = local.value.copy(busy = false, confirming = false)
                    _accountDeleted.send(Unit)
                }
                is AuthResult.Failure -> local.value = local.value.copy(busy = false, error = r.kind)
            }
        }
    }
}
