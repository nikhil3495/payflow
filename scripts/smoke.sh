#!/usr/bin/env bash
# End-to-end happy path against a running PayFlow: authorize -> partial capture -> partial refund
# -> void the remainder, plus an idempotent replay. Prints every request and response and exits
# non-zero on the first unexpected result.
#
#   BASE=http://localhost:8080 ./scripts/smoke.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
MERCHANT="${MERCHANT:-acme}"
RUN_ID="$(date +%s)-$$"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# call <expected-status> <method> <path> [json-body] [idempotency-key]
# Sets $STATUS and $BODY and echoes the exchange.
call() {
  local expected="$1" method="$2" path="$3" body="${4-}" key="${5-}"
  local args=(-sS -D /tmp/payflow-headers.$$ -o /tmp/payflow-body.$$ -w '%{http_code}' -X "$method"
              -H "X-Merchant-Id: $MERCHANT")
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  printf '$ curl -X %s %s%s' "$method" "$BASE" "$path"
  [[ -n "$key" ]] && printf '  [Idempotency-Key: %s]' "$key"
  [[ -n "$body" ]] && printf '\n  > %s' "$body"
  printf '\n'
  STATUS="$(curl "${args[@]}" "$BASE$path")"
  BODY="$(cat /tmp/payflow-body.$$)"
  printf '  < HTTP %s' "$STATUS"
  grep -i '^idempotency-replayed' /tmp/payflow-headers.$$ | tr -d '\r' | sed 's/^/  [/; s/$/]/' | tr -d '\n' || true
  printf '\n  < %s\n' "$(echo "$BODY" | jq -c .)"
  rm -f /tmp/payflow-headers.$$ /tmp/payflow-body.$$
  if [[ "$STATUS" != "$expected" ]]; then
    echo "FAIL: expected HTTP $expected, got $STATUS" >&2
    exit 1
  fi
}

expect() { # expect <jq-filter> <expected-value> : assert on the last $BODY
  local actual
  actual="$(echo "$BODY" | jq -r "$1")"
  if [[ "$actual" != "$2" ]]; then
    echo "FAIL: $1 = $actual, expected $2" >&2
    exit 1
  fi
}

step "Readiness"
curl -fsS "$BASE/actuator/health/readiness"; echo

step "1. Authorize 10000 USD"
call 201 POST /v1/payments '{"amount_minor": 10000, "currency": "USD", "payment_method_token": "tok_visa", "reference": "order-1042"}' "auth-$RUN_ID"
expect .state AUTHORIZED
PAYMENT_ID="$(echo "$BODY" | jq -r .id)"
AUTH_BODY="$BODY"

step "1b. Replay the same authorize request: same response, nothing re-executed"
call 201 POST /v1/payments '{"amount_minor": 10000, "currency": "USD", "payment_method_token": "tok_visa", "reference": "order-1042"}' "auth-$RUN_ID"
[[ "$BODY" == "$AUTH_BODY" ]] || { echo "FAIL: replay body differs" >&2; exit 1; }

step "1c. Same key, different payload -> 422"
call 422 POST /v1/payments '{"amount_minor": 99, "currency": "USD", "payment_method_token": "tok_visa"}' "auth-$RUN_ID"
expect .code IDEMPOTENCY_KEY_REUSED

step "2. Partial capture of 4000"
call 201 POST "/v1/payments/$PAYMENT_ID/captures" '{"amount_minor": 4000, "currency": "USD"}' "cap-$RUN_ID"
expect .payment.state PARTIALLY_CAPTURED
expect .payment.captured_minor 4000

step "3. Partial refund of 1000"
call 201 POST "/v1/payments/$PAYMENT_ID/refunds" '{"amount_minor": 1000, "currency": "USD", "reason": "customer request"}' "ref-$RUN_ID"
expect .payment.state PARTIALLY_REFUNDED
expect .payment.refunded_minor 1000

step "4. Over-capture is refused (409)"
call 409 POST "/v1/payments/$PAYMENT_ID/captures" '{"amount_minor": 7000, "currency": "USD"}' "over-$RUN_ID"
expect .code INVALID_STATE_TRANSITION

step "5. Void the uncaptured remainder"
call 201 POST "/v1/payments/$PAYMENT_ID/voids" '' "void-$RUN_ID"
expect .amount_minor 6000
expect .payment.voided_minor 6000

step "6. Final payment state"
call 200 GET "/v1/payments/$PAYMENT_ID"
expect .captured_minor 4000
expect .refunded_minor 1000
expect .voided_minor 6000

step "7. Ledger (double-entry, every transaction balances)"
call 200 GET "/v1/payments/$PAYMENT_ID/ledger"
UNBALANCED="$(echo "$BODY" | jq '[.transactions[]
  | ([.entries[] | select(.direction=="DEBIT") | .amount_minor] | add) as $d
  | ([.entries[] | select(.direction=="CREDIT") | .amount_minor] | add) as $c
  | select($d != $c)] | length')"
[[ "$UNBALANCED" == "0" ]] || { echo "FAIL: unbalanced ledger transaction" >&2; exit 1; }

step "8. Problem+json errors"
call 404 GET "/v1/payments/00000000-0000-7000-8000-000000000000"
call 400 POST /v1/payments '{"amount_minor": 0, "currency": "USD", "payment_method_token": "t"}' "bad-$RUN_ID"
expect .code VALIDATION_ERROR
call 400 POST /v1/payments '{"amount_minor": 1, "currency": "USD", "payment_method_token": "t"}' ""
expect .code MISSING_IDEMPOTENCY_KEY

printf '\n\033[1;32mSmoke test passed.\033[0m\n'
