# Event-Organizer Team-Member Flow — FE Hand-off

**Status:** Stable. Onboarding + per-event assignment + gate scanning are all live on master.
**Audience:** FE engineers wiring the organizer dashboard and the gate-scanner UI.

This is the customer-facing flow for **EVENT_ORGANIZER** users: how they sign in, onboard their gate-staff, optionally restrict each scanner to specific events, and what the scanner actually does at the gate. Every request below goes through the api-gateway; every response uses the standard envelope `{ code, message, data }` — read `response.data`.

## Roles in this flow

| Role | Who | What they do |
|---|---|---|
| `EVENT_ORGANIZER` | Manages an organization that runs events. | Creates and manages their team. May also scan tickets directly (small events). |
| `TEAM_MEMBER` | Gate-staff / scanner operator working under an organizer. | Logs in on the scanner device and redeems tickets at the gate. |

Both roles' JWTs carry the same `organizerUuid` claim — the organizer's `user_uuid`. booking-service authorizes scans against that claim, so a team member can scan any event owned by their organizer **unless** the organizer has narrowed them down via per-event assignments (see below).

## End-to-end sequence

```
ORGANIZER                                 SCANNER (TEAM_MEMBER)
─────────                                 ────────────────────

1. POST /auth/login  ──────────────►  organizer JWT (has organizerUuid = self)
2. POST /event-organizer/team-members  (creates Tariro Chikomo)
       └─ user-service emails/SMSes Tariro a one-time temp password.
3. (optional) PUT /event-organizer/team-members/{uuid}/events/{eventId}
       └─ scopes Tariro to specific events. Without any assignment Tariro
          can scan ANY event owned by the organizer (default org-wide).

                                       4. POST /auth/login  ──► scanner JWT
                                                              (has organizerUuid
                                                               = same as organizer,
                                                               firstName/lastName claims)
                                       5. POST /auth/change-password
                                          (first login forces a rotate;
                                           clearable mustChangePassword flag)
                                       6. POST /tickets/scan { ticketNumber }
                                          → OK / ALREADY_REDEEMED / NOT_FOUND / FORBIDDEN
```

## Endpoint reference

### 1. `POST /event-organizer/team-members` — onboard a scanner

**Auth:** EVENT_ORGANIZER JWT. **Body:**
```json
{
  "firstName": "Tariro",
  "middleName": "K",
  "lastName":  "Chikomo",
  "email":     "tariro@harare-arena.co.zw",
  "phoneNumber": "+263773456789"
}
```
- `firstName`, `lastName`, `email`, `phoneNumber` are required. `middleName` is optional.
- **No `organizerUuid` field** — the relation is derived from the caller's JWT.

**Response `201`** (`data`):
```json
{
  "id": 91,
  "userUuid": "5fc4c9d2-…",
  "firstName": "Tariro", "middleName": "K", "lastName": "Chikomo",
  "email": "tariro@harare-arena.co.zw",
  "phoneNumber": "+263773456789",
  "roles": ["TEAM_MEMBER"],
  "defaultServices": ["ticketing"],
  "active": true,
  "createdAt": "…",
  "createdByOrganizerUuid": "8b3a9c0e-…"
}
```
user-service auto-mints a one-time temp password and delivers it to Tariro's email/SMS. **No FE work needed for the temp password** — it never appears in the response.

### 2. `GET /event-organizer/team-members` — list the team

**Auth:** EVENT_ORGANIZER. Returns every team member the organizer ever created — active **and** disabled. Tell them apart by `active`.
Use this for the "Your team" table in the dashboard.

### 3. `GET /event-organizer/team-members/{teamMemberUuid}` — one member

Same shape as `[1]`, single row. Use it for the team-member detail page.

### 4. `DELETE /event-organizer/team-members/{teamMemberUuid}` — disable

**Soft delete** — the row stays so audit references (`booking_items.redeemed_by_user_uuid`, `redeemed_by_name`) keep resolving. `active` flips to `false`, the refresh-token family is revoked, all existing sessions are immediately invalidated. The next login attempt fails until you re-enable.

### 5. `PATCH /event-organizer/team-members/{teamMemberUuid}/enable` — re-activate

Sets `active=true`. The team member can log in again. Any prior per-event assignments are preserved.

### 6. `GET /event-organizer/team-members/{teamMemberUuid}/events` — list assignments

Returns the events this team member is *explicitly* assigned to. An **empty list** means the default org-wide scope is in effect — Tariro can scan **any** event the organizer owns. Only adding an assignment row narrows the scope; from that point Tariro can only scan events in the explicit list.

### 7. `PUT /event-organizer/team-members/{teamMemberUuid}/events/{eventId}` — assign

Idempotent. Adds an event to the team member's allow-list, switching them from default org-wide scope to event-scoped. Tariro now only sees scans land for the events you explicitly assigned.

### 8. `DELETE /event-organizer/team-members/{teamMemberUuid}/events/{eventId}` — unassign

Removes one event. **If the last assignment is removed**, the team member reverts to the default org-wide scope (NOT to zero events). If you want a true zero-event lockout, disable them instead via `[4]`.

### 9. `POST /tickets/scan` — redeem a ticket at the gate

**Auth:** EVENT_ORGANIZER **or** TEAM_MEMBER JWT. **Body:**
```json
{ "ticketNumber": "20260502-12345A" }
```
**Response `200`** (`data`) — possible statuses (verbatim enum names):
```json
{ "status": "ALLOWED",                "ticketNumber": "…", "bookingItemId": "…", "redeemedAt": "…", "redeemedByName": "Tariro Chikomo" }
{ "status": "ALREADY_REDEEMED",       "ticketNumber": "…", "bookingItemId": "…", "redeemedAt": "…", "redeemedByName": "Tariro Chikomo" }
{ "status": "TICKET_NOT_FOUND",       "ticketNumber": "…" }
{ "status": "BOOKING_NOT_CONFIRMED",  "ticketNumber": "…" }
{ "status": "WRONG_ORGANIZER",        "ticketNumber": "…" }
{ "status": "NOT_ASSIGNED_TO_EVENT",  "ticketNumber": "…" }
```
- `ALLOWED` — first valid scan. Open the gate.
- `ALREADY_REDEEMED` — a second scan of the same ticket. Show the rejection toast: *"already scanned by `{redeemedByName}` at `{redeemedAt}`"*. The `redeemedByName` is captured at the **first** scan and frozen — even if the scanner is later renamed or disabled, the toast still shows the name they were known by.
- `TICKET_NOT_FOUND` — ticket number doesn't match any booking item. Likely a fake or misread QR.
- `BOOKING_NOT_CONFIRMED` — the booking exists but isn't paid yet (PENDING or CANCELLED). Show "this ticket isn't valid for entry."
- `WRONG_ORGANIZER` — the caller's `organizerUuid` doesn't own the event the ticket belongs to.
- `NOT_ASSIGNED_TO_EVENT` — the team member has explicit per-event assignments and this event isn't one of them. Show "this scanner isn't assigned to this event."

## JWT claims the FE should care about

After login, the FE parses the access token. Claims relevant to this flow:

| Claim | Present on | Use |
|---|---|---|
| `roles` | every token | Switch dashboard vs scanner UI. |
| `userUuid` | every token | Stable identifier for the signed-in user. |
| `organizerUuid` | EVENT_ORGANIZER, TEAM_MEMBER | Display "Acting on behalf of …" when needed; identical for an organizer and their team. |
| `firstName` / `lastName` | CUSTOMER, **TEAM_MEMBER**, **EVENT_ORGANIZER** | Show the human name in the header / scanner status bar. The same claims are why the rejection toast now shows "Tariro Chikomo" instead of `tariro@harare-arena.co.zw`. |
| `tier` | CUSTOMER mostly; staff = 0 | Don't gate organizer/scanner features on tier — these roles aren't tier-bound. |

## Edge cases worth handling in the FE

- **`mustChangePassword`** is set in the login response on a team member's very first sign-in (and after any organizer re-issues their password). Route them straight to the password-change screen; don't surface the scanner until they rotate.
- **`401 INVALID_TOKEN`** during a scan session usually means the organizer just disabled the team member. Force a re-login screen — the refresh-token family was revoked and a refresh will also 401.
- **`409 CONFLICT` with `errorCode: wrong_cell`** can appear on any request if the user is signed in against the wrong country cell. Follow the multi-cell handling already in place (use `data.homeCountry` to look up the correct base URL via `/cells/lookup` and retry).
- **`FORBIDDEN` on scan** when the team member previously could scan: the organizer added per-event assignments and this event isn't in them. Surface "this scanner isn't assigned to this event" — not a generic 403.

## Out of scope (handled by other docs / services)

- The customer-side ticket QR rendering is in the booking/payment flow doc.
- Cell-level routing (`homeCountry` claim, wrong-cell 409 handling) is covered by the multi-cell rollout doc.
- Organizer self-onboarding (creating the organizer account itself) is a separate, admin-driven flow — not yet self-service.
