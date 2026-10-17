package ke.sokobei.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

enum class RefreshResult { OK, OFFLINE, ERROR }

/** Local, non-secret preferences. */
interface AppSettings {
    /** True once the user has passed the welcome + consent screen. */
    val onboarded: Flow<Boolean>
    /** Consent version the user accepted on this device, or null. */
    val consentVersion: Flow<Int?>
    val selectedMarketId: Flow<String?>
    suspend fun acceptConsent(version: Int)
    suspend fun setSelectedMarket(marketId: String)
    suspend fun clear()
}

class DataStoreAppSettings(private val store: DataStore<Preferences>) : AppSettings {
    private val consentKey = intPreferencesKey("consent_version")
    private val onboardedKey = booleanPreferencesKey("onboarded")
    private val marketKey = stringPreferencesKey("selected_market")

    override val onboarded: Flow<Boolean> = store.data.map { it[onboardedKey] == true }
    override val consentVersion: Flow<Int?> = store.data.map { it[consentKey] }
    override val selectedMarketId: Flow<String?> = store.data.map { it[marketKey] }

    override suspend fun acceptConsent(version: Int) {
        store.edit { it[consentKey] = version; it[onboardedKey] = true }
    }

    override suspend fun setSelectedMarket(marketId: String) {
        store.edit { it[marketKey] = marketId }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }
}
