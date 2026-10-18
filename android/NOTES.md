# Soko Bei Android - notes

## Toolchain
AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1, Room 2.8.5, Ktor client 3.6.0, JDK 21, compileSdk 37 (android-37.0 installed), Robolectric sdk=36.
AGP 9 worked as is; no fallback to AGP 8 was needed.

## Architecture
- Offline-first: ReportViewModel -> ReportRepositoryImpl.submit() writes `pending_reports` (UUID v4 id and reported_at_ms taken at tap time), enqueues sync, and the screen navigates back immediately. No network on that path.
- Cached in Room: markets, commodities (grades, units), market/commodity prices, trend JSON, `cache_meta` (drives "last updated" labels).
- Tokens in DataStore (DataStoreTokenStore). Ktor Auth (bearer) plugin refreshes on 401 using a separate plain client; a rejected refresh clears the session.
- BuildConfig.BASE_URL: debug http://10.0.2.2:8080 (cleartext allowed only via debug network security config), release https (override with -PsokoReleaseBaseUrl).
- Language: AppCompatDelegate.setApplicationLocales (runtime switch); strings in values/ and values-sw/ (89 keys each).

## WorkManager policy
- Periodic (6h, CONNECTED, exponential backoff 30s): `enqueueUniquePeriodicWork(KEEP)` so repeated app starts do not reset the timer.
- One-off after submit/login/app start: `enqueueUniqueWork(APPEND_OR_REPLACE)` so a trigger during a running sync is queued behind it (a report saved mid-run is not missed) and a failed/cancelled predecessor does not block the chain.
- Worker: DONE -> success, transient (network, 5xx, 408, 429, unresolved 401 with session) -> retry. 409 and other 4xx -> REJECTED (permanent). Pending >14 days -> EXPIRED, never sent. 403 CONSENT_REQUIRED -> posts consent once if the user agreed on the welcome screen, else waits.

## Fixes made in this pass
1. Real bug: `sendWithoutRequest` path matching used a leading slash ("/v1/reports") but the Auth plugin sees the relative path, so the bearer header was never attached proactively (every report POST cost a 401 round trip and the refresh path was not exercised as intended). Now matches without the slash.
2. Test infra: Robolectric 4.17 needs `--add-exports/--add-opens java.base/jdk.internal.access` on JDK 21 (build.gradle.kts testOptions).
3. Tests that were wrong: LoginViewModelTest expected "0000" after filtering "00a000" (digits give "00000"); MyReports test used a StateFlow fake plus an Unconfined dispatcher so "not loaded" could never be observed - now a non-replaying SharedFlow. ReportFormComposeTest clicked Submit while off-screen in a scrollable column; now `performScrollTo()` first.
4. Added SyncSchedulerTest (unique names, KEEP, APPEND_OR_REPLACE, CONNECTED).

## Verified
Initial run (current total is 82 unit tests, see PROGRESS.md): `./gradlew :android:testDebugUnitTest :android:assembleDebug --no-daemon`: 74 tests, 0 failures (plus 2 SyncSchedulerTest run afterwards, passing). Covers ViewModels (fakes + Turbine), Room DAOs (Robolectric), ReportSyncer and SyncWorker over Ktor MockEngine (201/200, 409, 400, 500, IO, 429, 401->refresh->success, refresh rejected, expired), report form Compose UI test (live price/kg, offline submit), PriceMath/PricePreview.

## Not verified
- Instrumented tests exist now (see below), but there is still no manual run of the 8 screens, no real WorkManager scheduling (periodic/constraints) on a device, release build only checked statically (see Release build / R8), layouts only checked through Robolectric.
- Never run against the real backend; the 401 contract was checked only with mocks. Note the backend's 401 challenge sends no `WWW-Authenticate` header; Ktor's Auth handles this because there is a single provider, but this is covered only by MockEngine tests with the same shape.
- Kiswahili strings need native-speaker review.

## API/DTO observations
No DTO changes needed. Backend module has a failing CqlSplitterTest (expects migrations [1,2], V003/V004 exist) - out of scope, not touched.

## Release build / R8
Command: `./gradlew :android:assembleRelease --no-daemon` (minify + shrinkResources on, unsigned APK `build/outputs/apk/release/android-release-unsigned.apk`, no signing config added).
Result: BUILD SUCCESSFUL, no R8 "Missing classes" errors, so no rule changes were needed. (Note: `minifyReleaseWithR8` reported UP-TO-DATE in that run, i.e. R8 had already executed with the current inputs and rules; outputs below are from that execution.)

Rules in `proguard-rules.pro` (unchanged, all necessary):
- `-keepattributes *Annotation*, InnerClasses`, serializer rules for `ke.sokobei.**` (`$$serializer`, `Companion`, `serializer(...)`): kotlinx.serialization (the shared DTOs live in `ke.sokobei.shared`, covered by the `ke.sokobei.**` pattern). The library ships its own consumer rules as well.
- `-dontwarn org.slf4j.**`, `-dontwarn io.ktor.**`: Ktor references slf4j/JVM-only classes absent on Android. (`io.ktor.**` is a broad dontwarn; narrow it if warnings ever matter.)
- Room, Hilt/Dagger, WorkManager, androidx.startup, DataStore and OkHttp rely on their own consumer rules (AGP merges them); nothing extra was added.

Mapping evidence (`build/outputs/mapping/release/mapping.txt`, run from `android/build/outputs/mapping/release`):
- `grep -F 'ke.sokobei.shared.ReportRequest$$serializer ->' mapping.txt` -> `ke.sokobei.shared.ReportRequest$$serializer -> ke.sokobei.shared.ReportRequest$$serializer:` (kept, same for `ReportDto$$serializer`, `AuthTokens$$serializer`, `TrendResponse$$serializer`).
- `ke.sokobei.shared.ReportDto -> ke.sokobei.shared.ReportDto:` kept, but its fields are renamed (`reportId -> a`). This is fine: the JSON names live in the serializer descriptor strings, not in Kotlin field names.
- JSON names present in the APK dex strings (`unzip classes*.dex; cat classes*.dex | strings | grep -c NAME`, each >= 1): report_id, reported_at_ms, received_at_ms, market_id, commodity_id, price_kes, kg_per_unit, price_per_kg_kes, median_per_kg, report_count, refresh_token, next_page_token, consent_version, pairs_processed.
- `ke.sokobei.shared.BigDecimalSerializer -> tl` is renamed; this is OK since it is referenced directly by the generated serializers (not via reflection).
- Room: `SokoBeiDatabase` and `SokoBeiDatabase_Impl` kept by name (Room's consumer rules). The DAO impls (`CatalogDao_Impl -> ms`, `PriceDao_Impl -> zc2`, `ReportDao_Impl -> oi2`) and entities (`PendingReportEntity -> o72`, ...) are renamed, which is expected and safe: they are constructed by generated code directly, not via reflection.
- Hilt/WorkManager: `SokoBeiApp`, `MainActivity` and `SyncWorker` kept (seeds.txt lists `ke.sokobei.app.data.sync.SyncWorker`).

Runtime guard: `WireFormatTest` (3 tests) encodes/decodes ReportRequest, ReportDto, TrendResponse with the app's `ApiJson` and asserts the snake_case names and BigDecimal handling. It runs against unminified debug classes, so it protects against DTO renames, not against R8 problems.
`./gradlew :android:testDebugUnitTest --no-daemon`: 79 tests, 0 failures (76 previous + 3).

Not verified: the minified APK was never installed or run on a device/emulator (no Ktor/Room/Hilt/WorkManager behaviour tested under R8 at runtime); the release APK is unsigned; BASE_URL for release is still the placeholder `https://api.sokobei.example` unless `-PsokoReleaseBaseUrl` is passed.

## Instrumented tests
Location: `android/src/androidTest/kotlin/ke/sokobei/app/` (runner `androidx.test.runner.AndroidJUnitRunner`, no Hilt test runner: tests build their own in-memory Room DB and fakes, `support/Fakes.kt` + `TestDb.kt` are copies of the unit-test support code).
- `data/RoomDaoInstrumentedTest` (9): real `SokoBeiDatabase` in memory on the device: report insert/status updates, ordering, expiry, catalog/price/trend caches.
- `ui/ReportFormInstrumentedTest` (3): real `ReportScreen` + `ReportViewModel` + Room-backed `ReportRepositoryImpl`; live price/kg (maize sack, and 1 crate of tomatoes at 4500 KES with a 60 kg factor -> 75.00), offline submit stores a PENDING row. Waits with `rule.waitUntil`, no sleeps.
- `sync/SyncWorkerInstrumentedTest` (7): real `SyncWorker` via `TestListenableWorkerBuilder` over Ktor MockEngine: 201/200 -> SYNCED, 409 -> REJECTED, 500 and IO error -> retry with row PENDING, 401 -> refresh -> success, >14 days -> EXPIRED and not sent.
- `security/CleartextPolicyInstrumentedTest` (1): `NetworkSecurityPolicy` on the debug build: localhost and 10.0.2.2 cleartext true, example.com false.

Run (emulator must be booted, `ANDROID_AVD_HOME` may be `~/.config/.android/avd` depending on the machine):
```
sdkmanager "emulator" "system-images;android-34;google_apis;x86_64" "platforms;android-34"
avdmanager create avd -n soko_test -k "system-images;android-34;google_apis;x86_64" -d pixel_5 --force
emulator -avd soko_test -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 2048 &
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 0; done
./gradlew :android:connectedDebugAndroidTest --no-daemon
```
Result (AVD soko_test, Pixel 5, Android 14 / API 34, google_apis x86_64, 2048 MB, KVM): 20 tests, 0 failures. Reports: `android/build/reports/androidTests/connected/`.
Notes: the suite takes about 5 minutes on a 4 core machine with software rendering (instrumentation start alone is ~45 s). `createComposeRule` (v1) is deprecated in favour of `androidx.compose.ui.test.junit4.v2`; kept to mirror the Robolectric test. One earlier run reported BUILD SUCCESSFUL with an empty (0 test) result file; it did not reproduce in three later runs and the cause is unknown.
