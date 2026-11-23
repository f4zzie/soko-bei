# Deploying Soko Bei on the shared EC2 instance

Soko Bei runs on the same EC2 instance as the AgriTrack project. Both hostnames are
served by the **Caddy container that already belongs to AgriTrack**; Soko Bei does
not add a second proxy, web server or certificate manager.

| Item | Value |
|---|---|
| Instance | `i-06c56f27c842789c7` (`t3.medium`, 2 vCPU, 4 GB RAM) |
| Region / AZ | `eu-north-1` / `eu-north-1b` |
| OS | Ubuntu 26.04 LTS, kernel `7.0.0-1006-aws` |
| Root volume | 50 GB gp3, expanded at runtime with `growpart` + `resize2fs` |
| Elastic IP | `13.50.114.115` (allocation `eipalloc-034a79b23fe860ea5`) |
| DNS | DuckDNS `agriapp.duckdns.org` (AgriTrack) and `sokobei.duckdns.org` (Soko Bei) |
| Docker | 29.9.1, Compose v5.6.0 |
| Soko project | `/home/ubuntu/soko-bei`, Compose project `soko-bei` |
| AgriTrack project | `/home/ubuntu/agri_app`, Compose project `agri_app` |

Both DuckDNS names resolve to the Elastic IP, so no DuckDNS updater runs on the
instance and the address survives a stop/start.

## Architecture

```
Internet
   |  80/443
   v
agri_app-caddy-1  (Caddy 2.8.4, ports 80/443, TLS via Let's Encrypt)
   |-- agriapp.duckdns.org   -> reverse_proxy app:80          (AgriTrack, unchanged)
   |-- sokobei.duckdns.org   -> reverse_proxy soko-backend:8080
                                    |
                                    v
                            soko-backend (Ktor, container port 8080)
                              /                 \
                       soko-cassandra:9042   soko-mongo:27017
```

The Soko backend container joins the existing `agri_app_default` network with the
alias `soko-backend`, so Caddy resolves it by name. Cassandra and MongoDB publish
**no** host ports; the backend is only reachable through that Docker network. The
only public ports are 80 and 443 (Caddy) and 22 (SSH).

**Outstanding security exception:** the user chose to leave SSH unchanged for
now. Security group `sg-08c3d74ab378b47fe` still allows TCP 22 from `0.0.0.0/0`.
Restrict it to the user's confirmed public IPv4 `/32` before treating this as a
finished security hardening task. Ports 8080, 9042 and 27017 have no host bindings.

## Why the databases are not published

Required by the deployment plan: never expose 8080, 9042 or 27017. Inside the
Compose project the services use their default bridge network; the backend also
attaches to `agri_app_default` so Caddy can reach it.

## Files that are not in git

The server keeps its secrets and the deployment-specific Compose file outside the
repository checkout:

- `/home/ubuntu/soko-bei/.env.ec2` — `DEV_MODE`, `ADMIN_TOKEN=` (explicitly empty),
  random `JWT_SECRET` and `OTP_SECRET`. Mode `600`.
- `/home/ubuntu/soko-bei/docker-compose.ec2.yml` — the image-based Compose file
  used on the server.
- `/home/ubuntu/soko-bei/cassandra.yaml` — the stock `cassandra:5.0` config with
  longer server-side timeouts, mounted over the container default.
- `/home/ubuntu/agri_app/docker/Caddyfile.bak-*` — backup taken before the Soko
  site block was appended.

`docs/deploy-ec2.example.yml` in the repository is the tracked copy of the Compose
file; the real one lives next to `.env.ec2` on the instance.

## Deployment steps

1. **Build the backend image on a machine with enough RAM**, not on the instance.
   The Gradle build needs more memory than is reasonable to reserve on a
   presentation server:

   ```sh
   docker build -f backend/Dockerfile -t sokobei-backend:ec2 .
   docker save sokobei-backend:ec2 | gzip -1 | ssh ubuntu@13.50.114.115 'gunzip | docker load'
   ```

2. **Create the environment file** (never commit it):

   ```sh
   cd /home/ubuntu/soko-bei
   umask 077
   cat > .env.ec2 <<EOF
   DEV_MODE=true
   ADMIN_TOKEN=
   JWT_SECRET=$(openssl rand -hex 32)
   OTP_SECRET=$(openssl rand -hex 32)
   EOF
   ```

   `ADMIN_TOKEN` must stay empty: with no token configured the backend returns
   `403` for every `/v1/admin` route, including a request with an empty header.

3. **Start the stack**:

   ```sh
   docker compose --env-file .env.ec2 -f docker-compose.ec2.yml up -d
   docker ps --filter name=soko-
   curl -sS https://sokobei.duckdns.org/health
   ```

4. **Add the Caddy site block** to `/home/ubuntu/agri_app/docker/Caddyfile`.
   Back up the file first, append, then validate and reload:

   ```caddy
   sokobei.duckdns.org {
       encode zstd gzip
       reverse_proxy soko-backend:8080
   }
   ```

   ```sh
   docker exec agri_app-caddy-1 caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
   docker exec agri_app-caddy-1 caddy reload   --config /etc/caddy/Caddyfile --adapter caddyfile
   ```

   Caddy issues the `sokobei.duckdns.org` certificate on first request and stores
   it in the existing `agri_app_caddy_data` volume.

   **Do not copy AgriTrack's `@bad_ua` matcher into the Soko site block.** It
   returns 403 for `okhttp`, `curl` and `python-requests`, which the Android app
   and `scripts/smoke-test.sh` legitimately use.

5. **Verify AgriTrack is untouched**:

   ```sh
   curl -sS https://agriapp.duckdns.org/ | sha256sum
   # expected 33072800ae239e6cd5acd0a9e443fedcd2b3379003e4dc0446e2600301eb6605
   ```

## Loading the demo data

The demo data is generated on the server from the tracked scripts; nothing is
copied from a development laptop.

```sh
cd /home/ubuntu/soko-bei
./scripts/demo-catalog.sh                    # 15 markets, 18 commodities, factors
./scripts/demo-backfill.sh --days 90 --through <yesterday> --concurrency 1 --batch-size 16
```

The verified historical window is 2026-08-25–2026-11-22: **77,561 generated
reports**, across 15 markets and 18 commodities. The successful replay returned
`created=0`, `existing=77561`, and completed aggregate rebuilding. Earlier runs
had finished the writes but failed to save their summary to a host-only path.
`--output` is interpreted **inside the backend container**, not on the host:

```sh
./scripts/demo-backfill.sh --days 90 --through 2026-11-22 --concurrency 1 --batch-size 16 --output /tmp/backfill-90.json
docker cp soko-backend:/tmp/backfill-90.json ./backfill-90.json
```

Today's reports go through the public API so the normal validation, rate limits
and derived-row updates run exactly as they do for real reports:

```sh
# 1. build today's plan inside the backend container
docker exec soko-backend /app/bin/backend demo-plan --output /tmp/plan.json

# 2. copy it out and submit it over HTTPS from any machine with Python 3
docker cp soko-backend:/tmp/plan.json ./plan.json
BASE_URL=https://sokobei.duckdns.org python3 scripts/demo_feed.py today --plan ./plan.json
```

`scripts/demo-trickle.sh` keeps posting a live trickle and generates a new plan
after the EAT date changes. The tracked `scripts/soko-demo-refresh.service` and
`.timer` refresh elapsed reports hourly at five minutes past the hour (including
00:05 EAT). `scripts/soko-demo-trickle.service` supervises the live trickle.
Both share the feeder's private per-user quota/checkpoint ledger; neither raises
the normal API limits. Install these units under `/etc/systemd/system/`, run
`systemctl daemon-reload`, then enable the timer and trickle service. Stop the
trickle and timer after the presentation if continuous demo writes are unwanted.

## Backups

A systemd timer runs every day at 03:00 EAT:

- `nodetool snapshot` on the `soko_bei` keyspace into Cassandra's snapshot
  directory, then a `tar` copy under `/home/ubuntu/soko-backups/cassandra/`.
- `docker exec soko-mongo mongodump --db soko_bei --archive --gzip` into
  `/home/ubuntu/soko-backups/mongo/`.
- Both directories keep **7 days** of archives; the script prints their size.

Install `scripts/soko-backup.sh` as `/usr/local/bin/soko-backup.sh`; the service
runs it as `ubuntu` with `Nice=10`, idle I/O priority and a 30-minute timeout.
Install `scripts/soko-backup.service` and `.timer` under `/etc/systemd/system/`,
then run `systemctl daemon-reload` and `systemctl enable --now soko-backup.timer`.
The timer uses `OnCalendar=*-*-* 00:00:00 UTC`, `RandomizedDelaySec=120` and
`Persistent=true`. Archives are written privately with `.partial` staging,
gzip checks and a SHA-256 manifest. Only the exported snapshot is cleared;
unrelated snapshots and database volumes remain untouched.

The first backup on 2026-11-23 succeeded: Cassandra **19 MB**, MongoDB **12 KB**;
both SHA-256 manifests and gzip checks passed, and Mongo's archive dry-run passed.
The Cassandra archive contains 29 data files. This is archive verification,
**not a completed restore rehearsal**. Root disk usage was 16 GB used / 33 GB
available (33% of the approximately 48 GB filesystem).

```sh
systemctl list-timers soko-backup.timer
/usr/local/bin/soko-backup.sh      # manual run
du -sh /home/ubuntu/soko-backups/* # current usage
```

## Verification

```sh
curl -sS https://sokobei.duckdns.org/health
curl -sS 'https://sokobei.duckdns.org/v1/markets'
curl -sS 'https://sokobei.duckdns.org/v1/markets/wakulima/prices'
curl -sS 'https://sokobei.duckdns.org/v1/commodities/tomatoes/markets/wakulima/trend?days=90&grade=standard'
curl -sS -X POST -o /dev/null -w '%{http_code}\n' \
  'https://sokobei.duckdns.org/v1/admin/rebuild?from=2026-11-01&to=2026-11-02'   # expect 403
BASE_URL=https://sokobei.duckdns.org ADMIN_TOKEN= SMOKE_PHONE=<dedicated-test-number> scripts/smoke-test.sh
```

## The Android APK

The staging APK is built against the permanent hostname:

```sh
./gradlew :android:assembleStaging -PsokoBaseUrl=https://sokobei.duckdns.org
```

The URL is baked into `BuildConfig`, so the APK does not depend on a tunnel and is
not affected by this laptop going to sleep. Rebuild only if the hostname changes.

Presentation artifact: `/home/arch/Desktop/soko-bei-staging.apk`, 2,649,839 bytes.
Its dex contains `https://sokobei.duckdns.org`.

```text
SHA-256 d9b04f6c609a04a485d093fbfbab62ff81b2611df11cf8311c4d0b95f2a95986
```

The exact installed APK matched that hash on the API 34 emulator. Live EC2
checks passed for consent/login, current prices, a 90-day chart, one online
report, two offline Pending reports with cached prices, and automatic sync of
both after reconnecting. Cassandra confirmed one row per report (three distinct
IDs total for the dedicated emulator account); no `adb reverse` was configured.
Physical-phone installation and venue/carrier reliability remain unverified.

## Resource notes

- The instance started as `t3.micro` (908 MB), which is below the 3.5 GB minimum in
  the deployment plan. A 90-day backfill on it exhausted memory and made the whole
  instance, including AgriTrack, unresponsive. It was resized to `t3.medium`
  (4 GB) before the data load; the Elastic IP kept the address and the boot volume.
- Cassandra heap is 1 GB, MongoDB's WiredTiger cache 0.5 GB, and the backend JVM
  `-Xmx512m`. Total swap is 7 GB as a safety net; the verified sample used none.
- The backend image is built off-instance because Gradle, the Kotlin daemon and
  R8 do not belong on a 4 GB presentation server.

## Rollback

```sh
cd /home/ubuntu/soko-bei
docker compose --env-file .env.ec2 -f docker-compose.ec2.yml down   # keeps volumes

cd /home/ubuntu/agri_app
cp -a docker/Caddyfile.bak-<timestamp> docker/Caddyfile
docker exec agri_app-caddy-1 caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

Removing the Soko site block never touches AgriTrack's own block or its data.
