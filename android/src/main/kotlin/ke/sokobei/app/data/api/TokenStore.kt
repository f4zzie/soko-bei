package ke.sokobei.app.data.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Persists the JWT access token and opaque refresh token. */
interface TokenStore {
    val loggedIn: Flow<Boolean>
    suspend fun access(): String?
    suspend fun refresh(): String?
    suspend fun save(access: String, refresh: String)
    suspend fun updateAccess(access: String)
    suspend fun clear()
}

class DataStoreTokenStore(private val store: DataStore<Preferences>) : TokenStore {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    override val loggedIn: Flow<Boolean> = store.data.map { it[refreshKey] != null }
    override suspend fun access(): String? = store.data.first()[accessKey]
    override suspend fun refresh(): String? = store.data.first()[refreshKey]

    override suspend fun save(access: String, refresh: String) {
        store.edit { it[accessKey] = access; it[refreshKey] = refresh }
    }

    override suspend fun updateAccess(access: String) {
        store.edit { it[accessKey] = access }
    }

    override suspend fun clear() {
        store.edit { it.remove(accessKey); it.remove(refreshKey) }
    }
}
