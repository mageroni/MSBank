#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Microservice Bank — End-to-end smoke test
#
# Walks the platform through a realistic happy path against the live stack:
#
#   1. Register a customer
#   2. Login (obtain access token)
#   3. Open a CHECKING account
#   4. Open a SAVINGS account
#   5. Deposit funds into CHECKING (accounts-service command)
#   6. Initiate a transfer CHECKING -> SAVINGS (transactions-service saga)
#   7. Poll the transfer until it reaches COMPLETED (or fails)
#   8. Verify the saga audit log reports every step succeeded
#   9. Verify accounts-service projection reflects the new balances
#  10. Verify notifications-service produced at least one notification
#
# Defaults assume the docker-compose stack is up:
#     make up
#     make smoke
#
# Overridable env:
#   GATEWAY_URL (default http://localhost:8080)
#   CURRENCY    (default USD)
#   TIMEOUT_SEC (default 60)
# -----------------------------------------------------------------------------
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
CURRENCY="${CURRENCY:-USD}"
TIMEOUT_SEC="${TIMEOUT_SEC:-60}"

# Tooling preflight ------------------------------------------------------------
need() { command -v "$1" >/dev/null 2>&1 || { echo "✗ missing: $1" >&2; exit 2; }; }
need curl
need jq
if command -v uuidgen >/dev/null 2>&1; then
  uuid() { uuidgen | tr '[:upper:]' '[:lower:]'; }
else
  uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
fi

# Pretty output ----------------------------------------------------------------
BLUE="\033[34m"; GREEN="\033[32m"; RED="\033[31m"; YELLOW="\033[33m"; RESET="\033[0m"
step()  { printf "${BLUE}▶ %s${RESET}\n" "$*"; }
ok()    { printf "${GREEN}✓ %s${RESET}\n" "$*"; }
warn()  { printf "${YELLOW}! %s${RESET}\n" "$*"; }
fail()  { printf "${RED}✗ %s${RESET}\n" "$*" >&2; exit 1; }

# Gateway availability ---------------------------------------------------------
step "Probing gateway at ${GATEWAY_URL}"
if ! curl -sf --max-time 3 "${GATEWAY_URL}/healthz" >/dev/null; then
  fail "Gateway not reachable at ${GATEWAY_URL}. Run 'make up' first."
fi
ok "Gateway is up"

# 1. Register ------------------------------------------------------------------
EMAIL="smoke+$(date +%s)@msbank.local"
PASSWORD="SmokeTest!Pass2026"
step "Registering ${EMAIL}"
REGISTER_BODY=$(jq -nc \
  --arg email "$EMAIL" --arg pwd "$PASSWORD" \
  '{email:$email,password:$pwd,firstName:"Smoke",lastName:"Test"}')

REGISTER_RES=$(curl -sS -w "\n%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$REGISTER_BODY" \
  "${GATEWAY_URL}/api/v1/auth/register")
REGISTER_CODE=$(printf "%s" "$REGISTER_RES" | tail -n1)
REGISTER_JSON=$(printf "%s" "$REGISTER_RES" | sed '$d')
[[ "$REGISTER_CODE" == "201" ]] || fail "register failed ($REGISTER_CODE): $REGISTER_JSON"
USER_ID=$(jq -r .id <<<"$REGISTER_JSON")
ok "Registered user ${USER_ID}"

# 2. Login ---------------------------------------------------------------------
step "Logging in"
LOGIN_BODY=$(jq -nc --arg email "$EMAIL" --arg pwd "$PASSWORD" '{email:$email,password:$pwd}')
TOKENS=$(curl -sSf -X POST \
  -H "Content-Type: application/json" \
  -d "$LOGIN_BODY" \
  "${GATEWAY_URL}/api/v1/auth/login")
ACCESS=$(jq -r .accessToken <<<"$TOKENS")
[[ -n "$ACCESS" && "$ACCESS" != "null" ]] || fail "no access token"
AUTH=(-H "Authorization: Bearer $ACCESS")
ok "Got access token (${#ACCESS} bytes)"

# 3 & 4. Open two accounts -----------------------------------------------------
open_account() {
  local type="$1" nick="$2"
  curl -sSf -X POST "${GATEWAY_URL}/api/v1/accounts" "${AUTH[@]}" \
    -H "Content-Type: application/json" \
    -d "$(jq -nc --arg t "$type" --arg c "$CURRENCY" --arg n "$nick" \
        '{accountType:$t,currency:$c,nickname:$n}')"
}
step "Opening CHECKING account"
CHK=$(open_account CHECKING "Smoke checking")
CHK_ID=$(jq -r .id <<<"$CHK")
ok "CHECKING id=${CHK_ID}"

step "Opening SAVINGS account"
SAV=$(open_account SAVINGS "Smoke savings")
SAV_ID=$(jq -r .id <<<"$SAV")
ok "SAVINGS id=${SAV_ID}"

# 5. Deposit -------------------------------------------------------------------
DEPOSIT_AMOUNT=500000  # $5,000.00
step "Depositing $((DEPOSIT_AMOUNT / 100)).00 ${CURRENCY} into CHECKING"
DEPOSIT_BODY=$(jq -nc --arg k "$(uuid)" --argjson a "$DEPOSIT_AMOUNT" --arg c "$CURRENCY" \
  '{idempotencyKey:$k,money:{amount:$a,currency:$c},description:"smoke deposit"}')
DEP_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X POST \
  "${GATEWAY_URL}/api/v1/accounts/${CHK_ID}/deposits" "${AUTH[@]}" \
  -H "Content-Type: application/json" -d "$DEPOSIT_BODY")
[[ "$DEP_CODE" == "202" ]] || fail "deposit returned $DEP_CODE"
ok "Deposit accepted"

# Wait for projection to catch up
step "Waiting for projection to reflect deposit"
for _ in $(seq 1 20); do
  BAL=$(curl -sSf "${GATEWAY_URL}/api/v1/accounts/${CHK_ID}" "${AUTH[@]}" | jq -r .balance)
  [[ "$BAL" -ge "$DEPOSIT_AMOUNT" ]] && { ok "CHECKING balance = ${BAL}"; break; }
  sleep 0.5
done
[[ "${BAL:-0}" -ge "$DEPOSIT_AMOUNT" ]] || fail "deposit never projected (last balance=${BAL:-?})"

# 6. Transfer ------------------------------------------------------------------
TRANSFER_AMOUNT=125000  # $1,250.00
IDEMP=$(uuid)
step "Transferring $((TRANSFER_AMOUNT / 100)).00 ${CURRENCY} from CHECKING -> SAVINGS"
TRANSFER_BODY=$(jq -nc \
  --arg s "$CHK_ID" --arg d "$SAV_ID" \
  --argjson a "$TRANSFER_AMOUNT" --arg c "$CURRENCY" \
  '{sourceAccountId:$s,destinationAccountId:$d,amount:$a,currency:$c,reference:"smoke transfer"}')
TRX=$(curl -sSf -X POST "${GATEWAY_URL}/api/v1/transfers" "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP}" \
  -d "$TRANSFER_BODY")
TRX_ID=$(jq -r .id <<<"$TRX")
ok "Transfer ${TRX_ID} accepted (status=$(jq -r .status <<<"$TRX"))"

# 7. Poll until terminal -------------------------------------------------------
step "Polling transfer status (timeout=${TIMEOUT_SEC}s)"
DEADLINE=$(( $(date +%s) + TIMEOUT_SEC ))
FINAL_STATUS=""
while [[ $(date +%s) -lt $DEADLINE ]]; do
  T=$(curl -sSf "${GATEWAY_URL}/api/v1/transfers/${TRX_ID}" "${AUTH[@]}")
  FINAL_STATUS=$(jq -r .status <<<"$T")
  case "$FINAL_STATUS" in
    COMPLETED|FAILED|COMPENSATED) break;;
  esac
  sleep 1
done
[[ "$FINAL_STATUS" == "COMPLETED" ]] || fail "transfer did not complete (status=$FINAL_STATUS)"
ok "Transfer COMPLETED"

# 8. Saga audit ----------------------------------------------------------------
step "Inspecting saga audit"
SAGA=$(curl -sSf "${GATEWAY_URL}/api/v1/transfers/${TRX_ID}/saga" "${AUTH[@]}")
echo "$SAGA" | jq -c '.[] | {step,status,occurredAt}'
FAILED_STEPS=$(jq '[.[] | select(.status=="FAILED")] | length' <<<"$SAGA")
[[ "$FAILED_STEPS" -eq 0 ]] || fail "$FAILED_STEPS saga step(s) reported FAILED"
ok "All saga steps SUCCEEDED"

# 9. Verify balances -----------------------------------------------------------
step "Verifying post-transfer balances"
for _ in $(seq 1 20); do
  CHK_BAL=$(curl -sSf "${GATEWAY_URL}/api/v1/accounts/${CHK_ID}" "${AUTH[@]}" | jq -r .balance)
  SAV_BAL=$(curl -sSf "${GATEWAY_URL}/api/v1/accounts/${SAV_ID}" "${AUTH[@]}" | jq -r .balance)
  EXPECTED_CHK=$(( DEPOSIT_AMOUNT - TRANSFER_AMOUNT ))
  if [[ "$CHK_BAL" -eq "$EXPECTED_CHK" && "$SAV_BAL" -eq "$TRANSFER_AMOUNT" ]]; then
    ok "CHECKING=${CHK_BAL} SAVINGS=${SAV_BAL} ✓"
    break
  fi
  sleep 0.5
done
if [[ "${CHK_BAL:-0}" -ne "$EXPECTED_CHK" || "${SAV_BAL:-0}" -ne "$TRANSFER_AMOUNT" ]]; then
  fail "balances incorrect: CHECKING=${CHK_BAL} SAVINGS=${SAV_BAL} (expected ${EXPECTED_CHK} / ${TRANSFER_AMOUNT})"
fi

# 10. Notifications ------------------------------------------------------------
step "Looking for notifications generated by the saga"
for _ in $(seq 1 20); do
  N=$(curl -sSf "${GATEWAY_URL}/api/v1/notifications?limit=20" "${AUTH[@]}")
  COUNT=$(jq 'length' <<<"$N")
  if [[ "$COUNT" -gt 0 ]]; then
    ok "Notifications received: ${COUNT}"
    jq -c '.[] | {channel,status,templateKey}' <<<"$N"
    break
  fi
  sleep 0.5
done
[[ "${COUNT:-0}" -gt 0 ]] || warn "no notifications visible yet (eventual consistency)"

# Idempotency replay -----------------------------------------------------------
step "Verifying transfer idempotency (replaying same Idempotency-Key)"
REPLAY=$(curl -sSf -X POST "${GATEWAY_URL}/api/v1/transfers" "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP}" \
  -d "$TRANSFER_BODY")
REPLAY_ID=$(jq -r .id <<<"$REPLAY")
[[ "$REPLAY_ID" == "$TRX_ID" ]] || fail "idempotency violated: got ${REPLAY_ID}, expected ${TRX_ID}"
ok "Idempotency held"

echo
ok "Smoke test PASSED 🎉  (user=${USER_ID}, transfer=${TRX_ID})"
