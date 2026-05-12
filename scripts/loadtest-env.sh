#!/usr/bin/env bash
# Source this file (don't execute it) to populate load-test env vars in your shell:
#   source scripts/loadtest-env.sh
#
# Requires: curl, jq, and the user-service + loyalty-service running locally.
# Passwords are read from env vars so this file is safe to commit. Export them
# first (e.g. from a gitignored .env.loadtest) before sourcing:
#   export CUSTOMER_PASSWORD='...'
#   export MERCHANT_PASSWORD='...'

export HOST="${HOST:-http://localhost:8086}"           # loyalty-service
export USER_HOST="${USER_HOST:-http://localhost:8081}" # user-service

: "${CUSTOMER_PASSWORD:?set CUSTOMER_PASSWORD before sourcing}"
: "${MERCHANT_PASSWORD:?set MERCHANT_PASSWORD before sourcing}"

# --- 1. CUSTOMER token (for read-mostly endpoints) ---
CUSTOMER_TOKEN=$(curl -s -X POST "$USER_HOST/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"identifier\":\"+254777224008\",\"password\":\"$CUSTOMER_PASSWORD\"}" \
  | jq -r .data.token)
export CUSTOMER_TOKEN
echo "Customer token: ${CUSTOMER_TOKEN:0:40}..."

# --- 2. MERCHANT_ADMIN token (for write endpoints) ---
MERCHANT_TOKEN=$(curl -s -X POST "$USER_HOST/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"identifier\":\"admin@yourmerchant.com\",\"password\":\"$MERCHANT_PASSWORD\"}" \
  | jq -r .data.token)
export MERCHANT_TOKEN
echo "Merchant token: ${MERCHANT_TOKEN:0:40}..."

# --- 3. Tenant + Merchant + Template IDs ---
export TENANT_ID="36c1377c-063a-4b1f-8272-16acae392b27"
export MERCHANT_ID="912f0da5-2501-41d9-a056-998d7769a7ea"
export TEMPLATE_ID="f6a41df0-05e0-4d4b-b5bd-7875314085e9"
export TEST_PHONE="+254777224008"
