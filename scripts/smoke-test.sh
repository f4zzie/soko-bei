#!/usr/bin/env bash
# shellcheck disable=SC2016  # jq programs are deliberately single-quoted; $c/$m are jq variables passed with --arg
# End-to-end smoke test for the Soko Bei backend.
#
# Requires the backend running with DEV_MODE=true (OTP code 000000 is accepted).
# Usage:
#   BASE_URL=http://localhost:8080 ADMIN_TOKEN=dev-admin-token scripts/smoke-test.sh
#
# Exits non-zero on the first failing step. Each step prints PASS or FAIL.
# Note: OTP requests are rate limited per phone (OTP_REQUESTS_PER_15MIN, default 3),
# so re-running within 15 minutes may fail at the OTP step with 429.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"
PHONE="0712345678"
OTP_CODE="000000"
MARKET="wakulima"
COMMODITY="tomatoes"
UNIT="crate"
GRADE="standard"

for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "FAIL: missing required tool: $tool" >&2
    exit 1
  fi
done

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

HTTP_CODE=""
BODY=""

# http_call METHOD PATH [curl args...]
# Sets HTTP_CODE and BODY. Never aborts on curl failure (code "000" means no response).
http_call() {
  local method="$1" path="$2"
  shift 2
  HTTP_CODE="$(curl -sS --max-time 20 -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$BASE_URL$path" "$@" 2>/dev/null || true)"
  BODY="$(cat "$BODY_FILE" 2>/dev/null || true)"
  [[ -n "$HTTP_CODE" ]] || HTTP_CODE="000"
}

print_error_envelope() {
  if jq -e '.error' <<<"$BODY" >/dev/null 2>&1; then
    echo "  error envelope: $(jq -c '.error' <<<"$BODY")"
  else
    echo "  body: ${BODY:0:500}"
  fi
}

fail() {
  local step="$1" reason="$2"
  echo "FAIL: $step: $reason"
  echo "  last HTTP status: $HTTP_CODE"
  print_error_envelope
  exit 1
}

pass() {
  echo "PASS: $1"
}

# expect_status STEP "codes space-separated" -> fails unless HTTP_CODE is one of them
expect_status() {
  local step="$1" allowed="$2" code
  for code in $allowed; do
    [[ "$HTTP_CODE" == "$code" ]] && return 0
  done
  fail "$step" "expected HTTP $allowed, got $HTTP_CODE"
}

# assert_json STEP DESCRIPTION JQ_FILTER [jq options such as --arg name value]
# Fails unless `jq -e FILTER` is true on BODY.
assert_json() {
  local step="$1" desc="$2" filter="$3"
  shift 3
  if ! jq -e "$@" "$filter" <<<"$BODY" >/dev/null 2>&1; then
    fail "$step" "assertion failed: $desc"
  fi
}

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    cat /proc/sys/kernel/random/uuid
  fi | tr '[:upper:]' '[:lower:]'
}

# ---------------------------------------------------------------------------
# 1. Wait for backend health (up to ~120s)
# ---------------------------------------------------------------------------
STEP="health"
deadline=$((SECONDS + 120))
while true; do
  http_call GET /health
  if [[ "$HTTP_CODE" == "200" ]] && jq -e '.cassandra == "up"' <<<"$BODY" >/dev/null 2>&1; then
    break
  fi
  if (( SECONDS >= deadline )); then
    fail "$STEP" "backend not healthy within 120s (cassandra must be \"up\")"
  fi
  sleep 2
done
pass "$STEP (cassandra up)"

# ---------------------------------------------------------------------------
# 2. Auth: OTP request + verify
# ---------------------------------------------------------------------------
STEP="otp request"
http_call POST /v1/auth/otp/request -H 'Content-Type: application/json' \
  -d "$(jq -n --arg p "$PHONE" '{phone: $p}')"
expect_status "$STEP" "200 202"
pass "$STEP"

STEP="otp verify"
http_call POST /v1/auth/otp/verify -H 'Content-Type: application/json' \
  -d "$(jq -n --arg p "$PHONE" --arg c "$OTP_CODE" '{phone: $p, code: $c}')"
expect_status "$STEP" "200"
assert_json "$STEP" "access_token and refresh_token present" \
  '(.access_token | type == "string" and length > 0) and (.refresh_token | type == "string" and length > 0)'
ACCESS_TOKEN="$(jq -r '.access_token' <<<"$BODY")"
REFRESH_TOKEN="$(jq -r '.refresh_token' <<<"$BODY")"
pass "$STEP"

# ---------------------------------------------------------------------------
# 3. Me + consent
# ---------------------------------------------------------------------------
STEP="me"
http_call GET /v1/me -H "Authorization: Bearer $ACCESS_TOKEN"
expect_status "$STEP" "200"
assert_json "$STEP" "current_consent_version is a number" '.current_consent_version | type == "number"'
CONSENT_VERSION="$(jq -r '.current_consent_version' <<<"$BODY")"
pass "$STEP"

STEP="consent"
http_call POST /v1/me/consent -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --argjson v "$CONSENT_VERSION" '{consent_version: $v}')"
expect_status "$STEP" "200 204"
pass "$STEP (version $CONSENT_VERSION)"

# ---------------------------------------------------------------------------
# 4. Catalog
# ---------------------------------------------------------------------------
STEP="commodities"
http_call GET /v1/commodities
expect_status "$STEP" "200"
assert_json "$STEP" "items contain commodity $COMMODITY" \
  '[.items[]? | select(.commodity_id == $c)] | length == 1' --arg c "$COMMODITY"
pass "$STEP"

STEP="markets"
http_call GET /v1/markets
expect_status "$STEP" "200"
assert_json "$STEP" "items contain market $MARKET" \
  '[.items[]? | select(.market_id == $m)] | length == 1' --arg m "$MARKET"
assert_json "$STEP" "page envelope has next_page_token" 'has("next_page_token")'
pass "$STEP"

# ---------------------------------------------------------------------------
# 5. Submit a report (201 first, 200 on identical resubmit)
# ---------------------------------------------------------------------------
REPORT_ID="$(new_uuid)"
REPORTED_AT_MS="$(( $(date +%s) * 1000 ))"
REPORT_BODY="$(jq -n \
  --arg id "$REPORT_ID" \
  --argjson ts "$REPORTED_AT_MS" \
  --arg m "$MARKET" --arg c "$COMMODITY" --arg g "$GRADE" --arg u "$UNIT" \
  '{report_id: $id, reported_at_ms: $ts, market_id: $m, commodity_id: $c, grade: $g, unit: $u, quantity: 1, price_kes: 4500}')"

STEP="report create"
http_call POST /v1/reports -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' -d "$REPORT_BODY"
expect_status "$STEP" "201"
assert_json "$STEP" "price_per_kg_kes present" '.price_per_kg_kes != null'
pass "$STEP (price_per_kg_kes=$(jq -r '.price_per_kg_kes' <<<"$BODY"))"

STEP="report idempotent resubmit"
http_call POST /v1/reports -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' -d "$REPORT_BODY"
expect_status "$STEP" "200"
pass "$STEP"

# ---------------------------------------------------------------------------
# 6. Prices visible
# ---------------------------------------------------------------------------
check_prices() {
  local label="$1"

  local step="market prices ($label)"
  http_call GET "/v1/markets/$MARKET/prices"
  expect_status "$step" "200"
  assert_json "$step" "items include $COMMODITY with reports_today >= 1" \
    '[.items[]? | select(.commodity_id == $c and .reports_today >= 1)] | length >= 1' --arg c "$COMMODITY"
  pass "$step"

  step="commodity prices ($label)"
  http_call GET "/v1/commodities/$COMMODITY/prices"
  expect_status "$step" "200"
  assert_json "$step" "items include market $MARKET" \
    '[.items[]? | select(.market_id == $m)] | length >= 1' --arg m "$MARKET"
  pass "$step"
}

check_prices "after report"

# ---------------------------------------------------------------------------
# 7. Admin rebuild of derived tables (range computed in Africa/Nairobi)
# ---------------------------------------------------------------------------
TODAY_EAT="$(TZ=Africa/Nairobi date +%F)"
YESTERDAY_EAT="$(TZ=Africa/Nairobi date -d "$TODAY_EAT -1 day" +%F)"

STEP="admin rebuild"
http_call POST "/v1/admin/rebuild?from=$YESTERDAY_EAT&to=$TODAY_EAT" -H "X-Admin-Token: $ADMIN_TOKEN"
expect_status "$STEP" "200"
pass "$STEP ($YESTERDAY_EAT..$TODAY_EAT)"

check_prices "after rebuild"

# ---------------------------------------------------------------------------
# 8. Logout, delete account, confirm old token is dead
# ---------------------------------------------------------------------------
STEP="logout"
http_call POST /v1/auth/logout -H 'Content-Type: application/json' \
  -d "$(jq -n --arg t "$REFRESH_TOKEN" '{refresh_token: $t}')"
expect_status "$STEP" "200 204"
pass "$STEP"

STEP="delete account"
http_call DELETE /v1/me -H "Authorization: Bearer $ACCESS_TOKEN"
expect_status "$STEP" "200 204"
pass "$STEP"

STEP="old token rejected"
http_call GET /v1/me -H "Authorization: Bearer $ACCESS_TOKEN"
expect_status "$STEP" "401 404"
pass "$STEP (HTTP $HTTP_CODE)"

echo "ALL STEPS PASSED"
