#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DOWN=false
case "${1:-}" in
  '') ;;
  --down) DOWN=true ;;
  -h|--help) echo 'Usage: scripts/demo-up.sh [--down]  (--down also stops compose on exit)'; exit 0 ;;
  *) echo "Unknown argument: $1" >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { echo 'Only --down is supported.' >&2; exit 1; }
if (( EUID == 0 )); then
  human="${SUDO_USER:-$(stat -c %U "$ROOT")}"
  [[ "$human" != root ]] || { echo 'Run this as your desktop user, without sudo.' >&2; exit 1; }
  exec runuser -u "$human" -- "$ROOT/scripts/demo-up.sh" "$@"
fi
cd "$ROOT"
export PATH="$HOME/.local/bin:$PATH"
for tool in docker curl jq java keytool unzip strings sha256sum; do
  command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }
done
[[ -x ./gradlew && -f gradle/wrapper/gradle-wrapper.jar ]] || { echo 'Gradle wrapper is missing or not executable.' >&2; exit 1; }
docker compose version >/dev/null
docker info >/dev/null

temp_root="${TMPDIR:-/tmp}"
if [[ -z "${TMPDIR:-}" && -d /tmp/opencode ]]; then temp_root=/tmp/opencode; fi
RUN_DIR="$(mktemp -d "$temp_root/soko-bei-demo.XXXXXX")"
chmod 700 "$RUN_DIR"
TUNNEL_PID=''
cleanup() {
  local result=$?
  trap - EXIT INT TERM HUP
  if [[ -n "$TUNNEL_PID" ]]; then
    kill "$TUNNEL_PID" 2>/dev/null || true
    wait "$TUNNEL_PID" 2>/dev/null || true
  fi
  if "$DOWN"; then docker compose down || result=$?; fi
  echo "Demo stopped. Runtime logs: $RUN_DIR"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

if ! command -v cloudflared >/dev/null; then
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64) asset=cloudflared-linux-amd64 ;;
    Linux/aarch64|Linux/arm64) asset=cloudflared-linux-arm64 ;;
    *) echo 'Install cloudflared: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/' >&2; exit 1 ;;
  esac
  echo "Installing official $asset into $HOME/.local/bin (no sudo)."
  if ! curl --fail --location --show-error --connect-timeout 15 --max-time 180 --retry 2 \
      "https://github.com/cloudflare/cloudflared/releases/latest/download/$asset" -o "$RUN_DIR/cloudflared"; then
    echo 'Install cloudflared from https://github.com/cloudflare/cloudflared/releases into ~/.local/bin and rerun.' >&2
    exit 1
  fi
  if ! install -Dm755 "$RUN_DIR/cloudflared" "$HOME/.local/bin/cloudflared"; then
    echo 'Install cloudflared from https://github.com/cloudflare/cloudflared/releases into ~/.local/bin and rerun.' >&2
    exit 1
  fi
fi
cloudflared --version || { echo 'Reinstall cloudflared from https://github.com/cloudflare/cloudflared/releases into ~/.local/bin.' >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"; fi
[[ -d "$SDK/build-tools" ]] || { echo 'Set ANDROID_HOME or sdk.dir in local.properties to your Android SDK.' >&2; exit 1; }
BUILD_TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
for tool in aapt apksigner; do
  [[ -x "$BUILD_TOOLS/$tool" ]] || { echo "Missing Android SDK tool: $BUILD_TOOLS/$tool" >&2; exit 1; }
done
desktop="$(xdg-user-dir DESKTOP 2>/dev/null || true)"
desktop="${desktop:-$HOME/Desktop}"
[[ "$desktop" = /* ]] || { echo 'Desktop must be an absolute path.' >&2; exit 1; }
mkdir -p "$desktop"
APK="$desktop/soko-bei-staging.apk"

export DEV_MODE=true ADMIN_TOKEN=''
docker compose config --format json | jq -e \
  '.services.backend.environment.DEV_MODE == "true" and .services.backend.environment.ADMIN_TOKEN == ""' >/dev/null
echo 'Starting the dev stack with admin API disabled; recreating only the backend resets its in-memory demo quotas.'
docker compose up -d --build --wait --wait-timeout 400 --force-recreate backend
docker compose exec -T backend sh -c 'test "$DEV_MODE" = true && test -z "$ADMIN_TOKEN"'
BASE_URL=http://localhost:8080 "$ROOT/scripts/seed-demo.sh"
admin_status="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/v1/admin/jobs/daily-stats)"
[[ "$admin_status" == 403 ]] || { echo "Admin API closure check failed: HTTP $admin_status" >&2; exit 1; }

echo 'Opening a PUBLIC HTTPS quick tunnel; anyone with its URL can use dev OTP 000000.'
cloudflared tunnel --url http://localhost:8080 --protocol http2 --no-autoupdate >"$RUN_DIR/cloudflared.log" 2>&1 &
TUNNEL_PID=$!
printf '%s\n' "$$" >"$RUN_DIR/demo.pid"
printf '%s\n' "$TUNNEL_PID" >"$RUN_DIR/tunnel.pid"
deadline=$((SECONDS + 60))
TUNNEL_URL=''
healthy=false
url_seen=false
while (( SECONDS < deadline )); do
  kill -0 "$TUNNEL_PID" 2>/dev/null || { echo "Tunnel exited. See $RUN_DIR/cloudflared.log" >&2; exit 1; }
  TUNNEL_URL="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$RUN_DIR/cloudflared.log" | head -n 1 || true)"
  if [[ -n "$TUNNEL_URL" ]]; then
    if ! "$url_seen"; then deadline=$((SECONDS + 60)); url_seen=true; fi
    status="$(curl -sS --connect-timeout 3 --max-time 5 -o "$RUN_DIR/health.json" -w '%{http_code}' "$TUNNEL_URL/health" 2>>"$RUN_DIR/health-errors.log" || true)"
    if [[ "$status" == 200 ]] && jq -e '.status == "ok" and .dev_mode == true' "$RUN_DIR/health.json" >/dev/null; then
      healthy=true
      break
    fi
  fi
  sleep 2
done
"$healthy" || { echo "Tunnel health did not return 200 within 60 seconds. See $RUN_DIR/cloudflared.log" >&2; exit 1; }
printf '%s\n' "$TUNNEL_URL" >"$RUN_DIR/url"
echo "HTTPS /health: HTTP 200 ($TUNNEL_URL)"

./gradlew :android:assembleStaging "-PsokoBaseUrl=$TUNNEL_URL" --console=plain
built_apk="$ROOT/android/build/outputs/apk/staging/android-staging.apk"
[[ -s "$built_apk" ]] || { echo 'Gradle did not produce the staging APK.' >&2; exit 1; }
"$BUILD_TOOLS/aapt" dump badging "$built_apk" >"$RUN_DIR/badging.txt"
grep -q "^package: name='ke.sokobei.app.staging'" "$RUN_DIR/badging.txt"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$built_apk" >"$RUN_DIR/signature.txt"
grep -q 'Signer #1 certificate DN:.*CN=Android Debug' "$RUN_DIR/signature.txt"
debug_verified=false
for debug_key in "${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore" "${XDG_CONFIG_HOME:-$HOME/.config}/.android/debug.keystore"; do
  [[ -f "$debug_key" ]] || continue
  key_hash="$(keytool -exportcert -keystore "$debug_key" -alias androiddebugkey -storepass android 2>/dev/null | sha256sum)"
  key_hash="${key_hash%% *}"
  if grep -q "Signer #1 certificate SHA-256 digest: $key_hash" "$RUN_DIR/signature.txt"; then debug_verified=true; break; fi
done
"$debug_verified" || { echo 'APK signer does not match a local Android debug keystore.' >&2; exit 1; }
unzip -p "$built_apk" 'classes*.dex' | strings >"$RUN_DIR/dex-strings.txt"
grep -Fq "$TUNNEL_URL" "$RUN_DIR/dex-strings.txt"
if grep -Fq 'http://localhost:8080' "$RUN_DIR/dex-strings.txt"; then
  echo 'APK still contains the old localhost URL.' >&2
  exit 1
fi
cp "$built_apk" "$APK.tmp"
mv -f "$APK.tmp" "$APK"
sha="$(sha256sum "$APK")"
echo
echo 'DEMO READY — verified staging package, debug-key signature and HTTPS URL in dex.'
echo "APK: $APK"
echo "Tunnel: $TUNNEL_URL"
echo "SHA-256: ${sha%% *}"
echo "Built: $(date --iso-8601=seconds)"
echo "PID: $$; stop with Ctrl+C or: kill -TERM $$"
echo "Runtime logs: $RUN_DIR"
echo 'The tunnel is PUBLIC; OTP 000000 works for anyone with the URL. Keep this laptop awake.'
echo 'Stopping this script stops only its tunnel (add --down to stop compose too).'
wait "$TUNNEL_PID"
