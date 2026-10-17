package ke.sokobei.app.ui.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.sokobei.app.R
import ke.sokobei.app.ui.components.MinTouch

@Composable
fun WelcomeScreen(onDone: () -> Unit, vm: WelcomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)

        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = state.language == "en", onClick = { vm.setLanguage("en") },
                label = { Text(stringResource(R.string.language_english)) },
                modifier = Modifier.heightIn(min = MinTouch).testTag("lang_en"),
            )
            FilterChip(
                selected = state.language == "sw", onClick = { vm.setLanguage("sw") },
                label = { Text(stringResource(R.string.language_swahili)) },
                modifier = Modifier.heightIn(min = MinTouch).testTag("lang_sw"),
            )
        }

        Text(stringResource(R.string.consent_body), style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("consent_row")
                .toggleable(value = state.consentChecked, role = Role.Checkbox, onValueChange = vm::setConsent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.consentChecked, onCheckedChange = null)
            Text(stringResource(R.string.consent_checkbox), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = vm::accept, enabled = state.canContinue,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("continue"),
        ) { Text(stringResource(R.string.continue_label)) }
    }
}
