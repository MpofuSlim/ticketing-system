# Payments — Frontend Integration Guide

Practical reference for integrating the customer money paths (transfer,
withdraw, history) and the auth flow that underpins them. Pairs with the
Swagger UI (each backend service exposes `/swagger-ui/index.html`); this
doc covers the cross-cutting rules that don't live on any single endpoint
— auth tokens, idempotency keys, session-supersession, error codes.

Base URL: the API gateway (`http://localhost:8080` in dev). All endpoints
below assume that prefix unless noted.

---

## 1. Auth — login, refresh, session supersession

### 1.1 Login

```http
POST /auth/login
Content-Type: application/json
X-Device-Id: 9f5e8c1a-3d0a-4b27-9f8d-7e1c0b6a2f54

{
  "identifier": "+254777224008",       // email or msisdn — either works
  "password": "..."
}
```

`X-Device-Id` is **strongly recommended** — send a stable per-install UUID
(see §1.5). The server stores its SHA-256 against the refresh-token row and
will reject any subsequent `/auth/refresh` that doesn't present the same id.

Returns:

```json
{
  "code": "200 OK",
  "message": "Login successful",
  "data": {
    "token":        "eyJhbGciOiJIUzI1NiJ9...",   // 15-min access token
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",   // 7-day refresh token
    "email":        "user@example.com",
    "roles":        ["CUSTOMER"],
    "tier":         2,
    "verified":     true
  }
}
```

Store **both tokens**. The access token goes on every authenticated
request as `Authorization: Bearer <token>`. The refresh token is used
only to mint a new access token when the current one expires.

### 1.2 Token TTLs

| Token   | TTL    | Where to use it                            |
|---------|--------|--------------------------------------------|
| Access  | 15 min | `Authorization: Bearer ...` on every call. |
| Refresh | 7 days | Only on `POST /auth/refresh`.              |

Access tokens are deliberately short. Wire up an axios / fetch
interceptor that on **any** 401 attempts a refresh once, then retries
the original request. Don't ask the user to log in again unless the
refresh itself fails.

### 1.3 Refresh

```http
POST /auth/refresh
Content-Type: application/json
Authorization: Bearer <the refresh token you stored>
X-Device-Id: 9f5e8c1a-3d0a-4b27-9f8d-7e1c0b6a2f54
```

Returns the same envelope as login. **Replace both tokens** in storage
(the refresh token rotates — the old one is now revoked, presenting it
again is treated as token theft and the entire family is killed).

`X-Device-Id` MUST match the value sent on the original `/auth/login`
that minted the family. A mismatch — or the header missing on a family
that was bound at login — fires the same "token reuse detected" path
as a replayed refresh token: the entire family is revoked, the response
is `400` with `Refresh token reuse detected; family revoked`, and the
user has to log in again.

### 1.4 Single active session — `SESSION_SUPERSEDED`

A new login from any device immediately bumps `users.token_version` and
revokes all of that user's prior refresh-token families. Inside
user-service every JWT carries the version at mint time; an older token
arrives with a stale version and gets:

```json
{
  "code": "SESSION_SUPERSEDED",
  "message": "This session has been ended by a newer login",
  "data": null
}
```

Treat this differently from `INVALID_TOKEN`:

| Error code          | What it means                              | What to do                                   |
|---------------------|--------------------------------------------|----------------------------------------------|
| `INVALID_TOKEN`     | Tampered / expired / malformed token       | Try `/auth/refresh` once, then re-login      |
| `TOKEN_REVOKED`     | Explicit logout / admin revoke             | Clear tokens, route to login                 |
| `SESSION_SUPERSEDED`| User logged in on another device          | Clear tokens, route to login with a friendly "you were signed in elsewhere" toast |

Inside the **money services** (payment-service, etc.) only signature +
expiry are validated — they don't read `token_version`. So a superseded
session on the old device keeps working there for up to 15 min before
the access token expires. That's acceptable because (a) the refresh is
already dead so it can't extend itself, and (b) the FE should be routing
to login on the first SESSION_SUPERSEDED hit anyway.

### 1.5 Device binding (`X-Device-Id`)

Refresh tokens are bound to the device that minted them. A stolen
refresh token replayed from a different device is rejected and burns
the whole family. The mechanism is one header:

```
X-Device-Id: <stable per-install UUID>
```

Send it on `POST /auth/login` and on every `POST /auth/refresh`. The
server stores `SHA-256(deviceId)` against the refresh-token row at
login and compares against the hash of the rotate-request's header.

**Generating the id**

- Generate **once on app install** and persist it for the life of the
  install. Reuse across logins on the same install.
- Format: a v4 UUID is fine. Anything stable per install and hard to
  guess for an attacker works.
- Storage: a write-once secure-storage slot (iOS Keychain /
  Android EncryptedSharedPreferences / web `localStorage` is acceptable
  for the SuperApp since the threat is cross-device replay, not
  same-origin XSS).

```javascript
// Web example — generate once, reuse forever
function getDeviceId() {
  let id = localStorage.getItem('deviceId');
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem('deviceId', id);
  }
  return id;
}
```

```kotlin
// Android example — EncryptedSharedPreferences
fun getDeviceId(ctx: Context): String {
    val prefs = EncryptedSharedPreferences.create(
        ctx, "innbucks", masterKey,
        AES256_SIV, AES256_GCM
    )
    return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }
}
```

**Rules**

- Send the **same** id on `/auth/login` AND every `/auth/refresh` for
  that session. If you can't read storage at refresh time, drop the
  refresh token — don't fake a different id, that revokes the family.
- Don't regenerate the id on login. Each fresh id starts a new
  binding; if you regenerate every login the user just won't notice
  the breakage, but you lose the cross-session-anomaly signal.
- A user-initiated "log out everywhere" flow will re-mint the id
  next install (uninstall + reinstall). That's fine.

**Backward compatibility**

Refresh-token rows minted **before** this feature shipped don't carry
a hash. They keep rotating without enforcement for the remainder of
their 7-day TTL. So FE clients can roll out the header at their own
pace — old refresh tokens won't break — but every new login from a
header-aware FE is bound immediately.

If the FE is on an old build with no `X-Device-Id`, the login still
works (the row is stored unbound, "legacy session" mode). Refreshing
also works. The risk profile in that case is identical to today's
behavior, just unimproved.

### 1.6 Logout

```http
POST /auth/logout
Authorization: Bearer <access token>
```

Revokes the specific access token (denylist) + the refresh token. Use
this on user-initiated logout — `SESSION_SUPERSEDED` doesn't need it
(the version bump did the work).

---

## 2. Idempotency-Key contract

### 2.1 When it's required

`Idempotency-Key` is **required** on:

- `POST /payments/transfer`
- `POST /payments/withdraw`

Missing key → `400 BAD_REQUEST` with `errorCode: "idempotency_key_required"`.
Other mutating endpoints (`/payments/shop-checkout`, `/payments`) accept
the header but don't require it.

### 2.2 How to mint it

> **Generate the key when the user expresses intent (the Send button
> tap), NOT when the network call starts.**

This is the only rule that matters. A correctly-generated key:

```javascript
// React example
const [idempotencyKey] = useState(() => crypto.randomUUID());
```

A buggy one:

```javascript
// Wrong — regenerated on every retry
async function sendMoney() {
  const idempotencyKey = crypto.randomUUID();  // NO!
  await fetch('/payments/transfer', {...});
}
```

The first form gives the SAME key to every retry of the same logical
attempt. The second gives a fresh key on every HTTP call — defeating
the entire mechanism and re-exposing the customer to double-charges
on network blips.

### 2.3 Same key + different body → 422

Reusing one key for a $1 transfer and then a $1000 transfer is rejected:

```json
{
  "code": "422 UNPROCESSABLE_ENTITY",
  "message": "Idempotency-Key reused with a different request body — refusing to replay",
  "data": null,
  "errorCode": "idempotency_conflict"
}
```

If the user edits the amount and re-submits, mint a **new** key for the
second attempt. Trigger: re-mount the confirm-screen state so the
`useState` initialiser runs again.

### 2.4 Replay semantics

Same key + same body within 24h → the cached 200 response is replayed
byte-for-byte. The FE sees the original confirmation. The money moved
exactly once.

After 24h the cache entry evicts and the request re-runs. Velocity caps
+ Oradian's own duplicate-check protect against double-sends if the FE
somehow retries the same logical attempt across a 24h boundary.

---

## 3. Transfer — move money between two Oradian accounts

```http
POST /payments/transfer
Authorization: Bearer <access token>
Content-Type: application/json
Idempotency-Key: <fresh UUID per user-tap>

{
  "fromAccountId": "A000001",     // source — MUST be one of the caller's accounts
  "toAccountId":   "A000002",     // destination
  "amount":        "123.00",      // string; max 4 decimal places; > 0
  "notes":         "Lunch"        // optional
}
```

> **Don't send `transactionDate`** — it's server-stamped to today. Any
> value the FE sends is ignored.

Success (200):

```json
{
  "code": "200 OK",
  "message": "Deposit transfer submitted",
  "data": {
    "fromAccountId": "A000001",
    "toAccountId":   "A000002",
    "amount":        "123.00",
    "notes":         "Lunch",
    "transactionDate":  "2026-05-19",
    "transactionID":    "1155",            // Oradian's ID — show on the receipt
    "referenceNumber":  "1234567980123"    // also useful on the receipt
  }
}
```

### 3.1 Failure shapes

See §5 below for the unified error catalog. Key ones specific to transfer:

- `400` — missing fields, missing Idempotency-Key, amount fails parse / sign / scale
- `401` — token issues (see auth section)
- `403` — three sub-reasons distinguished by message text:
  - `"fromAccountId does not belong to the authenticated customer"`
  - `"Customer must be at KYC tier 2 or higher to use this endpoint"`
  - `"Source account is not Active (status: Frozen)"` (or Closed / Dormant)
- `422` — `idempotency_conflict`
- `502` — Oradian rejected (validation, insufficient funds) — `message` carries the upstream reason

---

## 4. Withdraw — money out of a deposit account

```http
POST /payments/withdraw
Authorization: Bearer <access token>
Content-Type: application/json
Idempotency-Key: <fresh UUID per user-tap>

{
  "accountID":         "A000015",
  "paymentMethodName": "Cash",            // value from Oradian's config (e.g. Cash, MobileMoney)
  "amount":            "10.00",
  "notes":             "Cash out"         // optional
}
```

> Three fields are **server-stamped** and any value the FE sends is
> ignored: `transactionDate` (today), `transactionBranchID` (always
> `MobileBanking`), `overrideLimitCheck` (always `false`).

Success / failure shapes mirror Transfer. Same 403 reasons (substitute
`accountID` for `fromAccountId` in the message).

---

## 5. Error code catalog

Bookmark this when wiring error UX. Codes come either in the `code`
field (HTTP status name) or as `errorCode` (specific to the failure).

| code / errorCode                       | HTTP | What to do                                                       |
|----------------------------------------|------|------------------------------------------------------------------|
| `INVALID_TOKEN`                        | 401  | Try refresh once; if that fails, log out                         |
| `TOKEN_REVOKED`                        | 401  | Log out                                                          |
| `SESSION_SUPERSEDED`                   | 401  | Log out with a "signed in elsewhere" toast                       |
| `idempotency_key_required` (400)       | 400  | Bug. The FE must always send the header. Don't show to the user. |
| `idempotency_conflict` (422)           | 422  | Bug. The FE reused a key with a different body. Don't show to the user. |
| `"Customer must be at KYC tier 2 ..."` | 403  | Route to KYC upgrade flow                                         |
| `"Source account is not Active ..."`   | 403  | Show "your account is {Frozen/Closed/Dormant}, contact support"   |
| `"fromAccountId/accountID does not ..."`| 403 | Stale account picker — refresh `/auth/customer/deposits`         |
| `"Daily limit exceeded (...)"`         | 400  | Show "you've hit your daily limit"                               |
| `"Per-transaction limit exceeded (...)"`| 400 | Show "amount too large; max is {N}"                              |
| `"amount must be greater than zero"`   | 400  | Form validation bug — block on the FE before send                |
| `"amount must be a valid decimal ..."` | 400  | Form validation bug — same                                       |
| `"amount must have at most 4 decimal places"` | 400 | Form validation bug — same                                  |
| `"Oradian middleware rejected ..."`    | 502  | Show the message verbatim — Oradian's reason is customer-facing (insufficient funds, etc.) |
| `"Oradian middleware is temporarily unavailable (circuit open)"` | 503 | Show "service temporarily unavailable, try again in a few minutes" |
| HTTP 429                               | 429  | Rate limit hit — back off (see §8)                                |

For the 502s where the message carries Oradian's actual reason, show
the message text. Common ones:
- `"... Insufficient funds"`
- `"... The maximum transfer amount for outgoing account (A000009) is KSh0.00"`
- `"... Account is suspended"`

---

## 6. Transaction history

### 6.1 List

```http
GET /payments/transactions?fromDate=2026-04-19&toDate=2026-05-19&page=0&size=20
Authorization: Bearer <access token>
```

All params optional. Defaults:
- `fromDate`: 30 days before `toDate`
- `toDate`: today
- `page`: 0
- `size`: 20 (max 100; values larger are silently clamped)

Returns paginated `TransactionView` rows, newest-first. Includes
`PENDING` rows so the FE can show in-flight transfers, plus
`FAILED` rows so the user can see why something didn't go through.

Fields stripped before serialisation (you'll never see them): the
internal idempotency key, the correlation id, and Oradian's command id.

### 6.2 Detail (single transaction)

```http
GET /payments/transactions/{id}
Authorization: Bearer <access token>
```

Returns the same `TransactionView` shape as a single element of the
list. Use this for receipt screens / share-receipt flows.

**404 semantics**: a 404 means either "no such transaction" OR "exists
but belongs to another customer". The two are merged so a caller can't
probe UUIDs to discover other people's transactions.

---

## 7. Other endpoints you'll need

### 7.1 List the caller's Oradian deposit accounts (with balances)

```http
GET /auth/customer/deposits
Authorization: Bearer <access token>
```

Returns the customer's owned accounts. Use this to populate the
"from account" picker on transfer / withdraw, and to render balances
on the home screen. Each row includes `ID`, `productName`, `balance`,
`status` (Active / Frozen / etc.), `currencyCode`.

### 7.2 Look up a recipient's accounts by phone (for send-money)

```http
GET /auth/customer/send-money/details/{recipientPhoneNumber}
Authorization: Bearer <access token>
```

Returns the recipient's deposit-account identifiers. The balance,
subscribed amount, and lifecycle dates are stripped — the sender
shouldn't see the recipient's full account state.

---

## 8. Rate limits + retries

### 8.1 Gateway-side per-token rate limit

`/payments/**` is split by HTTP method at the gateway:

| Side  | Methods                  | Replenish | Burst |
|-------|--------------------------|-----------|-------|
| Read  | `GET`                    | 50 rps    | 100   |
| Write | `POST`, `PUT`, `PATCH`, `DELETE` | 1 rps | 5  |

Same limits per bearer token. Reads use the generous default that
matches `/events/**`, `/bookings/**`, etc. — so history pagination,
detail-screen fetches, and pull-to-refresh won't hit the bucket under
normal use. Writes use the tight limit because real customers
transfer / withdraw a handful of times per day; anything north of
1 rps on the money path is anomalous.

If you hit it, you get `HTTP 429` from the gateway. Back off and retry.

### 8.2 Retries on transient failures

The backend has its own retry-on-transient against Oradian (3 attempts
with exponential backoff, ~3.5s ceiling). So:

- A 502 with `"Oradian middleware is temporarily unavailable"` means
  even our internal retries gave up. **The FE should NOT silently
  retry** — surface the error and let the user decide.
- A 503 with `"circuit open"` means Oradian is having a sustained
  outage and we've stopped trying. Same advice: surface, don't retry.

If you DO retry from the FE (e.g. on user tap-to-retry), **reuse the
same Idempotency-Key**. Generating a fresh key on a manual retry is
the same bug as generating it inside the fetch function.

---

## 9. Velocity caps

`POST /payments/transfer` and `POST /payments/withdraw` enforce two
caps server-side:

- **Per-transaction**: configurable, default `100,000` (currency unit).
  A typo turning `"100"` into `"100000"` lands here.
- **Per-day per source account**: configurable, default `500,000`.
  Sum of today's `PENDING + SUCCEEDED` rows on that account.

Override per deployment via `PER_TX_LIMIT` / `PER_DAY_LIMIT` env vars.
For Kenya, ask backend before shipping FE — placeholders won't match
the real banking limits.

---

## 10. Status lifecycle (for the receipt UX)

`TransactionView.status` transitions:

```
        ┌─────────┐
   ─────▶ PENDING │
        └────┬────┘
             │
       ┌─────┴──────┐
       ▼            ▼
 ┌──────────┐  ┌────────┐
 │ SUCCEEDED│  │ FAILED │
 └──────────┘  └────────┘
```

- `PENDING`: payment-service has called Oradian and is awaiting the
  response (or the response came back but the local "mark succeeded"
  write hasn't committed yet). Briefly visible in normal operation;
  long-lived `PENDING` rows are a reconciliation concern.
- `SUCCEEDED`: Oradian confirmed the money moved. Receipt is final.
- `FAILED`: Oradian or the gate rejected the call. `failureCode` +
  `failureMessage` carry the why.

There's no `CANCELLED` / `REVERSED` today. Reversals go through
support tooling, not the customer app.

---

## 11. Sample end-to-end flow

1. **App start** → `POST /auth/login` → store both tokens.
2. **Home screen** → `GET /auth/customer/deposits` → render account
   tiles with balances.
3. **User taps "Send money"** → opens recipient picker.
4. **User picks recipient by phone** → `GET /auth/customer/send-money/details/{phone}`
   → render their accounts (no balances visible to the sender).
5. **User confirms transfer screen mount** →
   `const [idempotencyKey] = useState(() => crypto.randomUUID());`
6. **User taps Send** → `POST /payments/transfer` with the key.
   - On `200`: render receipt with `transactionID` + `referenceNumber`.
   - On `403 Frozen`: route to "contact support" flow.
   - On `403 tier 2`: route to KYC upgrade.
   - On `502`: show Oradian's reason text.
   - On `401 SESSION_SUPERSEDED`: clear tokens, route to login.
7. **User views history** → `GET /payments/transactions`.
8. **User taps a row** → `GET /payments/transactions/{id}` → receipt.

---

## 12. Quick gotchas checklist

- [ ] `Idempotency-Key` minted on the confirm-screen mount, not inside the fetch
- [ ] Same key reused on retry; new key on a user-edit + re-submit
- [ ] Refresh interceptor wired up (15-min access TTL)
- [ ] `SESSION_SUPERSEDED` routes to login, not just a generic 401 toast
- [ ] `transactionDate` / `transactionBranchID` / `overrideLimitCheck` NOT sent on the request
- [ ] 502 message text shown verbatim — it's the customer-facing Oradian reason
- [ ] 429 handled with backoff, not a tight retry loop
- [ ] FE doesn't generate amounts with > 4 decimal places (server rejects)
- [ ] Tier-1 customer attempts to transfer routed to KYC upgrade UX
- [ ] History view shows PENDING + SUCCEEDED + FAILED (don't hide PENDING — user wants to know it's in flight)
