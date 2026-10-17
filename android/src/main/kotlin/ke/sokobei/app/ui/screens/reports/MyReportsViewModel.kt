package ke.sokobei.app.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.sokobei.app.data.db.PendingReportEntity
import ke.sokobei.app.data.repository.ReportRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MyReportsUiState(val loaded: Boolean = false, val reports: List<PendingReportEntity> = emptyList())

@HiltViewModel
class MyReportsViewModel @Inject constructor(reports: ReportRepository) : ViewModel() {
    val state: StateFlow<MyReportsUiState> = reports.observeReports()
        .map { MyReportsUiState(true, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyReportsUiState())
}
