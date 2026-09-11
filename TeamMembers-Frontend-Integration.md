# Team Members (Gate Staff) — Frontend Integration Guide

The organizer-facing surface for managing gate staff: create, list, disable, re-enable, re-issue
credentials, and assign to events. Ten endpoints under `/event-organizer/team-members`.

Companion to `GateStaff-Login-Frontend-Integration.md` (how those accounts sign in) and
`ScanDay-Frontend-Integration.md` (when their scans are accepted).

Anchored to `user-service`'s `TeamMemberController`, `TeamMemberService` and
`InternalTeamMemberController` as merged.

---

## 0. Read this before building the screen

**A team member with no event assignments can scan NOTHING.** Access is deny-by-default: the
scan check is a bare "is there an assignment row for this member and this event", so a freshly
created account is inert until you assign it to at least one event.

This is the single most likely way to ship a broken flow. A "create team member" screen that
ends at creation produces accounts that log in perfectly and are refused at every gate — which
reads as a scanning bug, not a missing setup step. **Treat assignment as part of creation**, or
show an unmissable "not assigned to any event yet" state on the member's row.

> Two stale comments in the backend say the opposite — that no assignments means organizer-wide
> access. They are wrong; `canScanEvent` is `existsByTeamMemberUserUuidAndEventId`, and the
> internal `assigned-events` endpoint documents "Empty list = the team member has no scan access
> (deny-by-default)". Trust the behaviour described here.

---

## 1. Auth and permissions

All endpoints are under `/event-organizer/team-members` and need a bearer token. Permissions,
not roles, gate each one:

| Permission | Endpoints |
|---|---|
| `team-members:read` | list, get one, list assigned events |
| `team-members:write` | create |
| `team-members:manage` | disable, enable, reset password, assign/unassign events |

`EVENT_ORGANIZER` holds all three. Note a subtlety: **creation additionally requires the built-in
`EVENT_ORGANIZER` role at the service layer**, so a custom role granted `team-members:write`
passes the permission check and is then refused with
`403 "Only EVENT_ORGANIZER may manage team members"`. If you build a role-management UI, do not
present `team-members:write` as sufficient on its own.

---

## 2. Create

`POST /event-organizer/team-members` → `201`

```json
{
  "firstName": "Tariro",
  "middleName": "T",
  "lastName": "Chikomo",
  "email": "gate1@your-org.co.zw",
  "phoneNumber": "0771234567"
}
```

`middleName` is optional; everything else is required. The phone number is validated and
canonicalised to E.164 against the cell's country, so local format is fine on input but the
stored/returned value will be `+263...`.

Response is the created member (`UserResponseDTO`): `userUuid`, name fields, `email`,
`phoneNumber`, `roles: ["TEAM_MEMBER"]`, `active: true`, `createdByOrganizerUuid`.

**The temporary password is NOT in the response.** It is delivered out of band to the new member
— email first, then SMS, then WhatsApp, best-effort and asynchronous. Do not build a UI that
expects to display or copy it. If it does not arrive, use the reset-password endpoint (§6)
rather than recreating the account.

The new account has `mustChangePassword: true`, so their first login routes to the
change-password screen before they can scan. See the gate-staff login guide.

### Errors

| Status | `message` | When |
|---|---|---|
| `400` | `Email already registered` | address already in use |
| `400` | `Phone number already registered` | number already in use in this cell |
| `400` | `Invalid phone number: <input>` | unparseable for the cell's country |
| `400` | field-level message | missing/blank required field |
| `403` | `Only EVENT_ORGANIZER may manage team members` | caller holds the permission but not the role |

---

## 3. List and read

`GET /event-organizer/team-members` → your own team.

`GET /event-organizer/team-members/{teamMemberUuid}` → one member.

A `SUPER_ADMIN` calling the list gets **every** team member across all organizers, not just one
organizer's — if your console is used by platform staff, the list is not implicitly scoped and
you should show the owning organizer.

---

## 4. Disable (soft delete)

`DELETE /event-organizer/team-members/{teamMemberUuid}` → `200` with the updated member.

Despite the verb, **nothing is deleted**. The row is kept so the audit trail on already-scanned
tickets (`redeemed_by_user_uuid` / `redeemed_by_name`) never orphans. What happens:

- `active` → `false`, so they can no longer log in.
- `tokenVersion` is bumped, so **every access token they currently hold is rejected on its next
  call** — the lockout is immediate, not "when the token expires".
- Refresh-token families are revoked, so they cannot refresh back into a session.

Idempotent: disabling an already-disabled member returns current state and changes nothing.

Label this "Disable", not "Delete" — the member reappears in the list as inactive, and a UI that
promised deletion will look broken.

## 5. Re-enable

`PATCH /event-organizer/team-members/{teamMemberUuid}/enable` → `200`

Restores `active: true`. They log in with their existing password — re-enabling does **not**
issue new credentials. If the password is also lost, follow with §6.

---

## 6. Re-issue a temporary password

`POST /event-organizer/team-members/{teamMemberUuid}/reset-password` → `200`

Mints a fresh 10-character temporary password and delivers it out of band on the same
email → SMS → WhatsApp chain. As with creation, **the password is not in the response**.

Use this when credentials were never received or are lost. It is the supported recovery path —
do not delete and recreate the account, which orphans nothing but does lose the member's event
assignments and their scan history association.

---

## 7. Event assignments — the part that actually grants access

`GET /event-organizer/team-members/{teamMemberUuid}/events` → the member's assigned event ids.

`PUT /event-organizer/team-members/{teamMemberUuid}/events/{eventId}` → assign. Idempotent.

`DELETE /event-organizer/team-members/{teamMemberUuid}/events/{eventId}` → unassign. Idempotent.

Both write operations return the member's **full current assignment set**, so you can refresh the
UI from the response without a second round trip.

Behaviour that matters for the UI:

- **No assignments = no access at all.** Not "all events" — nothing. See §0.
- Removing the **last** assignment silently returns the member to zero access. If your UI has an
  "unassign" control, warn on the last one; it looks identical to removing one of several but
  has a completely different effect.
- Assignment also scopes what they *see*: event-service narrows a team member's event list to
  their assigned events.

### Suggested flow

1. Create the member (§2).
2. Immediately prompt to assign at least one event.
3. On the team list, badge any member with zero assignments as **"No events assigned — cannot
   scan"**.

---

## 8. What a team member can do once set up

Deliberately narrow. They hold **no** user-service permissions at all, so every admin endpoint is
closed to them. Their remit is:

- Scan tickets for assigned events (booking-service), subject to the event-day rule.
- Read their own scan history and stats.
- See their assigned events.
- Their own `/auth/**` self-service (change password, MFA enrolment if they choose).

They cannot read other users, manage anyone, or see other organizers' data.

---

## 9. Gotchas checklist

- [ ] **Assignment is required for any access.** The single biggest trap — see §0.
- [ ] **Warn when unassigning the last event.** Same control, very different outcome.
- [ ] **Never expect a password in a response.** Creation and reset both deliver out of band.
- [ ] **"Delete" disables.** The member stays in the list as inactive, by design.
- [ ] **Disable is immediate**, not TTL-bound — do not tell the operator "within the hour".
- [ ] **`team-members:write` alone is not enough to create.** The built-in `EVENT_ORGANIZER`
      role is also required, and the refusal is a 403 that mentions the role, not the permission.
- [ ] **Phone numbers come back canonicalised** to `+263...` even if entered as `077...`.
      Compare canonically, not by string equality with what was typed.
- [ ] **A `SUPER_ADMIN`'s list is platform-wide**, not scoped to one organizer.
