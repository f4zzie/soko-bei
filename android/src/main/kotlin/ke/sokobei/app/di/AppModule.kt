package ke.sokobei.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import ke.sokobei.app.BuildConfig
import ke.sokobei.app.data.api.ApiClientFactory
import ke.sokobei.app.data.api.DataStoreTokenStore
import ke.sokobei.app.data.api.SokoBeiApi
import ke.sokobei.app.data.api.TokenStore
import ke.sokobei.app.data.db.CatalogDao
import ke.sokobei.app.data.db.PriceDao
import ke.sokobei.app.data.db.ReportDao
import ke.sokobei.app.data.db.SokoBeiDatabase
import ke.sokobei.app.data.repository.AppSettings
import ke.sokobei.app.data.repository.AuthRepository
import ke.sokobei.app.data.repository.AuthRepositoryImpl
import ke.sokobei.app.data.repository.CatalogRepository
import ke.sokobei.app.data.repository.CatalogRepositoryImpl
import ke.sokobei.app.data.repository.Clock
import ke.sokobei.app.data.repository.DataStoreAppSettings
import ke.sokobei.app.data.repository.PriceRepository
import ke.sokobei.app.data.repository.PriceRepositoryImpl
import ke.sokobei.app.data.repository.ReportRepository
import ke.sokobei.app.data.repository.ReportRepositoryImpl
import ke.sokobei.app.data.sync.ReportSyncer
import ke.sokobei.app.data.sync.SyncScheduler
import ke.sokobei.app.data.sync.WorkManagerSyncScheduler
import ke.sokobei.app.ui.AppLanguage
import ke.sokobei.app.ui.LanguageController
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun clock(): Clock = Clock.System

    @Provides @Singleton @Named("tokens")
    fun tokensStore(@ApplicationContext c: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { c.preferencesDataStoreFile("tokens") }

    @Provides @Singleton @Named("settings")
    fun settingsStore(@ApplicationContext c: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { c.preferencesDataStoreFile("settings") }

    @Provides @Singleton fun tokenStore(@Named("tokens") ds: DataStore<Preferences>): TokenStore = DataStoreTokenStore(ds)
    @Provides @Singleton fun appSettings(@Named("settings") ds: DataStore<Preferences>): AppSettings = DataStoreAppSettings(ds)

    @Provides @Singleton
    fun database(@ApplicationContext c: Context): SokoBeiDatabase =
        Room.databaseBuilder(c, SokoBeiDatabase::class.java, "sokobei.db").build()

    @Provides fun catalogDao(db: SokoBeiDatabase): CatalogDao = db.catalogDao()
    @Provides fun priceDao(db: SokoBeiDatabase): PriceDao = db.priceDao()
    @Provides fun reportDao(db: SokoBeiDatabase): ReportDao = db.reportDao()

    @Provides @Singleton
    fun httpClient(tokens: TokenStore): HttpClient = ApiClientFactory.create(OkHttp.create(), BuildConfig.BASE_URL, tokens)

    @Provides @Singleton fun api(client: HttpClient): SokoBeiApi = SokoBeiApi(client)

    @Provides @Singleton
    fun syncScheduler(@ApplicationContext c: Context): SyncScheduler = WorkManagerSyncScheduler(c)

    @Provides @Singleton
    fun syncer(api: SokoBeiApi, dao: ReportDao, tokens: TokenStore, settings: AppSettings, clock: Clock) =
        ReportSyncer(api, dao, tokens, settings, clock)

    @Provides @Singleton
    fun catalogRepo(api: SokoBeiApi, c: CatalogDao, p: PriceDao, clock: Clock): CatalogRepository =
        CatalogRepositoryImpl(api, c, p, clock)

    @Provides @Singleton
    fun priceRepo(api: SokoBeiApi, p: PriceDao, clock: Clock): PriceRepository = PriceRepositoryImpl(api, p, clock)

    @Provides @Singleton
    fun reportRepo(dao: ReportDao, scheduler: SyncScheduler, clock: Clock): ReportRepository =
        ReportRepositoryImpl(dao, scheduler, clock)

    @Provides @Singleton
    fun authRepo(api: SokoBeiApi, t: TokenStore, s: AppSettings, r: ReportDao, sch: SyncScheduler): AuthRepository =
        AuthRepositoryImpl(api, t, s, r, sch)

    @Provides @Singleton
    fun language(): LanguageController = AppLanguage()
}
