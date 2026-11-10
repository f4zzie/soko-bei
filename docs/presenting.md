# Presenting Soko Bei on an unplugged phone

**These prices are seeded demo data, not real market observations. All non-kg unit conversion factors are still unverified placeholders.** A crate-to-kg calculation demonstrates the software, not a field-verified weight.

## Start the demo

From the repository root, as your normal desktop user:

```sh
scripts/demo-up.sh
```

You need Docker with Compose and a running daemon, `curl`, `jq`, JDK 21, and the Android SDK/build-tools configured through `ANDROID_HOME` or `local.properties`. Standard Linux tools (`unzip`, `strings`, `sha256sum`) must be installed. The script downloads the official Cloudflare `cloudflared` binary to `~/.local/bin` if necessary; no account or sudo is needed.

If MongoDB refuses to start with SERVER-121912 on a newer Linux kernel, use `MONGO_IMAGE=mongo:7.0 scripts/demo-up.sh` with a compatible MongoDB 7 data volume. This is the command used for the verified local presentation. Keep that override on subsequent runs. An existing MongoDB 8 data volume cannot be downgraded by changing the image; retain it and use a supported migration or separate storage. The launcher never resets database volumes.

The script starts Cassandra, MongoDB and the backend with `DEV_MODE=true`, explicitly closes the admin API, loads 150 reports through the normal API, opens a public HTTPS quick tunnel over HTTP/2, waits for its health check, and builds the R8-minified `staging` APK with that URL. HTTP/2 uses TCP for the connector, avoiding dependence on venue Wi-Fi passing QUIC/UDP traffic. It verifies the application ID, debug-key signature, embedded HTTPS URL and absence of the localhost default before replacing `soko-bei-staging.apk` on your Desktop. The exact path, SHA-256, build time, tunnel URL and process ID are printed.

**Leave the terminal running.** Ctrl+C stops its tunnel and leaves the backend/data running. To also stop the compose services on exit, start it with `scripts/demo-up.sh --down` (volumes/data are retained). For a detached run, stop the printed script PID with `kill -TERM <PID>`; do not use `kill -9`, which prevents cleanup. Runtime logs and PID files are in the printed temporary directory, outside the repository.

Every quick-tunnel restart gets a new URL. The URL is baked into the APK, so **rebuild and reinstall whenever the tunnel restarts**. Running `scripts/demo-up.sh` again does the rebuild and replaces the Desktop APK; install that fresh file on the phone. Stop the earlier script first.

### Install on the phone

1. Copy the Desktop APK using USB **file transfer**, Google Drive, Quick Share or Telegram. USB debugging and `adb reverse` are unnecessary; disconnect the cable for the demo.
2. Open the APK on the phone. Allow **Install unknown apps** for the app opening the file (Files, Drive, Telegram, etc.).
3. Tap Install. If Play Protect warns about this unverified demo app, choose **Install anyway** (sometimes under More details).
4. Open Soko Bei, accept consent and log in with a Kenyan mobile number and OTP **000000**. This is a dev-only login; no SMS delivery is needed.

Staging uses `ke.sokobei.app.staging` and the debug keystore. Reinstallation from the same laptop/key updates it in place. HTTPS works with the existing staging network security policy; plain HTTP remains restricted to `localhost` and `10.0.2.2`. No permissive network-security override is needed.

## Pre-demo checklist

- About **one hour before**, run the script and check it reaches **DEMO READY**. Install the fresh Desktop APK.
- Leave the script running and the laptop awake: disable suspend/lid-close sleep, and keep it plugged in. Power alone may not prevent suspend.
- Prefer the **phone's hotspot for the laptop** to unreliable venue Wi-Fi. During airplane mode this also cuts the laptop's internet; after reconnecting, allow the hotspot and tunnel to recover before checking sync.
- Run the entire sequence below once, including airplane mode **with Wi-Fi off**. Record a backup **screen recording on the phone**.
- Check the phone shows seeded market prices and a trend. Confirm both offline reports become Synced after reconnecting.
- Avoid unnecessary OTP requests (three per number per 15 minutes) and repeated live submissions (30 per user per hour). Use your own demo login rather than the seeder's five accounts.

## Large dataset on an already-running demo

Use this sequence **without rerunning `demo-up.sh`**: keep the existing launcher, Cloudflare connector and APK URL. The helpers below use the running backend container and never restart the tunnel, recreate databases or build an APK. Keep the current demo's `MONGO_IMAGE=mongo:7.0` setting. Python 3 (standard library only) is required for the API feeders.

1. **Back up before the first load.** Record the snapshot tag and keep the Mongo archive outside the repository:

   ```sh
   umask 077
   tag="soko-bei-before-large-demo-$(date +%Y%m%dT%H%M%S)"
   backup="$HOME/.local/state/soko-bei/backups/$tag"
   mkdir -p "$backup"
   docker exec soko-cassandra nodetool snapshot -t "$tag" soko_bei
   docker exec soko-mongo mongodump --db soko_bei --archive --gzip > "$backup/mongodb.archive.gz"
   gzip -t "$backup/mongodb.archive.gz"
   ```

   Cassandra retains the snapshot under `/var/lib/cassandra/data/soko_bei/<table>/snapshots/<tag>/` in its existing volume. Copy those directories to the host for a separate backup, and record hashes plus the original report IDs/rows before loading. A snapshot integrity check is not a restoration rehearsal. The actual independently archived backup and preservation evidence for this demo are recorded in [PROGRESS.md](../PROGRESS.md).

2. **Use a backend containing the demo commands.** If upgrading an older backend image, deploy only that service:

   ```sh
   MONGO_IMAGE=mongo:7.0 ADMIN_TOKEN='' docker compose up -d --no-deps --build backend
   ```

   This permits a brief backend outage while leaving the connector and URL intact. Verify `curl -fsS "$BASE_URL/health"` through the existing tunnel afterwards. No backend restart is needed between catalog loading, backfilling and API feeding.

3. **Extend the catalog and load past history.** Commands default to `BACKEND_CONTAINER=soko-backend`; each command checks the effective backend `DEV_MODE=true` before connecting/writing. No migration runs in these commands.

   ```sh
   scripts/demo-catalog.sh
   scripts/demo-backfill.sh --days 90 --seed 42 --concurrency 2 --batch-size 64
   ```

   The ten added markets are Gikomba, Muthurwa and Kangemi (Nairobi), Githurai 45 (Kiambu), Karatina (Nyeri), Daraja Mbili (Kisii), Eldoret Main (Uasin Gishu), Kitale Municipal (Trans Nzoia), Gakoromone (Meru), and Machakos Main (Machakos). Coordinates are approximate. The eight new bilingual commodities, grades and placeholder weights are listed in [README's field-data table](../README.md#data-to-verify-in-the-field).

   `--days 90` selects the inclusive 90-day EAT window ending today, with **89 completed days written server-side**; today is reserved for step 4. `--through YYYY-MM-DD` pins the ending date for a repeatable range; it cannot be in the future. IDs and payloads depend on dataset version, seed, EAT date, market, commodity and ordinal, rather than the run time. Replaying the same dates/catalog/seed reports `created=0` and preserves source rows. A later rolling run adds only the new date's IDs; it does not remove older history. Keep seed 42 for this dataset. `--markets wakulima,karatina` is useful for a small two-market run; `--days 4` gives three past days plus today.

   To replay this presentation's completed history on a later date, pin **89 days ending 2026-11-04**: `scripts/demo-backfill.sh --days 89 --through 2026-11-04 --seed 42`. That range is 2026-08-08–2026-11-04. An end date equal to the current day is excluded from history only while it is still today; use an already-completed ending date when exact later replay is required.

   History uses the API's `ReportService`, validator, unit factors/version snapshots, deterministic timeuuids, and logged source/user/locator write batch. Only the separately guarded CLI widens the age window; HTTP still rejects new reports over 14 days old. Defaults are at most two reports in flight and 64 queued per batch (allowed maxima: four and 128). Each date prints progress, followed by a full-range in-process rebuild using the existing recompute service; the demo rebuild does not remove existing rows. The admin HTTP API remains closed.

   The 40 synthetic accounts are `0700002000`–`0700002039`, created through dev OTP verification and normal consent handling. The loader refuses to adopt a pre-existing, unmarked account in that range. Mongo's demo-only `demo_large_users` collection keeps their identities stable across interrupted runs. No SMS is sent.

4. **Fill today's board through the public API.** Set `BASE_URL` to the **already-running** HTTPS tunnel URL printed by the original launcher; never commit its concrete value.

   ```sh
   export BASE_URL='https://<existing-tunnel-host>'
   scripts/demo-today.sh
   ```

   The backend creates a deterministic current-day plan, then the script posts it through HTTPS. It includes a synthetic opening-board sample for each of the 270 market/commodity pairs, followed by market-hour trades whose timestamps have already elapsed today, capped at 1,000 posts (at most 25 per account). The opening board is deliberately available just after midnight EAT so a late-night presentation can still show every commodity. Price levels, grades and units come from the same scenario as the history. This is generated demo data, including the early-hours samples.

   Both feeders require healthy public `dev_mode=true`. Tokens stay in memory. Successful today IDs and conservative per-user hourly attempt budgets are checkpointed outside the repository under `${XDG_STATE_HOME:-$HOME/.local/state}/soko-bei/demo-feed/`. Reruns skip completed IDs; an interrupted request reuses its report ID. Keep this shared state when restarting feeders. Server 429/`Retry-After` is authoritative; the script waits rather than raising quotas. `--reports-per-hour N` can lower its local budget if the backend uses less than 30. Do not run the old small seeder as part of this sequence.

5. **Rehearse and start the live trickle.** Refresh Markets in the app; scroll through all commodities, compare a commodity across 15 markets, and choose **90 days** on its trend. My reports is the device's local submission history, so it retains that phone's reports rather than displaying the 40 server-side users' history.

   On the updated chart, **Median focus** keeps seasonal movement and short shocks readable even with extra-zero reports. The numeric axis is KES/kg; a notice identifies clipped shaded extremes. **Full range** restores every minimum/maximum to the plot, and the period low/high labels always retain the true values. Missing dates occupy their actual calendar spacing. The chart change was prompted by the observed 90-day display; it does not filter reports or alter prices.

   ```sh
   scripts/demo-trickle.sh --duration 3600
   ```

   Omit `--duration` to run until Ctrl+C. The first report is posted immediately, then one every randomly selected 20–40 seconds; rate-limit waits and network retries can lengthen an interval. The script rotates across the 40 demo accounts and preserves the same report ID on a retry. Stopping it leaves the backend and tunnel running. A detached invocation can redirect its output to a private log outside the repository and be stopped with `kill -TERM <trickle-PID>`. Across EAT midnight, automatic plans refresh for the new date; rerun `demo-today.sh` to fill the new board. `--plan /path/to/current-plan.json` reuses a saved plan but deliberately refuses it after its date changes.

6. **Optional operational check before presenting:** run the normal daily stats and reconciliation jobs once, in-process, with durations printed:

   ```sh
   docker exec soko-backend /app/bin/backend demo-jobs
   docker exec soko-cassandra nodetool tablestats soko_bei
   ```

   Refresh the app after this check. Leave enough time for history loading and its aggregate rebuild before starting the presentation; today's API feed then gives the latest cards real receipt times. Keep the laptop awake and the original tunnel online throughout.

## Five-minute walkthrough

| Time | Action |
| --- | --- |
| 0:00–0:45 | Browse Wakulima Market. Explain today's typical price, latest report and number of reports. Say these are demo prices. |
| 0:45–1:20 | Tap Tomatoes and compare it across 15 markets with the large dataset (five with the small seed). |
| 1:20–1:50 | Open its price trend and choose 90 days. Use Median focus to show seasonal movement and a short shock, then Full range to reveal the outliers; the small seed instead covers 12 days. |
| 1:50–2:40 | Report a price: Wakulima, Tomatoes, standard, crate, quantity 1, KES 4500. Show the live **KES 75.00/kg** preview and mention the placeholder 60 kg/crate factor. Save; show Synced in My reports. |
| 2:40–3:40 | Turn airplane mode on **and Wi-Fi off**. Save two different prices (for example 4800 and 5100). Show both Pending and the cached market data. |
| 3:40–4:35 | Reconnect, restore the hotspot if used, and watch both become Synced. Allow up to a minute; transient tunnel errors retry with backoff. |
| 4:35–5:00 | Settings → Language → Kiswahili. Return to Masoko and close with the offline-first benefit. |

For a database-backed verification, use the commands in [the device checklist](device-test-checklist.md), but use the public HTTPS URL for HTTP checks and omit `adb reverse`. Record a baseline first: this database already has seeded reports. Compare distinct new report IDs and row counts before/offline/after, rather than expecting a database containing one report.

## Seed data and reruns

`scripts/seed-demo.sh` needs `curl` and `jq`; `BASE_URL` defaults to `http://localhost:8080`. It refuses to write unless `/health` reports `dev_mode=true`. Five fixed demo accounts log in directly with dev OTP `000000` and accept the current consent version. Direct dev verification avoids spending the per-phone OTP-request quota.

Each account submits 30 reports, covering all ten commodities in one market: one report per pair today and two historical points. A fixed seed, Nairobi date and report index determine the report UUID, timestamp and payload. Same-date reruns are identical and return 200 instead of 201; the summary prints both counts. `DEMO_DATE=YYYY-MM-DD` pins the date for an exact replay; fresh reports must still be inside the 14-day acceptance window. A new calendar date deliberately produces a fresh dated dataset so today's market cards remain populated.

The API counts retries against the 30/hour quota too. The launcher recreates only the dev backend to reset its in-memory quotas before seeding; database volumes and the production rate limits are preserved. A standalone seeder rerun without a backend restart may wait up to an hour: it honours `Retry-After`, renews its login and retries the identical payload once. Unexpected statuses always fail the script. New reports update their daily trend immediately, including late/offline reports, so no admin endpoint has to be opened to prepare the demo.

## Public-tunnel security

While the tunnel is running, **anyone with its URL can reach the dev backend**, and **OTP 000000 works for them too**. Use only demo data and demo numbers. The admin API is closed (`ADMIN_TOKEN` is empty; requests return 403). Stop the tunnel after presenting.

A Cloudflare quick tunnel is temporary and has no availability guarantee. A **named Cloudflare tunnel on a domain you own** gives a stable URL, avoids rebuilding the APK whenever the connector restarts, and is the better setup beyond this demo. Production also needs real authentication, safe secrets, verified conversion factors and an appropriate deployment configuration.

## Verification limits

See [PROGRESS.md](../PROGRESS.md) for the commands actually run and results. Emulator testing is useful but does not verify physical-phone installation prompts, OEM battery restrictions, mobile carrier networking, or venue/hotspot reliability. A physical phone has not yet been used by the automated workflow; perform the pre-demo rehearsal on yours.
