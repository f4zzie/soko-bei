package ke.sokobei.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ke.sokobei.app.ui.MainViewModel
import ke.sokobei.app.ui.SokoBeiRoot
import ke.sokobei.app.ui.components.LoadingBox
import ke.sokobei.app.ui.theme.SokoBeiTheme

/** AppCompatActivity so AppCompatDelegate.setApplicationLocales works on every API level. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SokoBeiTheme {
                val onboarded by vm.onboarded.collectAsStateWithLifecycle()
                // The start destination is decided once, when the preference first loads.
                val initial = remember(onboarded == null) { onboarded }
                if (initial == null) LoadingBox() else SokoBeiRoot(onboarded = initial)
            }
        }
    }
}
