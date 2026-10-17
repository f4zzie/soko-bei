# Device test checklist (one Android phone + one laptop)

Goal: prove on a real phone what the automated tests cannot: install, login, offline queueing, idempotent sync, token refresh, language switch and account deletion, each checked against the backend.

You need: a laptop with Docker, JDK 21 and the Android SDK platform-tools (`adb`); one Android 8.0+ phone with USB debugging on, connected by USB.

Tick each box and write what you saw in the "Result" column of the table at the end.

**Read this first**

- `adb reverse` is lost whenever the phone is unplugged and plugged back in, when `adb` restarts, or when the phone reboots. Re-run `adb reverse tcp:8080 tcp:8080` every time you reconnect (check with `adb reverse --list`).
- Debug and staging builds only allow plain HTTP to `localhost` and `10.0.2.2`. Pointing the app at the laptop's Wi-Fi IP (for example `http://192.168.1.20:8080`) will NOT work: Android blocks the cleartext request. Use USB plus `adb reverse`.
- Because `adb reverse` carries the app's traffic over the USB cable, **airplane mode does not necessarily cut the app off from the backend**. Section 4 therefore tests the "no network" case, and section 5 tests the "network present but backend unreachable" case separately. Do both; either one alone can pass for the wrong reason.

**Quick start (laptop, repo root)**

```sh
docker compose up -d --build
adb devices                                  # the phone must be listed as "device"
adb reverse tcp:8080 tcp:8080
./gradlew :android:installStaging -PsokoBaseUrl=http://localhost:8080
```

## 0. One-time setup

```sh
# Laptop, repo root. Start the stack (Cassandra, MongoDB, backend with DEV_MODE=true).
docker compose up -d --build
curl -s localhost:8080/health          # expect {"status":"ok","cassandra":"up","mongodb":"up",...}

# Build the staging APK: same R8/minify as release, signed with the debug key, installs next to debug.
./gradlew :android:assembleStaging -PsokoBaseUrl=http://localhost:8080
ls android/build/outputs/apk/staging/   # android-staging.apk

# Phone: verify the connection, install, and tunnel the phone's localhost:8080 to the laptop.
adb devices                              # your phone must be listed as "device"
adb install -r android/build/outputs/apk/staging/android-staging.apk   # or: ./gradlew :android:installStaging
adb reverse tcp:8080 tcp:8080
adb reverse --list                       # expect: tcp:8080 tcp:8080
```

Handy shell variables for the backend checks below (laptop):

```sh
MONTH=$(TZ=Africa/Nairobi date +%Y-%m)                     # month partition key is computed in Africa/Nairobi
CQL() { docker compose exec -T cassandra cqlsh -e "$1"; }
MONGO() { docker compose exec -T mongodb mongosh --quiet soko_bei --eval "$1"; }
```

The app id is `ke.sokobei.app.staging`. Start it from the launcher (it appears as "Soko Bei" alongside any debug build).

Screen and label names below are quoted from the English app (Kiswahili in brackets). Tabs along the bottom: **Markets** [Masoko], **My reports** [Ripoti zangu], **Settings** [Mipangilio]. The Report screen is reached only from the Markets tab (home screen, title "Prices today"), with the floating button **Report a price** [Ripoti bei]. Its fields are Market, Product, Grade, Unit, Quantity, "Price you saw (KES)", a "Price per kilogram" preview, and the button **Save report** [Hifadhi ripoti]. Report status badges in My reports: **Pending** [Inasubiri], **Synced** [Imetumwa], **Rejected** [Imekataliwa], **Expired** [Imekwisha muda].

Pick one test report and use it throughout: market **Wakulima Market** (id `wakulima`), product **Tomatoes**, grade standard, unit **crate (60 kg)**, quantity 1, price **4500** (the app should preview **KES 75.00 per kg**).

How sync is triggered (so you know what to expect): right after Save report, at app process start (cold start), when a network becomes available while reports are Pending, after login, and every 6 hours as a safety net. Every run needs a connected network (WorkManager constraint). A transient failure (no response, 5xx, 408, 429) keeps the report Pending and retries with exponential backoff starting at 30 s (30 s, 1 min, 2 min, 4 min, ...). A 401 triggers one token refresh and one replay; if the session is gone the report stays Pending until you log in again. A 403 CONSENT_REQUIRED makes the app post its consent and retry once. Any other 4xx (400 validation, 409 id conflict) marks the report Rejected for good. A Pending report older than 14 days is marked Expired and never sent. Sync only works while logged in; if you pressed "Not now" on the login screen, reports stay Pending.

---

## 1. Install and first launch

1. Open the app. Expect the welcome screen ("Welcome to Soko Bei": language chips English / Kiswahili, consent text, checkbox, **Continue**); no crash.
2. `adb shell pm list packages | grep sokobei` shows `ke.sokobei.app.staging` (and `ke.sokobei.app` if you also installed debug).

Backend: nothing yet.
```sh
curl -s localhost:8080/v1/markets | jq '.items | length'      # 5
MONGO 'db.users.countDocuments()'                                # 0 on a fresh stack
```

## 2. Consent and login (OTP 000000)

1. Choose a language, tick the consent checkbox ("I agree that my price reports and phone number are stored as described."), press **Continue**. Expect the **Log in with your phone** screen.
2. Enter a Kenyan number, e.g. `0712345678`, press **Send code**. The same screen then asks for the "6-digit code": enter `000000` and press **Verify**. (There is also a **Not now** button; do not use it for this test. The backend allows only 3 code requests per phone per 15 minutes, and sections 8 and 10 may each need another login, so do not request codes needlessly.)
3. Expect to land on the Markets tab ("Prices today").

Backend:
```sh
docker compose logs backend | grep -E "DEV_MODE login" | tail -1     # shows a MASKED phone (only the last 3 digits)
MONGO 'db.users.find({}, {phone:1, consent_version:1, consent_at:1}).toArray()'
#   one user, phone "+254712345678", consent_version 1
MONGO 'db.refresh_tokens.countDocuments()'                           # 1
docker compose logs backend | grep -c "712345678"                    # 0: the full number must never be logged
```

## 3. Online submit

1. On the Markets tab press **Report a price**: Wakulima Market / Tomatoes / standard / crate / 1 / 4500. Check the preview shows 75.00 per kg. Press **Save report**.
2. Expect to return to the previous screen immediately. In My reports the entry goes Pending, then Synced within a few seconds.

Backend:
```sh
CQL "SELECT report_id, price_kes, price_per_kg_kes, conversion_version, user_id FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"
#   exactly 1 row; price_per_kg_kes 75.00; user_id not null
curl -s localhost:8080/v1/markets/wakulima/prices | jq '.items[] | select(.commodity_id=="tomatoes")'
#   reports_today 1, median_today_per_kg 75, last_price_per_kg 75
```
The Markets tab should show the same "Typical price today (median)", "Latest report" and "Reports today" and an "updated just now" line (it may take a moment or a switch of tab to refresh).

## 4. No network: airplane mode (sync waits for a connection)

This tests that the app queues reports and that sync is held back until the phone has a network connection (the WorkManager network constraint). It does not prove behaviour when the backend alone is down; that is section 5.

1. Enable **airplane mode** (also turn Wi-Fi off).
2. Submit three reports (vary the prices: 3000, 4800, 6000). Each returns instantly; My reports shows three **Pending** entries; the app opens screens from cache and labels how old the data is.
3. Backend still has only the earlier report:
```sh
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # 1
```
4. Turn airplane mode **off**. The app starts a sync as soon as the network is available, so all three should become **Synced** within seconds (allow up to a minute). If nothing syncs after two minutes, force-stop and reopen the app (a cold start triggers a sync) and note this as a finding.

Backend (each report exactly once):
```sh
CQL "SELECT report_id, price_kes FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"
#   4 rows total, 4 distinct report_ids
docker compose logs backend | grep -E "POST - /v1/reports " | grep -c "201 Created"       # 4
docker compose logs backend | grep -E "POST - /v1/reports " | grep -c "200 OK"            # 0 retries needed here
curl -s localhost:8080/v1/markets/wakulima/prices | jq '.items[] | select(.commodity_id=="tomatoes") | .reports_today'   # 4
```
A `200 OK` on POST /v1/reports is not a failure: it means the server recognised a retry of an already-stored report (idempotency). The row count must still be exactly 4.

## 5. Network present, backend unreachable (each report syncs exactly once afterwards)

The phone stays online (Wi-Fi or mobile data on, airplane mode off) but cannot reach the backend. Cut it off in one of two ways, then use the matching restore command:

| Cut off | Restore |
| --- | --- |
| `adb reverse --remove tcp:8080` | `adb reverse tcp:8080 tcp:8080` |
| `docker compose stop backend` | `docker compose start backend` |

1. Cut the backend off (pick one row above). Confirm the phone still has internet (open a web page).
2. Submit two reports (prices 3200 and 3400). Each returns instantly and shows **Pending**.
3. Wait two minutes. They must stay **Pending** (not Rejected, not Synced) and the app must not crash or show them as failed for good.

Backend (with the backend started again if you stopped it, so you can query; it must still have none of them):
```sh
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # unchanged from before this step
```
4. Restore (second column). Both reports become **Synced** on the next retry. Retries use exponential backoff (30 s, 1 min, 2 min, 4 min, ...), and restoring the backend does not by itself trigger a sync, so after a two-minute outage expect up to a few minutes. To speed it up, force-stop and reopen the app (`adb shell am force-stop ke.sokobei.app.staging`; a cold start enqueues a sync) or toggle Wi-Fi. Opening the app from recents while its process is still alive does not trigger a sync.

Backend:
```sh
CQL "SELECT report_id, price_kes FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"
#   exactly 2 new rows (3200 and 3400), each report_id once
docker compose logs backend | grep -E "POST - /v1/reports " | grep -c "201 Created"     # +2 compared with before this step
```
A `200 OK` for the same POST means the server recognised a retry; the rows must still be counted once. With `adb reverse --remove`, the phone reports a connection failure; with `docker compose stop backend` it gets a refused connection. Both must behave the same: stay Pending and retry later.

## 6. Force-close in the middle of a sync

1. Airplane mode on. Submit five more reports (prices 3100 to 3900).
2. Airplane mode off, then **immediately** force-close the app: `adb shell am force-stop ke.sokobei.app.staging` (or swipe it from recents).
3. Wait two minutes, then reopen the app. (Force-stop cancels WorkManager's scheduled jobs, so the sync normally resumes only when the app is next started; a cold start triggers a sync.)
4. Expect all five **Synced**, none duplicated, none stuck Pending.

Backend:
```sh
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # 11 in total (4 + 2 + 5)
CQL "SELECT report_id FROM soko_bei.reports_by_user WHERE user_id=$(MONGO 'print(db.users.findOne()._id)') AND year=$(TZ=Africa/Nairobi date +%Y);" | grep -c -E '^ [0-9a-f-]{36}'   # 11
```
If you force-closed after the server stored a report but before the phone saw the answer, the retry returns `200 OK` and the count still stays at 11.

## 7. A report the server rejects shows as Rejected

1. Submit Tomatoes / crate / quantity 1 / price **1** KES (about 0.02 KES per kg, below the plausible minimum of 1 KES/kg).
2. The app does not block this client-side (it only requires a whole number of shillings greater than 0), so the report is saved, goes Pending, is sent, and the server answers 400. Expect **Rejected** with the line "Reason: Validation failed" (the app shows only the server's generic message; the price detail is not displayed). It must **not** retry forever.

Backend:
```sh
docker compose logs backend | grep -E "POST - /v1/reports " | tail -1      # ..."msg":"400 Bad Request: POST - /v1/reports in 12ms"; once only (count the 400 lines before and after)
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # unchanged (11)
```
Optional second case: in airplane mode leave one report Pending, then turn off automatic date and set the phone's date forward by 15 days, and turn airplane mode off. The expiry check (report older than 14 days by the phone's clock) runs at the start of the next sync, which the reconnect triggers. Expect the badge **Expired** with "Not sent: the report was more than 14 days old." and no POST in the backend log. Restore automatic date afterwards; the entry stays Expired (there is no delete button) and does not change the backend counts.

## 8. Token expiry and refresh

The access token lives 15 minutes; the refresh token 30 days. Backend log lines look like `{"ts":...,"msg":"201 Created: POST - /v1/reports in 87ms"}`. Two ways to prove the refresh path:

**Fast: invalidate the access token by changing the server's signing secret** (the refresh token stays valid in MongoDB):
```sh
JWT_SECRET=another-dev-secret-another-dev-secret-12345 docker compose up -d backend
curl -s localhost:8080/health      # wait until ok again
```
1. On the phone, submit one report online (price 4600).
2. Expect it to end up **Synced** with no login prompt.

Backend:
```sh
docker compose logs backend | grep -E "POST - /v1/(reports|auth/refresh) " | tail -3
#   "401 Unauthorized: POST - /v1/reports in ..."  then  "200 OK: POST - /v1/auth/refresh in ..."  then  "201 Created: POST - /v1/reports in ..."
```

**Natural: wait 16 minutes** after login with the app in the background, then submit online and look for the same three log lines.

**Refresh failure:** `MONGO 'db.refresh_tokens.deleteMany({})'`, then restart the backend with yet another secret (the phone now holds a token signed with the previous one, so reusing the same secret would not force a 401), for example `JWT_SECRET=third-dev-secret-third-dev-secret-123456 docker compose up -d backend`, and submit a report. Expect the report to stay **Pending** (its pending text says it is waiting until you are online and logged in). The app does not pop up a login prompt: Settings now shows "You are not logged in." with a **Log in** button. Log in (code 000000, same number); the pending report then syncs. This adds one more row (13 in total). To return to the default secret afterwards: `docker compose up -d backend`.

## 9. Language switch

1. Settings tab, Language: select Kiswahili (radio buttons English / Kiswahili). Expect every screen label, button, badge and error to change immediately and survive closing and reopening the app. Switch back.
2. Note any English left over in Kiswahili mode, or text that is cut off on your screen. Kiswahili wording needs review by a native speaker; write the corrections down.

Backend: unaffected (the language is a device setting).

## 10. Account deletion

1. Make sure everything is Synced first (deleting also wipes the phone's local reports, including any still Pending). Settings tab, Account, **Delete my account** (shown only while logged in), confirm with **Delete** in the "Delete your account?" dialog (**Cancel** dismisses it).
2. Expect to be signed out and returned to the welcome/consent screen (not the login screen): the app also clears its local settings, so you must tick consent and press Continue again before the Log in screen appears. My reports is empty. If the phone cannot reach the backend, the dialog stays open with "No internet connection..." and nothing is deleted.

Backend:
```sh
MONGO 'db.users.countDocuments()'                    # 0
MONGO 'db.refresh_tokens.countDocuments()'           # 0
CQL "SELECT report_id, price_kes, user_id FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"
#   the same 12 rows (13 if you ran the refresh-failure test), every user_id null: prices stay, anonymously
curl -s localhost:8080/v1/markets/wakulima/prices | jq '.items[] | select(.commodity_id=="tomatoes") | .reports_today'   # unchanged (12, or 13)
```
Signing in again with the same number (after Continue on the welcome screen) creates a brand-new account with an empty My reports list.

## 11. Clean up

```sh
adb reverse --remove tcp:8080
adb uninstall ke.sokobei.app.staging
docker compose down -v        # also deletes the test data
```

## Result sheet

| Step | Expected | Result (pass / fail, notes) |
| --- | --- | --- |
| 1 Install, launch | no crash, staging id installed | |
| 2 Consent + login | home screen, 1 user, consent_version 1, no full phone in logs | |
| 3 Online submit | Pending, then Synced, 1 row, 75.00 per kg | |
| 4 Airplane mode x3, reconnect | 3 Pending, then 3 Synced, 4 rows, 4 distinct ids | |
| 5 Backend unreachable, phone online | 2 stay Pending, then 2 Synced, 6 rows, each once | |
| 6 Force-close mid-sync | 5 Synced, 11 rows, no duplicates | |
| 7 Invalid report | Rejected ("Reason: Validation failed"), no row, not retried | |
| 8 Token refresh | 401, refresh, 201, no login prompt | |
| 9 Language switch | all UI switches and persists | |
| 10 Account deletion | user gone, 12 rows (13 with the refresh-failure test) with null user_id | |
