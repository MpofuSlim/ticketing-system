# Gate Staff Login (TEAM_MEMBER) — Frontend Integration Guide

What changes for the scanner app now that gate staff are no longer forced through a 2FA
challenge. Covers `POST /auth/login` only — no new endpoints, no request-shape changes.

Anchored to `user-service`'s `MfaPolicy`, `AuthService.login`, `AuthController` and
`AuthResponseDTO` as merged.

> **Corrections in this revision** (thanks to the FE team for catching the first):
> the MFA step-2 path is **`POST /auth/login/mfa`**, not `/auth/mfa/login` as the first draft
> said — that path does not exist and returns 404. The step-2 body also carries an optional
> `rememberDevice`, and `POST /auth/login` accepts an `X-Device-Trust-Token` header, both of
> which the first draft omitted.

---

## 1. What changed

A `TEAM_MEMBER` — event gate staff, the scanner operator role — used to be treated as a
"system user" by the MFA policy, which meant a **forced TOTP enrolment on first login** and a
TOTP challenge on every login after that. They are now on the same **opt-in** footing as a
`CUSTOMER`:

| Account | Before | After |
| --- | --- | --- |
| Role set exactly `{TEAM_MEMBER}`, not enrolled | `mfaEnrollmentRequired: true` + `mfaToken` | **normal token pair, straight away** |
| Role set exactly `{TEAM_MEMBER}`, enrolled themselves | `mfaRequired: true` + `mfaToken` | `mfaRequired: true` + `mfaToken` (unchanged) |
| `TEAM_MEMBER` **plus any other role** | challenge | challenge (unchanged) |
| Every other staff role | challenge | challenge (unchanged) |

Nothing else about login moves: same path, same request body, same headers, same token TTLs,
same refresh flow, same single-active-session behaviour.

---

## 2. The endpoint

`POST /auth/login`

### Request

Unchanged. Headers:

| Header | Required | Notes |
| --- | --- | --- |
| `Content-Type: application/json` | yes | |
| `X-Auth-Channel` | no | `WEB` (default), `MOBILE`, `USSD`, `WHATSAPP`. The scanner app should send `MOBILE`. |
| `X-Device-Id` | recommended | Device binding; also what the trusted-device flow keys on. |
| `X-Device-Trust-Token` | optional | A previously issued trusted-device token. Presented with a matching `X-Device-Id`, it skips the MFA challenge for a user who would otherwise be challenged. |

```json
{
  "identifier": "gate1@organizer.co.zw",
  "password": "the-temp-or-chosen-password"
}
```

### Response — gate staff, not enrolled (the new path)

`200 OK`

```json
{
  "code": "200 OK",
  "message": "Login successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "roles": ["TEAM_MEMBER"],
    "defaultServices": ["ticketing"],
    "email": "gate1@organizer.co.zw",
    "mfaRequired": false,
    "mustChangePassword": true
  }
}
```

`mfaEnrollmentRequired` and `mfaToken` are **absent** (they are `@JsonInclude(NON_NULL)`), not
`false`/`null`. Branch on presence, not on value.

### Response — gate staff who enrolled themselves

`200 OK`, and you must still complete step 2 exactly as before:

```json
{
  "code": "200 OK",
  "message": "MFA required",
  "data": {
    "mfaRequired": true,
    "mfaToken": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```

Then complete step 2 as today:

`POST /auth/login/mfa`

```json
{
  "mfaToken": "eyJhbGciOiJIUzI1NiJ9...",
  "code": "123456",
  "rememberDevice": false
}
```

Note the path is `/auth/login/mfa` — **not** `/auth/mfa/login`. The enrolment and disable
endpoints are `/auth/mfa/enroll/start`, `/auth/mfa/enroll/complete` and `/auth/mfa/disable`, so
the segment order differs between "complete a login" and "manage MFA". Easy to transpose.

`rememberDevice` is optional (defaults false). Set it with an `X-Device-Id` header to mint a
trusted-device token, which lets that device skip the challenge next time.

---

## 3. Client logic

The branch you already have is still the correct branch — do not special-case the role.

```js
const { data } = await login(identifier, password);

if (data.mfaEnrollmentRequired) {
  // Staff who must enrol. Gate staff no longer reach this.
  return goToEnrolment(data.mfaToken);
}
if (data.mfaRequired) {
  // Includes a gate staffer who opted in. Still reachable.
  return goToCodeEntry(data.mfaToken);
}
// Signed in.
return storeSession(data.token, data.refreshToken);
```

**Do not** gate this on `roles.includes("TEAM_MEMBER")`. The server decides; a client-side role
check would drift the moment a team member enrols or gains a second role.

### `mustChangePassword` — `true` on a new team member's first login

Unrelated to MFA and **enforced**. `TeamMemberService` now sets it at creation, so the first
login of a newly-created gate staffer returns `mustChangePassword: true`.

This is the one interstitial left on that first login, and it is deliberate: the organizer
receives the temporary password and relays it, so it is a shared secret from the moment it is
minted — and with no 2FA challenge it would otherwise be the account's only standing
credential.

`JwtFilter` blocks **every** non-`/auth/**` path while the claim is present, so a scanner cannot
redeem a ticket until it is rotated. The refusal is a `403`:

```json
{
  "code": "403 FORBIDDEN",
  "message": "Password change required before this account can use the rest of the app",
  "data": { "errorCode": "password_change_required" }
}
```

Note the code is **`data.errorCode`**, lowercase snake_case — not a top-level field. Route to the
change-password screen on that error as well as on the login flag, since the app may be holding a
token minted before the change. `AuthService` bumps `token_version` on a successful change, so
the claim-carrying JWT dies immediately and the next login is a clean session.

The steady-state login after that is: password in, token out, no interstitial.

---

## 4. Errors

Top-level shapes are unchanged. Messages below are the ones the service actually produces.

| Status | When | Body `message` |
| --- | --- | --- |
| `401` | Wrong identifier or password, or unknown identifier | `Invalid credentials` |
| `423` | Too many failed attempts | `Account temporarily locked due to too many failed login attempts`, plus `lockedUntil` in the body |
| `400` | Account not active / not approved | `We couldn't process your request. Please try again.` — see below |
| `400` | Malformed body / missing field | field-level validation message |

The inactive-account case is worth knowing about: `AuthService` throws a bare `RuntimeException`
("Account is not active. Please contact a SUPER_ADMIN for approval."), and
`GlobalExceptionHandler` collapses any bare `RuntimeException` into a **400** with the generic
`We couldn't process your request. Please try again.` So the specific reason never reaches the
client. Don't try to match on the specific text — it isn't sent. (Pre-existing behaviour, not
introduced here.)

No new error code is introduced by this change.

---

## 5. Gotchas checklist

- [ ] **Branch on field presence, not truthiness.** `mfaEnrollmentRequired` / `mfaToken` are
      omitted entirely when not applicable.
- [ ] **Keep the `mfaRequired` branch.** A gate staffer who voluntarily enables MFA still gets
      challenged. Removing the branch breaks exactly those users.
- [ ] **Don't infer the exemption client-side from `roles`.** It also depends on the account
      holding no permissions, which the token does not let you evaluate reliably.
- [ ] **Handle `mustChangePassword: true` on first login.** It is set at creation, and
      `JwtFilter` blocks every non-`/auth/**` path until the password is rotated. Every login
      after that is a plain password login.
- [ ] **A team member with a second role is challenged.** If an organizer's account also holds
      `TEAM_MEMBER`, that person sees the full 2FA flow — expected, not a bug.
- [ ] **Send `X-Device-Id`.** Unchanged advice, but it matters more now that it is the only
      device signal on a gate staffer's session.
- [ ] **Single active session still applies.** Each login bumps `tokenVersion` and revokes prior
      refresh families, so two scanners sharing one account will evict each other. Give each
      gate staffer their own account.

---

## 6. Getting a test account that actually exercises this

The exemption applies to an account whose role set is **exactly** `{TEAM_MEMBER}` **and** which
holds no permissions. An account that returns `mfaRequired: true` is therefore not testing this
path — it is either an organizer account, a multi-role account, or a team member who enrolled in
TOTP voluntarily.

You cannot tell which from the `mfaToken`: it deliberately carries only `kind` / `purpose` /
`sub` / `exp`, with no roles claim. That is intentional — an interim token that leaked would
otherwise describe the account it belongs to.

**To create a real gate-staff account**, call as a user holding the `EVENT_ORGANIZER` role (the
service checks the built-in role, so a custom role with `team-members:write` is refused):

`POST /event-organizer/team-members`

```json
{
  "firstName": "Tariro",
  "lastName": "Chikomo",
  "email": "gate1@your-org.co.zw",
  "phoneNumber": "0771234567"
}
```

`middleName` is optional. The email must be unclaimed and the phone number must be valid for the
cell's country (it is canonicalised to E.164). The response is `201` with the new account; the
**temporary password is not in the response** — it is delivered out of band to the new member by
email, then SMS, then WhatsApp.

That account will have role set exactly `{TEAM_MEMBER}`, no permissions, and `mfaEnabled: false`,
so its first login returns a token pair immediately with `mustChangePassword: true`.

**To check an existing account instead**, decode its *access* token after a completed login — the
access token carries `roles` and `perms`, unlike the interim `mfaToken`. Roles of exactly
`["TEAM_MEMBER"]` with an empty `perms` is the shape that qualifies.

---

## 6. Not changed by this work

- The scanning endpoints themselves (`POST /scans/...` in booking-service) — same auth, same
  `organizerUuid` scoping, same per-event assignment check.
- Token TTLs, `POST /auth/refresh`, `SESSION_SUPERSEDED`, logout.
- The trusted-device ("remember this device") flow.
- Every other role's MFA behaviour.
