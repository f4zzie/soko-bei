package ke.sokobei.app.ui.screens.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.repository.AppSettings
import ke.sokobei.app.ui.LanguageController
import ke.sokobei.shared.Limits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WelcomeUiState(val language: String, val consentChecked: Boolean = false) {
    val canContinue get() = consentChecked
}

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val settings: AppSettings,
    private val language: LanguageController,
) : ViewModel() {
    private val _state = MutableStateFlow(WelcomeUiState(language.current()))
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    fun setConsent(checked: Boolean) = _state.update { it.copy(consentChecked = checked) }

    fun setLanguage(tag: String) {
        _state.update { it.copy(language = tag) }
        language.set(tag)
    }

    fun accept() {
        if (!_state.value.canContinue) return
        viewModelScope.launch {
            settings.acceptConsent(Limits.CURRENT_CONSENT_VERSION)
            _done.send(Unit)
        }
    }
}
