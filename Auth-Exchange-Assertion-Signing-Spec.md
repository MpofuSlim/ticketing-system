# Federated login assertion — signing spec

**For whoever operates the InnBucks middleware / broker** (the system where
super-app customers actually sign in). This is the complete contract the Foundry
fleet already verifies. Nothing here is a proposal — it is merged, deployed
code; implementing it turns the feature on.

**Verifier, for reference:**
`user-service/src/main/java/com/innbucks/userservice/security/FederationAssertionVerifier.java`

---

## 1. What is being asked for

Foundry cannot verify or introspect the middleware's own `accessToken`. So after
a successful customer login, the middleware **signs a short-lived JWT** saying
"the owner of this number just authenticated with me". The app posts that to
`POST /auth/exchange` and receives a normal fleet `CUSTOMER` session.

**The middleware holds the private key. Foundry holds only the public key.**
That asymmetry is the whole point: there is no credential anywhere in the fleet
that, if leaked, would let anyone mint a customer session.

Two things are owed:

1. **Sign an assertion at login** (the shape below).
2. **Hand over the public key** for provisioning on each cell.

Until both land, `POST /auth/exchange` answers `404` and the super app has no
path to any `CUSTOMER`-gated endpoint — marketplace orders, bookings, and
loyalty's authenticated rail all included.

---

## 2. The assertion

A **compact JWS**. Header and claims:

### Header

| Field | Value |
|---|---|
| `alg` | **`RS256`** (see the algorithm note below) |
| `typ` | `JWT` (conventional, not checked) |

### Claims

| Claim | Required | Value |
|---|---|---|
| `iss` | **yes** | Exactly the cell's `AUTH_FEDERATION_ISSUER`. Default: **`innbucks-middleware`** |
| `aud` | **yes** | Exactly the cell's `AUTH_FEDERATION_AUDIENCE`. Default: **`innbucks-foundry`** |
| `sub` | **yes** | The customer's phone number. **Send E.164 with a leading `+`** (`+263771234567`) |
| `jti` | **yes** | Unique per assertion. A UUID is fine. This is the replay key |
| `iat` | **yes** | Issue time, seconds since epoch |
| `exp` | **yes** | Expiry, seconds since epoch. **`exp - iat` must be ≤ 300 seconds** |

Nothing else is read. Any name, role, account id or tier you also stamp is
**ignored** — deliberately, so the middleware can never widen a customer's
authority by asserting it.

### Rules

- **`exp - iat` ≤ 300s**, enforced. Longer is rejected outright — without the
  ceiling, a single captured assertion would be a permanent login. 60–120s is a
  sensible choice; the app posts it immediately.
- **30 seconds of clock skew** is tolerated. Keep the signing host on NTP.
- **`jti` must be unique.** It is burned in Redis on first use for the
  assertion's remaining life plus 60s. A second exchange with the same `jti` is
  refused. Do not reuse, do not derive it deterministically from the phone.
- **Issue one assertion per login, per device.** The app exchanges it once and
  lives on the fleet refresh chain from there.
- `sub` in a local format is normalised against the cell's country, but **send
  E.164** — it is the only unambiguous form, and a number that normalises to a
  different country is refused `409 wrong_cell` on that cell.

### Algorithm note — RSA only, in practice

The verifier allow-lists `RS256/384/512` and `ES256/384/512`, but the key parser
currently builds an **RSA** `KeyFactory` only. **An EC public key fails at boot.**
Use **RSA, 2048-bit minimum**. If you need ECDSA, say so and the parser gets a
one-line change first — do not just send an EC key.

Symmetric algorithms and `alg: none` are rejected before any key comparison, so
alg-confusion is closed.

---

## 3. The key

- **Format: X.509 / `SubjectPublicKeyInfo` PEM** — i.e. `-----BEGIN PUBLIC KEY-----`,
  not `BEGIN RSA PUBLIC KEY` (PKCS#1).
- Generate:

  ```sh
  openssl genrsa -out federation-private.pem 2048
  openssl rsa -in federation-private.pem -pubout -out federation-public.pem
  ```

- **Send `federation-public.pem` only.** The private key never leaves the
  middleware. Do not email, paste or commit the private key anywhere.
- An unparseable key **fails Foundry's boot**, on purpose — the alternative
  presents as "every login is invalid", which reads like the middleware's fault
  and is not.

### Rotation

`AUTH_FEDERATION_PREVIOUS_PUBLIC_KEY` exists so a rotation has an overlap window
instead of a cliff. Order: provision the new key as the current one and the old
one as previous → roll Foundry → switch the middleware to sign with the new
private key → drop the previous key at the next convenient roll.

---

## 4. Provisioning (Foundry side, per cell)

Committed **off** in `deploy/cells/cell.zw.env`; enabled per host in the
gitignored `cell.zw.local.env` where the key actually lives.

```sh
AUTH_FEDERATION_ENABLED=true
AUTH_FEDERATION_PUBLIC_KEY=<the X.509 PEM, newlines \n-escaped or as a literal block>
AUTH_FEDERATION_PREVIOUS_PUBLIC_KEY=
AUTH_FEDERATION_ISSUER=innbucks-middleware
AUTH_FEDERATION_AUDIENCE=innbucks-foundry
AUTH_FEDERATION_MAX_TTL_SECONDS=300
```

Then roll user-service and **read the boot log** — it tells you which of the
three states you are in:

| Boot line | State |
|---|---|
| *(silence)* | Feature off. Every call `404` |
| `Federated login is HALF-PROVISIONED: ... AUTH_FEDERATION_PUBLIC_KEY is blank` | **ERROR.** On but no key. Every call `503` |
| `Federated login is enabled: assertions signed by issuer '…' for audience '…'` | Live. Check the issuer and audience printed match what you sign |

That last line is the cheapest possible confirmation that the two sides agree on
`iss`/`aud` — read it before testing.

---

## 5. Do not conflate this with the loyalty registration assertion

The fleet verifies **two** assertions of the same shape, signed by the same
party, and they are **not interchangeable**:

| | Federated login | Loyalty partner registration |
|---|---|---|
| Endpoint | `POST /auth/exchange` (user-service) | `POST /loyalty/partner/registrations` |
| Default `iss` | `innbucks-middleware` | `innbucks-app` |
| **Default `aud`** | **`innbucks-foundry`** | **`innbucks-loyalty`** |
| Config prefix | `AUTH_FEDERATION_*` | `LOYALTY_PARTNER_REGISTRATION_*` |

**The audiences differ on purpose:** a registration proof must never double as a
login. Both verifiers require `iss` *and* `aud`, so a token minted for one is
refused by the other. Sign the right audience for the right call.

---

## 6. Reference implementation (Java / jjwt)

```java
// exp - iat must be <= 300s. Keep it short; the app posts immediately.
private static final Duration ASSERTION_TTL = Duration.ofSeconds(120);

public String signLoginAssertion(String customerMsisdnE164) {
    Instant now = Instant.now();
    return Jwts.builder()
            .issuer("innbucks-middleware")          // must equal AUTH_FEDERATION_ISSUER
            .audience().add("innbucks-foundry").and() // must equal AUTH_FEDERATION_AUDIENCE
            .subject(customerMsisdnE164)             // "+263771234567"
            .id(UUID.randomUUID().toString())        // jti — unique, one use
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ASSERTION_TTL)))
            .signWith(federationPrivateKey, Jwts.SIG.RS256)
            .compact();
}
```

Return it to the app alongside your own login response — the app posts it to
`POST /auth/exchange` and never sees the private key.

---

## 7. End-to-end test before go-live

1. Generate a throwaway keypair (section 3) and provision the **public** half on
   staging with `AUTH_FEDERATION_ENABLED=true`.
2. Roll user-service; confirm the `Federated login is enabled: …` boot line and
   that the issuer/audience it prints match.
3. Sign a test assertion for a **real ZW mobile number that is not a staff
   account**, then:

   ```sh
   curl -sS -X POST https://<edge>/auth/exchange \
     -H 'Content-Type: application/json' \
     -H 'X-Device-Id: test-device-1' \
     -d '{"assertion":"<compact JWS>"}'
   ```

   Expect `200` with `roles: ["CUSTOMER"]` and both tokens.
4. **Post the identical assertion again.** Expect `401 "Assertion rejected"` —
   that is the replay guard working. If it succeeds twice, stop and escalate.
5. Sign one with `exp - iat = 600` → expect `401`.
6. Sign one with `aud: "innbucks-loyalty"` → expect `401`.
7. Call a `CUSTOMER`-gated endpoint with the access token, e.g.
   `GET /marketplace/cart`, and confirm `200` rather than `403`.
8. `POST /auth/refresh` with the refresh token in the `Authorization` header →
   expect a fresh pair.

Steps 4–6 are the ones worth insisting on: they prove the guarantees, not just
the happy path.

---

## 8. What this unblocks

- **Marketplace, whole buyer journey** — cart, addresses, checkout, orders,
  disputes, favorites, reviews, collection codes. All `hasRole('CUSTOMER')`.
- **Loyalty's authenticated rail** — the app currently falls back to the
  staging-only `/loyalty/public/**` surface, where any caller can name any
  number. A fleet `CUSTOMER` token binds every transfer and redemption to the
  caller's own phone, which is the posture that surface exists to replace.
- **Bookings and everything else** gated on `CUSTOMER`.

One integration, three surfaces.
