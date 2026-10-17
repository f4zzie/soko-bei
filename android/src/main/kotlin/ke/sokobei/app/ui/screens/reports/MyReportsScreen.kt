package ke.sokobei.app.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.sokobei.app.R
import ke.sokobei.app.data.db.PendingReportEntity
import ke.sokobei.app.data.db.ReportStatus
import ke.sokobei.app.ui.components.EmptyState
import ke.sokobei.app.ui.components.agoText
import ke.sokobei.app.ui.components.kesPerKg

@Composable
fun MyReportsScreen(vm: MyReportsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.my_reports), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
        if (s.loaded && s.reports.isEmpty()) {
            EmptyState(stringResource(R.string.no_reports_yet), null, null)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().testTag("reports_list")) {
                items(s.reports, key = { it.reportId }) { ReportCard(it) }
            }
        }
    }
}

@Composable
private fun ReportCard(r: PendingReportEntity) {
    Card(Modifier.fillMaxWidth().testTag("report_${r.reportId}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(r.commodityName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge(r.status)
            }
            Text("${r.marketName} - ${r.grade}", style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.report_line, r.quantity.stripTrailingZeros().toPlainString(), r.unit, r.priceKes),
                style = MaterialTheme.typography.bodyMedium,
            )
            r.pricePerKg?.let { Text(kesPerKg(it), style = MaterialTheme.typography.bodyMedium) }
            Text(agoText(r.reportedAtMs).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
            when (r.status) {
                ReportStatus.REJECTED -> Text(
                    stringResource(R.string.rejected_reason, r.rejectReason.orEmpty()),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
                ReportStatus.EXPIRED -> Text(stringResource(R.string.expired_explain), style = MaterialTheme.typography.bodySmall)
                ReportStatus.PENDING -> Text(stringResource(R.string.pending_explain), style = MaterialTheme.typography.bodySmall)
                ReportStatus.SYNCED -> Unit
            }
        }
    }
}

@Composable
fun StatusBadge(status: ReportStatus) {
    val (label, container, content) = when (status) {
        ReportStatus.PENDING -> Triple(R.string.status_pending, Color(0xFFFFE082), Color(0xFF4E3B00))
        ReportStatus.SYNCED -> Triple(R.string.status_synced, Color(0xFFC8E6C9), Color(0xFF0B2E0F))
        ReportStatus.REJECTED -> Triple(R.string.status_rejected, Color(0xFFFFCDD2), Color(0xFF5F0A0A))
        ReportStatus.EXPIRED -> Triple(R.string.status_expired, Color(0xFFE0E0E0), Color(0xFF212121))
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("badge_${status.name}")) {
        Text(stringResource(label), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}
