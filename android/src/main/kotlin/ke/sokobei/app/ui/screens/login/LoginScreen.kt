package ke.sokobei.app.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.sokobei.app.R
import ke.sokobei.app.ui.components.MinTouch
import ke.sokobei.app.ui.components.SmallTextButton

@Composable
fun LoginScreen(onDone: () -> Unit, onSkip: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (s.step == LoginStep.PHONE) R.string.login_phone_body else R.string.login_code_body),
            style = MaterialTheme.typography.bodyLarge,
        )

        when (s.step) {
            LoginStep.PHONE -> OutlinedTextField(
                value = s.phone, onValueChange = vm::onPhoneChange, singleLine = true,
                label = { Text(stringResource(R.string.phone_label)) },
                placeholder = { Text(stringResource(R.string.phone_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = s.error == LoginError.INVALID_PHONE,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("phone"),
            )
            LoginStep.CODE -> OutlinedTextField(
                value = s.code, onValueChange = vm::onCodeChange, singleLine = true,
                label = { Text(stringResource(R.string.code_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = s.error == LoginError.INVALID_CODE,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).testTag("code"),
            )
        }

        s.error?.let { e ->
            val text = stringResource(
                when (e) {
                    LoginError.INVALID_PHONE -> R.string.error_invalid_phone
                    LoginError.INVALID_CODE -> R.string.error_invalid_code
                    LoginError.NETWORK -> R.string.error_network
                    LoginError.RATE_LIMITED -> R.string.error_rate_limited
                    LoginError.SERVER -> R.string.error_server
                },
            )
            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { if (s.step == LoginStep.PHONE) vm.sendCode() else vm.verify() },
            enabled = !s.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("primary"),
        ) {
            if (s.busy) CircularProgressIndicator(Modifier.height(24.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (s.step == LoginStep.PHONE) R.string.send_code else R.string.verify))
        }
        if (s.step == LoginStep.CODE) SmallTextButton(stringResource(R.string.change_number), vm::backToPhone)
        SmallTextButton(stringResource(R.string.not_now), onSkip)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_why), style = MaterialTheme.typography.bodySmall)
    }
}
