# PROGRESS

## Verified by running
- Phase 1 skeleton: `./gradlew build` passed (before Android was added). The compose stack was NOT started (no Docker access).
- Phase 2: migrations V001-V004 apply from scratch, rerun is a no-op, tampered checksum blocks startup (MigrationIT 2/2).
- Phase 3: domain unit tests pass.
- Phases 4-6 (auth, write path, reads, jobs, rebuild, deletion, metrics): full backend suite = 84 tests, 0 failures, against native Cassandra 5.0.9 + MongoDB 8.0.34 (`SOKO_TEST_CASSANDRA=localhost:9042 SOKO_TEST_MONGO=mongodb://localhost:27017 ./gradlew :backend:test`, run from a mirror dir without :android).
- Smoke test: `scripts/smoke-test.sh` ALL STEPS PASSED against a natively started backend (DEV_MODE=true).
- Full gate: `./gradlew build` (shared, backend incl. 84 integration/unit tests, android incl. unit tests + lint) BUILD SUCCESSFUL in 9m32s with SOKO_TEST_CASSANDRA/SOKO_TEST_MONGO set. Not from a clean checkout (build caches and daemons were warm).
- Phases 7-8 (Android): per its agent, `:android:testDebugUnitTest` + `:android:assembleDebug` BUILD SUCCESSFUL, 74 tests / 0 failures, plus a later SyncSchedulerTest (2 tests) run alone. Not re-run by me.

- Docker gates: `docker compose up -d --build` OK (Cassandra, MongoDB, backend healthy; /health shows both up); smoke test ALL STEPS PASSED; then `docker compose down -v && docker compose up -d` + smoke test ALL STEPS PASSED. Fixes needed on the way: Dockerfile drops the :android include (image only copies shared+backend) and retries Gradle with longer HTTP timeouts (container downloads timed out repeatedly on this network).

- FINAL FULL RUN (clean, no build cache, Testcontainers path, no SOKO_TEST_* vars, Gradle run with the docker group): `./gradlew clean build --no-build-cache` BUILD SUCCESSFUL in 35m27s. Backend 84 tests / 0 failures (12 suites, Cassandra 5.0 + Mongo 8 started by Testcontainers); Android 76 unit+Robolectric tests / 0 failures (13 suites) plus lint and assemble; shared has no tests.
- After that: `docker compose down -v && up -d --build` + smoke test ALL STEPS PASSED; /metrics exposes soko_job_* gauges.

## Follow-up round (admin token, startup guard, deletion, conversion policy, R8)
- Final gate: `./gradlew clean build :android:assembleRelease --no-build-cache` (Gradle run with the docker group, Testcontainers Cassandra 5.0 + MongoDB 8, no SOKO_TEST_* vars): BUILD SUCCESSFUL in 43m32s. Backend 98 tests / 0 failures (14 suites); Android 79 tests / 0 failures (14 suites); release APK (unsigned) and R8 mapping produced.
- `docker compose down -v && docker compose up -d --build` then `scripts/smoke-test.sh`: ALL STEPS PASSED.
- New tests: AdminTokenIT (3), StartupGuardTest (10), crash-recovery deletion test in AccountIT, conversion-policy test in ReportsIT, WireFormatTest (3, Android).
- R8: existing keep rules sufficient; mapping inspection shows serializers and JSON names intact (details in android/NOTES.md). Minified APK never run on a device.

## Staging build, release guard, fresh-cache build, device checklist
- Android `staging` build type (inherits release: R8/shrink/keep rules; debug keystore; `ke.sokobei.app.staging`; URL from `-PsokoBaseUrl`, default `http://localhost:8080`). `./gradlew :android:assembleStaging` OK. APK inspected: package `ke.sokobei.app.staging`, signer `CN=Android Debug`, `http://localhost:8080` in dex, R8 mapping present with the shared `$$serializer` classes kept, network config in the APK = cleartext false + only `localhost` and `10.0.2.2`.
- Release guard: `assembleRelease` without `-PsokoReleaseBaseUrl` FAILS ("still the placeholder"); with `http://...` FAILS ("must be https"); with `https://api.sokobei.test` BUILD SUCCESSFUL. `assemble`/`build` now cover debug and staging only (dry-run: assembleDebug, assembleStaging).
- Release forbids cleartext: `NetworkSecurityConfigTest` (3 tests) + `verifyReleaseNetworkSecurity` task over the merged release manifest (in `check`); `./gradlew :android:check` BUILD SUCCESSFUL.
- Fresh-cache build: repo copied to a new temp dir, `GRADLE_USER_HOME=$(mktemp -d)`, run as `sudo -u arch -g docker`: `./gradlew clean build :android:assembleStaging --no-build-cache` BUILD SUCCESSFUL in 43m 5s (ELAPSED 2588 s). Backend 98 tests / 0 failures (Testcontainers), Android 79 debug unit tests / 0 failures, staging APK produced. Caveat: my copy excluded every directory named `build`, which silently dropped the new test package `ke.sokobei.app.build`; I renamed it `ke.sokobei.app.security` (a package named `build` would also be ignored by `.gitignore`/`.dockerignore` rules) and re-ran `:android:check` in the same clone with the then-warm cache: BUILD SUCCESSFUL in 2m 39s, including the 3 network-security tests. The full 43-minute run itself therefore did not include those 3 tests.
- Old compose service name: grep for `mongo:27017`, `//mongo`, `up -d ... mongo` finds none; remaining `mongo` hits are the `mongo:8` image and the DECISIONS note.
- `docs/device-test-checklist.md` written. NOT executed on a phone (no device available): it is a procedure, unverified until someone runs it. The final compose + smoke gate was not re-run this round (no backend or compose changes).

## Not verified
- Android instrumented tests, any run on an emulator or phone (including the new device checklist), Android against the real backend.
- A build on a different machine / fresh Gradle cache (dependency caches were warm).
- Kiswahili strings need native-speaker review; seed unit conversions are placeholders.
