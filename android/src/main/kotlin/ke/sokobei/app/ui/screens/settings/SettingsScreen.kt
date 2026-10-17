package ke.sokobei.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ke.sokobei.app.BuildConfig
import ke.sokobei.app.R
import ke.sokobei.app.data.repository.AuthResult
import ke.sokobei.app.ui.components.MinTouch

@Composable
fun SettingsScreen(onLogin: () -> Unit, onAccountDeleted: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.accountDeleted.collect { onAccountDeleted() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)

        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.titleMedium)
        LanguageOption("en", stringResource(R.string.language_english), s.language == "en", vm::setLanguage)
        LanguageOption("sw", stringResource(R.string.language_swahili), s.language == "sw", vm::setLanguage)
        HorizontalDivider()

        Text(stringResource(R.string.account), style = MaterialTheme.typography.titleMedium)
        if (s.loggedIn) {
            OutlinedButton(vm::logout, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("logout")) { Text(stringResource(R.string.logout)) }
            Button(
                vm::askDelete, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("delete_account"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.delete_account)) }
            Text(stringResource(R.string.delete_account_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.not_logged_in), style = MaterialTheme.typography.bodyMedium)
            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("login")) { Text(stringResource(R.string.login)) }
        }
        HorizontalDivider()
        Text(stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
    }

    if (s.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { if (!s.busy) vm.cancelDelete() },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_confirm_body))
                    s.deleteError?.let {
                        Text(
                            stringResource(if (it == AuthResult.Kind.NETWORK) R.string.error_network else R.string.error_server),
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(vm::confirmDelete, Modifier.heightIn(min = MinTouch).testTag("confirm_delete"), enabled = !s.busy) {
                    Text(stringResource(R.string.delete_confirm_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(vm::cancelDelete, Modifier.heightIn(min = MinTouch), enabled = !s.busy) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LanguageOption(tag: String, label: String, selected: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("setting_lang_$tag")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(tag) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
