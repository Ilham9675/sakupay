#!/usr/bin/env bash
# Plays the partner bank: sends a signed top-up webhook to SakuPay, as if a customer had paid into a VA.
#
#   TOPUP_WEBHOOK_SECRET=... scripts/simulate-topup.sh <virtual-account> <amount> [base-url]
#
# Set EVENT_ID to re-send an earlier event (SakuPay answers DUPLICATE and credits nothing).
# Needs bash, curl and openssl (Git Bash on Windows has all three). No real money is involved.
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 <virtual-account> <amount> [base-url]" >&2
  exit 2
fi
va=$1
amount=$2
base=${3:-http://localhost:8080}
secret=${TOPUP_WEBHOOK_SECRET:?set TOPUP_WEBHOOK_SECRET to the same value the app uses}
event=${EVENT_ID:-evt_$(date +%s)_${RANDOM}${RANDOM}}

ts=$(date +%s)
paid_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
body=$(printf '{"eventId":"%s","virtualAccount":"%s","amount":%s,"paidAt":"%s"}' "$event" "$va" "$amount" "$paid_at")
# HMAC-SHA256 over "<timestamp>.<body>", hex encoded
sig=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$secret" | sed 's/^.*= //')

echo "event $event: Rp$amount to VA $va" >&2
curl -sS -X POST "$base/api/v1/webhooks/topups" \
  -H 'Content-Type: application/json' \
  -H "X-Timestamp: $ts" \
  -H "X-Signature: sha256=$sig" \
  -d "$body"
echo
