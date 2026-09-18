# Scan Day Rule — Frontend Integration Guide

What changes for the scanner app now that a ticket only redeems on its event's own days.
Covers `POST /tickets/scan`. One new verdict, one new field, and **one change to the HTTP
contract** that the app must handle before this goes near a live gate.

Anchored to `booking-service`'s `TicketScanService`, `EventDayRule`, `ScanTicketResponseDTO` and
`TicketScanController` as merged.

---

## 1. The rule

A ticket redeems only on the **market-local calendar days its event spans** — the day containing
`startDateTime` through the day containing `endDateTime`, inclusive.

- A single-evening show: that one local day.
- A club night running 22:00 → 01:00: **both** local days. Nobody is refused mid-event.
- A three-day festival: all three days, middle day included.
- Scanning is allowed from **00:00 local** on the event's day. There is no doors-open window —
  "the day of the event" is the whole day.

The zone is the cell's market (`Africa/Harare` for ZW, +2), never UTC. This matters at the edges:
a 00:30-local scan the morning after a late show is `22:30Z` on the *previous* date, and the rule
correctly calls that the **next** day, not the event's day.

---

## 2. New verdict: `WRONG_EVENT_DAY`

`200 OK`, verdict in the body as usual:

```json
{
  "code": "200 OK",
  "message": "Scan result",
  "data": {
    "status": "WRONG_EVENT_DAY",
    "ticketNumber": "20260619-48291X",
    "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
    "eventDate": "2026-06-19"
  }
}
```

**The ticket is NOT redeemed.** It stays valid for its own day — this is a refusal, not a
consumption. Do not present it as "ticket used".

`eventDate` is the event's **first market-local day**, so staff can say "come back on the 19th"
instead of an unactionable "wrong day". It appears **only** on this verdict (the DTO is
`@JsonInclude(NON_NULL)`), so branch on its presence, not on a default.

Suggested copy: *"Not valid today — this ticket is for 19 June."*

---

## 3. The contract change — read this one

The endpoint used to promise *"always returns 200; the status field carries the verdict"*. That
is now amended:

> **200 carries verdicts about the ticket. 503 means the server could not decide.**

If `booking-service` cannot confirm the event's dates — `event-service` unreachable, circuit
open — the scan is refused **without redeeming** and returns:

```json
{
  "code": "503 SERVICE_UNAVAILABLE",
  "message": "Could not confirm the event's date. Please try again.",
  "data": null
}
```

### What the app must do

Show an **amber, retryable** state — *"Couldn't check, tap to retry"* — and let staff scan again.

**Do not render this as a rejection.** An outage is not a verdict about the ticket: the customer
in front of the gate may be holding a perfectly valid one. A scanner build that treats any
non-200 as "invalid ticket" will turn away real customers for the duration of an incident, which
is the single worst failure mode of this change.

```js
const res = await scan(ticketNumber);

if (res.status === 503) {
  return showRetry("Couldn't check the event date. Try again.");
}

switch (res.data.status) {
  case "ALLOWED":               return showAllowed(res.data);
  case "ALREADY_REDEEMED":      return showAlreadyUsed(res.data);
  case "WRONG_EVENT_DAY":       return showWrongDay(res.data.eventDate);
  case "WRONG_ORGANIZER":
  case "NOT_ASSIGNED_TO_EVENT":
  case "TICKET_NOT_FOUND":
  case "BOOKING_NOT_CONFIRMED": return showRefused(res.data.status);
}
```

The 400 (blank `ticketNumber`), 401 and 403 responses are unchanged.

---

## 4. Why it fails closed

When the date can't be verified, the server refuses rather than allowing. The asymmetry is the
reason: a refused scan is recoverable by retrying seconds later, whereas an allowed wrong-day
scan sets `redeemed_at` and there is **no inverse** — it permanently burns a ticket that was
valid for its own day.

Two operator levers exist if an outage outlasts a gate's patience, both server-side:

| Property | Default | Effect |
|---|---|---|
| `innbucks.scan.event-day-check.enabled` | `true` | `false` disables the day rule entirely |
| `innbucks.scan.event-day-check.fail-open` | `false` | `true` keeps the rule but allows unverifiable scans |

Neither is client-visible. The app should not try to detect or work around them.

---

## 5. Gotchas checklist

- [ ] **503 is retryable, not a rejection.** The most important item on this list.
- [ ] **`WRONG_EVENT_DAY` does not consume the ticket.** Wording like "already used" is wrong.
- [ ] **Branch on `eventDate` presence.** It is absent on every other verdict, not null.
- [ ] **`eventDate` is a plain calendar date** (`YYYY-MM-DD`), already in the market's clock.
      Do **not** parse it as a timestamp and re-zone it — that is how you land on the wrong day.
- [ ] **Don't compute the rule client-side.** The app has no reliable view of the event's stored
      window or the cell's market zone; the server is the only correct place to decide.
- [ ] **A scan during a past-midnight event is still valid.** If staff report tickets refused at
      01:00 for an event that is visibly still running, that is a bug — report it rather than
      working around it.
- [ ] **Results may be up to 60s stale after an organizer edits an event's date**, by design
      (per-event cache). Not a client concern, but it explains a brief disagreement after an edit.

---

## 6. Not changed by this work

- Every other scan verdict, its meaning and its payload.
- Authorization: `WRONG_ORGANIZER` and `NOT_ASSIGNED_TO_EVENT` still run **before** the day
  check, so an unauthorised scanner learns nothing about an event's schedule.
- The request shape, required headers, and the `EVENT_ORGANIZER` / `TEAM_MEMBER` role gate.
- Gate-staff login (see `GateStaff-Login-Frontend-Integration.md`).
