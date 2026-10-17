package ke.sokobei.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

interface LanguageController {
    /** "en" or "sw". */
    fun current(): String
    /** Applies the language at runtime (activity is recreated by AppCompat). */
    fun set(tag: String)

    companion object {
        val SUPPORTED = listOf("en", "sw")
    }
}

class AppLanguage : LanguageController {
    override fun current(): String {
        val applied = AppCompatDelegate.getApplicationLocales()
        val lang = if (!applied.isEmpty) applied[0]?.language else Locale.getDefault().language
        return if (lang == "sw") "sw" else "en"
    }

    override fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
