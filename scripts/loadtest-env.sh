#!/usr/bin/env bash
# Source this file (don't execute) to populate load-test env vars:
#   source scripts/loadtest-env.sh

export HOST="http://localhost:8086"          # loyalty-service
export USER_HOST="http://localhost:8081"     # user-service

# --- 1. CUSTOMER token (for read-mostly endpoints) ---
CUSTOMER_TOKEN=$(curl -s -X POST $USER_HOST/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"+254777224008","password":"YourTestPassword"}' \
  | jq -r .data.token)
export CUSTOMER_TOKEN
echo "Customer token: ${CUSTOMER_TOKEN:0:40}..."

# --- 2. MERCHANT_ADMIN token (for write endpoints) ---
MERCHANT_TOKEN=$(curl -s -X POST $USER_HOST/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"admin@yourmerchant.com","password":"AdminPass123!"}' \
  | jq -r .data.token)
export MERCHANT_TOKEN
echo "Merchant token: ${MERCHANT_TOKEN:0:40}..."

# --- 3. Tenant + Merchant IDs ---
export TENANT_ID="36c1377c-063a-4b1f-8272-16acae392b27"
export MERCHANT_ID="912f0da5-2501-41d9-a056-998d7769a7ea"
export TEMPLATE_ID="f6a41df0-05e0-4d4b-b5bd-7875314085e9"
export TEST_PHONE="+254777224008"
