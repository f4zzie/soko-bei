package ke.sokobei.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.repository.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(settings: AppSettings) : ViewModel() {
    /** null while the preference is loading; true once the welcome/consent screen has been accepted. */
    val onboarded: StateFlow<Boolean?> = settings.onboarded
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
