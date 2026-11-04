# Presenting Soko Bei on an unplugged phone

**These prices are seeded demo data, not real market observations. All non-kg unit conversion factors are still unverified placeholders.** A crate-to-kg calculation demonstrates the software, not a field-verified weight.

## Start the demo

From the repository root, as your normal desktop user:

```sh
scripts/demo-up.sh
```

You need Docker with Compose and a running daemon, `curl`, `jq`, JDK 21, and the Android SDK/build-tools configured through `ANDROID_HOME` or `local.properties`. Standard Linux tools (`unzip`, `strings`, `sha256sum`) must be installed. The script downloads the official Cloudflare `cloudflared` binary to `~/.local/bin` if necessary; no account or sudo is needed.

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

## Five-minute walkthrough

| Time | Action |
| --- | --- |
| 0:00–0:45 | Browse Wakulima Market. Explain today's typical price, latest report and number of reports. Say these are demo prices. |
| 0:45–1:20 | Tap Tomatoes and compare it across the five markets. |
| 1:20–1:50 | Open its price trend. Historical demo reports span the last 12 Nairobi calendar days. |
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
