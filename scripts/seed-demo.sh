#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
for tool in curl jq sha256sum date; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
temp_root="${TMPDIR:-/tmp}"
if [[ -z "${TMPDIR:-}" && -d /tmp/opencode ]]; then temp_root=/tmp/opencode; fi
headers="$(mktemp "$temp_root/soko-bei-seed-headers.XXXXXX")"

request() {
  local method="$1" path="$2"
  shift 2
  local response
  response="$(curl --silent --show-error --connect-timeout 10 --max-time 30 -D "$headers" \
    -w $'\n%{http_code}' -X "$method" "$BASE_URL$path" "$@")"
  STATUS="${response##*$'\n'}"
  BODY="${response%$'\n'*}"
}

expect() {
  [[ " $* " == *" $STATUS "* ]] || { echo "Unexpected HTTP $STATUS (expected $*)" >&2; exit 1; }
}

request GET /health
expect 200
jq -e '.dev_mode == true and .status == "ok"' <<<"$BODY" >/dev/null || {
  echo 'Refusing to seed: /health must report dev_mode=true (DEV_MODE=true).' >&2
  exit 1
}

DEMO_DATE="${DEMO_DATE:-$(TZ=Africa/Nairobi date +%F)}"
[[ "$DEMO_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo 'DEMO_DATE must be YYYY-MM-DD.' >&2; exit 1; }
anchor="$(TZ=Africa/Nairobi date -d "$DEMO_DATE 00:00:00" +%s)"
markets=(wakulima marikiti kongowea kibuye nakuru-main)
commodities=(maize dry_beans tomatoes onions irish_potatoes cabbage sukuma_wiki bananas rice carrots)
bases=(55 140 85 105 65 35 40 70 160 60)
market_factors=(100 105 114 96 92)
created=0
existing=0
echo "Seeding 150 demo reports: 5 users, 5 markets, 10 commodities, $DEMO_DATE and the preceding 11 days."

for m in "${!markets[@]}"; do
  printf -v phone '070090010%d' "$m"
  request POST /v1/auth/otp/verify -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg phone "$phone" '{phone: $phone, code: "000000"}')"
  expect 200
  token="$(jq -er '.access_token | select(length > 0)' <<<"$BODY")"
  request GET /v1/me -H "Authorization: Bearer $token"
  expect 200
  consent="$(jq -er '.current_consent_version' <<<"$BODY")"
  request POST /v1/me/consent -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "$(jq -nc --argjson version "$consent" '{consent_version: $version}')"
  expect 204
  for c in "${!commodities[@]}"; do
    pair=$((m * 10 + c))
    for sample in 0 1 2; do
      case "$sample" in
        0) age=$((6 + pair % 6)) ;;
        1) age=$((1 + pair % 6)) ;;
        2) age=0 ;;
      esac
      index=$((pair * 3 + sample))
      digest="$(printf 'soko-bei-demo-v1|%s|%s' "$DEMO_DATE" "$index" | sha256sum)"
      digest="${digest%% *}"
      report_id="${digest:0:8}-${digest:8:4}-5${digest:13:3}-a${digest:17:3}-${digest:20:12}"
      timestamp=$(((anchor - age * 86400) * 1000))
      jitter=$(((424242 + pair * 1103515245 + sample * 12345) % 9 - 4))
      drift=$(((c % 2 * 2 - 1) * (11 - age)))
      price=$((bases[c] * market_factors[m] * (100 + drift + jitter) / 1000))
      payload="$(jq -nc --arg id "$report_id" --argjson ts "$timestamp" \
        --arg market "${markets[m]}" --arg commodity "${commodities[c]}" --argjson price "$price" \
        '{report_id: $id, reported_at_ms: $ts, market_id: $market, commodity_id: $commodity,
          grade: "standard", unit: "kg", quantity: 10, price_kes: $price}')"
      request POST /v1/reports -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$payload"
      if [[ "$STATUS" == 429 ]]; then
        delay="$(awk 'tolower($1) == "retry-after:" {gsub("\r", "", $2); print $2}' "$headers")"
        [[ "$delay" =~ ^[0-9]+$ && "$delay" -le 3600 ]] || { echo 'Invalid or missing Retry-After on HTTP 429.' >&2; exit 1; }
        echo "Report quota exhausted (30/user/hour); waiting $((delay + 1)) seconds before retrying the identical payload."
        sleep "$((delay + 1))"
        request POST /v1/auth/otp/verify -H 'Content-Type: application/json' \
          -d "$(jq -nc --arg phone "$phone" '{phone: $phone, code: "000000"}')"
        expect 200
        token="$(jq -er '.access_token | select(length > 0)' <<<"$BODY")"
        request POST /v1/reports -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$payload"
      fi
      expect 201 200
      jq -e --arg id "$report_id" '.report_id == $id and .price_per_kg_kes != null' <<<"$BODY" >/dev/null
      if [[ "$STATUS" == 201 ]]; then created=$((created + 1)); else existing=$((existing + 1)); fi
    done
  done
done
echo "Demo seed complete: HTTP 201=$created, HTTP 200=$existing, total=$((created + existing))."
