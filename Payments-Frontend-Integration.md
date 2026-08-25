# Payments — Frontend Integration Guide

Practical reference for the auth flow and the cross-cutting request rules
that apply to every customer-facing call. Pairs with the Swagger UI (each
backend service exposes `/swagger-ui/index.html`); this doc covers the
rules that don't live on any single endpoint — auth tokens, device
binding, idempotency keys, session-supersession, gateway rate limits.

> **The wallet money paths documented here previously — transfer, withdraw,
> withdrawal/transfer history, deposit-account lookup and send-money
> recipient lookup — no longer exist.** They were backed by the Oradian
> middleware, which has been removed; the frontend now calls Veengu
> directly for those operations. What remains in this backend on
> `/payments` is the **ticket/order payment** rail (`POST /payments`,
> InnBucks 2D code and ZimSwitch card) plus `POST /payments/shop-checkout`
> — see the payment-service Swagger for those.

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

The two endpoints that used to *require* `Idempotency-Key`
(`POST /payments/transfer`, `POST /payments/withdraw`) are gone. No
endpoint requires it today: `/payments`, `/payments/shop-checkout` and the
other mutating paths accept the header and honour it, but don't reject a
request that omits it.

Send it anyway on anything that moves money or creates an order — the
replay semantics below are what stop a retried request from charging
twice.

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
async function pay() {
  const idempotencyKey = crypto.randomUUID();  // NO!
  await fetch('/payments', {...});
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

After 24h the cache entry evicts and the request re-runs. Don't rely on
the cache as the only guard across a 24h boundary — a logical attempt that
old should be re-confirmed with the user rather than silently retried.

---

## 3. Rate limits

`/payments/**` is split by HTTP method at the gateway:

| Side  | Methods                  | Replenish | Burst |
|-------|--------------------------|-----------|-------|
| Read  | `GET`                    | 50 rps    | 100   |
| Write | `POST`, `PUT`, `PATCH`, `DELETE` | 1 rps | 5  |

Same limits per bearer token. Reads use the generous default that matches
`/events/**`, `/bookings/**`, etc. Writes use the tight limit because the
money path is low-frequency by nature; anything north of 1 rps on it is
anomalous.

If you hit it, you get `HTTP 429` from the gateway. Back off and retry.
