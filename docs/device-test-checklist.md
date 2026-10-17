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

Pick one test report and use it throughout: market **Wakulima**, commodity **Tomatoes**, grade standard, unit **crate**, quantity 1, price **4500** (the app should preview **75.00 / kg**).

---

## 1. Install and first launch

1. Open the app. Expect the welcome/consent screen; no crash.
2. `adb shell pm list packages | grep sokobei` shows `ke.sokobei.app.staging` (and `ke.sokobei.app` if you also installed debug).

Backend: nothing yet.
```sh
curl -s localhost:8080/v1/markets | jq '.items | length'      # 5
MONGO 'db.users.countDocuments()'                                # 0 on a fresh stack
```

## 2. Consent and login (OTP 000000)

1. Choose a language, tick the consent box, continue.
2. Enter a Kenyan number, e.g. `0712345678`, request the code. Enter `000000`.
3. Expect to land on the Market home screen.

Backend:
```sh
docker compose logs backend | grep -E "DEV_MODE login" | tail -1     # shows a MASKED phone (only the last 3 digits)
MONGO 'db.users.find({}, {phone:1, consent_version:1, consent_at:1}).toArray()'
#   one user, phone "+254712345678", consent_version 1
MONGO 'db.refresh_tokens.countDocuments()'                           # 1
docker compose logs backend | grep -c "712345678"                    # 0: the full number must never be logged
```

## 3. Online submit

1. Report price: Wakulima / Tomatoes / crate / 1 / 4500. Check the preview shows 75.00 per kg. Submit.
2. Expect to return to the previous screen immediately. In My reports the entry goes Pending, then Synced within a few seconds.

Backend:
```sh
CQL "SELECT report_id, price_kes, price_per_kg_kes, conversion_version, user_id FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"
#   exactly 1 row; price_per_kg_kes 75.00; user_id not null
curl -s localhost:8080/v1/markets/wakulima/prices | jq '.items[] | select(.commodity_id=="tomatoes")'
#   reports_today 1, median_today_per_kg 75, last_price_per_kg 75
```
Market home should show the same median/last/count and "updated just now".

## 4. No network: airplane mode (sync waits for a connection)

This tests that the app queues reports and that sync is held back until the phone has a network connection (the WorkManager network constraint). It does not prove behaviour when the backend alone is down; that is section 5.

1. Enable **airplane mode** (also turn Wi-Fi off).
2. Submit three reports (vary the prices: 3000, 4800, 6000). Each returns instantly; My reports shows three **Pending** entries; the app opens screens from cache and labels how old the data is.
3. Backend still has only the earlier report:
```sh
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # 1
```
4. Turn airplane mode **off**. Within about a minute all three become **Synced**. If nothing syncs after two minutes, open the app (it triggers a sync on start) and note this as a finding.

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
4. Restore (second column), then wait up to two minutes or open the app. Both reports become **Synced**.

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
3. Wait two minutes (WorkManager retries by itself), then reopen the app.
4. Expect all five **Synced**, none duplicated, none stuck Pending.

Backend:
```sh
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # 11 in total (4 + 2 + 5)
CQL "SELECT report_id FROM soko_bei.reports_by_user WHERE user_id=$(MONGO 'print(db.users.findOne()._id)') AND year=$(TZ=Africa/Nairobi date +%Y);" | grep -c -E '^ [0-9a-f-]{36}'   # 11
```
If you force-closed after the server stored a report but before the phone saw the answer, the retry returns `200 OK` and the count still stays at 9.

## 7. A report the server rejects shows as Rejected

1. Submit Tomatoes / crate / quantity 1 / price **1** KES (about 0.02 KES per kg, below the plausible minimum of 1 KES/kg).
2. Expect it to go Pending, then **Rejected**, with a reason that mentions the price. It must **not** retry forever.

Backend:
```sh
docker compose logs backend | grep -E "POST - /v1/reports" | tail -2      # 400 Bad Request for that call, once
CQL "SELECT count(*) FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"   # unchanged (11)
```
Optional second case: leave a report Pending in airplane mode, then set the phone's date forward by 15 days, reconnect. Expect **Expired** (never sent). Restore the date afterwards and delete or ignore that entry.

## 8. Token expiry and refresh

The access token lives 15 minutes; the refresh token 30 days. Two ways to prove the refresh path:

**Fast: invalidate the access token by changing the server's signing secret** (the refresh token stays valid in MongoDB):
```sh
JWT_SECRET=another-dev-secret-another-dev-secret-12345 docker compose up -d backend
curl -s localhost:8080/health      # wait until ok again
```
1. On the phone, submit one report online (price 4600).
2. Expect it to end up **Synced** with no login prompt.

Backend:
```sh
docker compose logs backend | grep -E "/v1/reports|/v1/auth/refresh" | tail -4
#   401 Unauthorized  POST /v1/reports  ->  200 OK  POST /v1/auth/refresh  ->  201 Created  POST /v1/reports
```

**Natural: wait 16 minutes** after login with the app in the background, then submit online and look for the same three log lines.

**Refresh failure:** `MONGO 'db.refresh_tokens.deleteMany({})'`, restart the backend as above to force a 401, submit a report. Expect the report to stay **Pending** and the app to ask you to sign in again; after signing in (code 000000) it syncs.

## 9. Language switch

1. Settings, switch English to Kiswahili. Expect every screen label, button, badge and error to change immediately and survive closing and reopening the app. Switch back.
2. Note any English left over in Kiswahili mode, or text that is cut off on your screen. Kiswahili wording needs review by a native speaker; write the corrections down.

Backend: unaffected (the language is a device setting).

## 10. Account deletion

1. Settings, delete account, confirm in the dialog.
2. Expect to be signed out and returned to the welcome/login screen.

Backend:
```sh
MONGO 'db.users.countDocuments()'                    # 0
MONGO 'db.refresh_tokens.countDocuments()'           # 0
CQL "SELECT report_id, price_kes, user_id FROM soko_bei.reports_by_market_commodity WHERE commodity_id='tomatoes' AND market_id='wakulima' AND month='$MONTH';"
#   the same 11 rows, every user_id null: prices stay, anonymously
curl -s localhost:8080/v1/markets/wakulima/prices | jq '.items[] | select(.commodity_id=="tomatoes") | .reports_today'   # still 11
```
Signing in again with the same number creates a brand-new account with an empty My reports list.

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
| 7 Invalid report | Rejected with reason, no row, not retried | |
| 8 Token refresh | 401, refresh, 201, no login prompt | |
| 9 Language switch | all UI switches and persists | |
| 10 Account deletion | user gone, 11 rows with null user_id | |
