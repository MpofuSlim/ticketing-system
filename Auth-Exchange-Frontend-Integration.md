# Federated customer login — `POST /auth/exchange`

**Frontend integration guide (super app).** How a customer who signed in at the
InnBucks middleware gets a fleet token, which is what every `CUSTOMER`-gated
endpoint in marketplace, bookings and loyalty's authenticated rail requires.

> [!IMPORTANT]
> **Status: merged, deployed, and switched OFF on the ZW cell.**
> `AUTH_FEDERATION_ENABLED=false` in `deploy/cells/cell.zw.env`, and no public
> key is provisioned. **Every call returns `404` today.** This is the documented
> state, not a bug — the endpoint is waiting on the middleware to sign
> assertions and hand over its public key. See
> `Auth-Exchange-Assertion-Signing-Spec.md` for what that side owes.
>
> Build against this contract now. Nothing below changes when the switch flips —
> a `404` becomes a `200` and the rest of the app starts working unchanged.

---

## 1. Where this sits

Two audiences, two identity providers, **one token shape**:

| Audience | Signs in at | Gets a fleet token how |
|---|---|---|
| Merchants, operators | Foundry (`POST /auth/login`, password) | Directly |
| **Super-app customers** | **The InnBucks middleware** | **`POST /auth/exchange`** |

The fleet can neither verify the middleware's own `accessToken` (no key) nor
introspect it (no endpoint — this was measured for InnRewards V42; do not
re-try it). So the middleware signs a short-lived **assertion** — "the owner of
this number just authenticated with me" — and the app trades it here for a
normal fleet session.

**The response is byte-identical in shape to `POST /auth/login`.** Once you hold
that token, `/auth/refresh`, `/auth/logout`, marketplace orders, bookings and
loyalty's authenticated surface all work unchanged and cannot tell how the
customer proved themselves.

---

## 2. The call

```http
POST /auth/exchange
Content-Type: application/json
X-Device-Id: <your stable per-install device id>
```

```json
{
  "assertion": "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJpbm5idWNrcy1taWRkbGV3YXJlIiwiYXVkIjoiaW5uYnVja3MtZm91bmRyeSIsInN1YiI6IisyNjM3NzEyMzQ1NjciLCJqdGkiOiI3YzUxYzM4ZS0uLi4iLCJpYXQiOjE3NTc0OTQ4MDAsImV4cCI6MTc1NzQ5NTEwMH0.sig"
}
```

- **`assertion` is the only field.** Deliberately — there is no second phone
  field, because the phone is read from the assertion's *signed* `sub`. A caller
  cannot pair their own number with somebody else's valid assertion.
- **Max 8192 characters.** Longer is a `400`.
- **No `X-Tenant-Id`.** The `/auth/**` routes carry no tenant predicate.
- **`X-Device-Id` is optional but send it**, exactly as you do on `/auth/login`.
  It binds the refresh family to the device; without it you lose the
  device-mismatch protection on the whole refresh chain.
- **Unauthenticated.** Do not send an `Authorization` header — the credential
  *is* the assertion in the body.

**Rate limit (gateway, per source IP):** 5/second sustained, burst 20. A real
app calls this once per login per device, so you will not see it; a retry loop
will. It is fail-safe — a Redis outage keeps the cap, it does not lift it.

---

## 3. Success — `200`

```json
{
  "code": "200 OK",
  "message": "Login successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...access-signature",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...refresh-signature",
    "roles": ["CUSTOMER"],
    "defaultServices": [],
    "mfaRequired": false,
    "tier": 1,
    "verified": false
  }
}
```

| Field | Notes |
|---|---|
| `token` | The access token. Send as `Authorization: Bearer <token>`. |
| `refreshToken` | Use **only** at `POST /auth/refresh`. Rejected everywhere else. |
| `roles` | Always exactly `["CUSTOMER"]` on this endpoint. |
| `tier` | Customer registration tier, 1–4. A first sign-in is **tier 1**. |
| `verified` | Tier-4 verification flag. `false` for a new customer. |
| `email` | **Omitted entirely** when the account has none (`NON_NULL`). Most federated customers have none — do not expect the key. |
| `mfaRequired` | Always `false` here. Customers do not carry a second factor. |

**First sign-in silently creates the account.** A phone with no Foundry account
gets a tier-1 `CUSTOMER` created — active, approved, placeholder name
`Customer Pending`, `phoneVerified` stamped, and loyalty is told so the
customer's points projections activate with no SMS. There is no separate
registration call to make and no "new user" flag in the response; a returning
customer is simply signed in. **If your UI wants a real name, collect it after
first sign-in** via the customer profile endpoints — the placeholder is what the
account starts with.

### Token lifetimes

| Token | Default TTL | Source |
|---|---|---|
| Access `token` | **15 minutes** (`JWT_EXPIRATION_MS=900000`) | Same as a password login |
| `refreshToken` | **7 days** (`JWT_REFRESH_EXPIRATION_MS=604800000`) | Rotating family |

These are per-cell env values — read them as the current ZW configuration, not
as a contract. Drive your refresh off the access token's own `exp` claim, never
off a hardcoded 15.

### Refreshing

```http
POST /auth/refresh
Authorization: Bearer <refreshToken>
X-Device-Id: <same device id>
```

The refresh token goes in the **`Authorization` header, not a body field.**
Returns the same `AuthResponse` shape with a **new access token and a new
refresh token** — store both, atomically.

- **Rotation is single-use.** Presenting an already-rotated refresh token is
  read as theft and revokes the **entire family** — the customer is logged out
  everywhere. This is why the store must be atomic: a crash between "got new
  pair" and "saved new pair" costs the session.
- Refresh re-reads the live account, so a tier upgrade (1 → 2) lands here
  without a full re-login.
- **When refresh fails, go back to the middleware**, not to `/auth/exchange`
  with the old assertion — it was spent on first use (see below).

---

## 4. Failures

Every one renders in the standard envelope: `{code, message, data}`.

| Status | `message` | What it means | What the app does |
|---|---|---|---|
| `400` | `assertion is required` | Field missing or blank | Fix the request |
| **`401`** | **`Assertion rejected`** | **Opaque — see below** | **Re-authenticate at the middleware, get a fresh assertion, retry once** |
| `409` | (varies) | Wrong cell — `data.errorCode = "wrong_cell"`, plus `homeCountry` and `homeBaseUrl` | Redirect to the customer's home cell at `homeBaseUrl` |
| `404` | `Not found` | Federated login is **off on this cell** | **This is today's ZW state.** Fall back to whatever you do now |
| `503` | `Federated login is not provisioned on this cell` | On, but no public key — half-provisioned | Retryable; it is an ops fault, page someone |
| `503` | `Login is temporarily unavailable; please try again` | The replay guard (Redis) could not be consulted | Retryable with backoff |

### The `401` is deliberately one message

All of these produce the identical body:

- bad signature, wrong `iss`, wrong `aud`, `alg` not allow-listed
- expired, or `exp - iat` over the 300s ceiling
- **replayed** — the assertion was already spent
- the phone belongs to a **staff account** (merchant admin, operator)
- the account is **inactive**
- the `sub` is not a normalisable phone number

Telling a caller which check failed is free help to an attacker. **Which one it
was is written to the audit log** (`AUTH_FEDERATED_LOGIN_REJECTED`, with a
`failure_reason`) — so when a customer reports "it just says rejected", the
answer is in the backend audit trail, and that is where support should look.

**Do not build a retry loop on `401`.** The client's remedy is always the same
and always one step: sign in at the middleware again, post the *fresh*
assertion. Retrying the same assertion is guaranteed to fail — it was burned.

---

## 5. Rules the app must honour

1. **One assertion, one use, ever.** The `jti` is spent in Redis *before* any
   account work, and held for the assertion's remaining life plus 60s. Never
   cache an assertion, never retry with the same one, never reuse it across two
   devices. Get a fresh one per exchange.
2. **Assertions are short.** `exp - iat` may not exceed **300 seconds**, and the
   verifier allows 30s of clock skew. Post it immediately after the middleware
   login — do not hold it across a screen transition that might sit for minutes.
3. **Exchange once per login, then live on refresh.** The refresh chain is the
   session. Calling `/auth/exchange` again mid-session means a second middleware
   login for no reason.
4. **Only ever a `CUSTOMER`.** A staff member who also shops must use a
   different number. A middleware login can never become a merchant or admin
   session, whatever the assertion says — that is a guarantee, not a limitation
   to work around.
5. **The customer is passwordless here.** No password was ever chosen, so
   `POST /auth/login` will not work for them. If they ever want a direct Foundry
   login, route them through the OTP-gated forgot-password flow to set one.

---

## 6. Gotchas checklist

- [ ] `404` on ZW right now is **expected** — the feature is off. Handle it as
      "federated login unavailable", not as a bug report.
- [ ] Send `X-Device-Id`, and send the **same value** on `/auth/refresh`.
- [ ] Store `token` **and** `refreshToken` atomically on every response.
- [ ] Refresh token goes in the `Authorization` header, not the body.
- [ ] Never retry a `401` with the same assertion.
- [ ] Do not expect an `email` key in the response.
- [ ] Do not send `X-Tenant-Id` on `/auth/exchange`.
- [ ] Treat `409 wrong_cell` as a redirect, using `data.homeBaseUrl`.
- [ ] Drive refresh timing off the access token's `exp`, not a hardcoded 15 min.
- [ ] The account starts named `Customer Pending` — prompt for a real name after
      first sign-in if your UI shows one.
