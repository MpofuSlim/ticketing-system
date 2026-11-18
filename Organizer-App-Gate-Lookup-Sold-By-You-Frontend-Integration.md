# Gate Lookup: `SOLD_BY_YOU` — Organizer App Frontend Integration Guide

**Client app: the Ticketize Organizer app, gate scanner sign-in** (the session
from the SMS-code sign-in, `POST /auth/gate/code` then `POST /auth/gate/sign-in`).
Built by the Organizer app team. The Super app gets its own guide; nothing here
is for the Foundry portal.

**Change:** `POST /tickets/lookup` (find a booking by its confirmation number,
the no-QR fallback at the gate) has **one new `status` value, `SOLD_BY_YOU`**.
A gate scanner who is also a **sales agent** for the event gets it when they
look up a booking **they sold themselves**: the tickets are not listed to them.
Everything else about the endpoint is unchanged.

**Why:** a ticket number is the gate credential (it is what the QR holds, and
the first scan wins). A sales agent keeps the confirmation number of every sale
it makes, so the lookup used to hand the agent the whole ticket numbers of
everything it sold: enough to walk someone in on the buyer's ticket before the
buyer arrives. A seller never holds an entry credential, so the lookup now
refuses. It applies to the gate sign-in exactly as to the Super app: it is the
same account.

Anchored to `booking-service` on branch `feature/agent-gate-lookup-own-sales`
(`GateLookupService`, `GateLookupResponseDTO`, `TicketScanController`).

---

## 1. Base URL, auth, headers

| | |
|---|---|
| **Base URL** | the API gateway, e.g. `https://<host>/foundry` |
| **Auth** | `Authorization: Bearer <JWT>`: the gate session from `POST /auth/gate/sign-in` (role `GATE_SCANNER` only) |
| **`X-Tenant-Id`** | **not used** by booking-service. Do not send it. |
| **`Content-Type`** | `application/json` |
| **Gateway route** | unchanged (`booking-tickets-route`, `Path=/tickets/**`) |

A gate session reaches only `POST /tickets/scan`, `POST /tickets/lookup`,
`GET /scans/me` and `GET /scans/me/stats` in booking-service; anything else is
`403 gate_session_only`. The scanner's authority is not in the token:
booking-service asks event-service, on every call, whether this account is an
accepted, switched-on scanner assigned to the event. That is why `eventId` is
required.

---

## 2. The endpoint

```
POST /tickets/lookup
```

### Request

```json
{
  "confirmationNumber": "INN-20261118-CFAFE2",
  "eventId": "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0"
}
```

| field | required | notes |
|---|---|---|
| `confirmationNumber` | yes | As the customer reads it out or shows it from their SMS. Case and surrounding spaces are ignored. At most 64 characters. |
| `eventId` | **yes, for a gate session** | The event the scanner is working at. |

### Every verdict is HTTP 200 with a `status`

| `status` | what it means | tickets listed? |
|---|---|---|
| `FOUND` | Paid booking for this event. | yes |
| **`SOLD_BY_YOU`** (new) | **You sold this booking as a sales agent.** | **no** |
| `BOOKING_NOT_FOUND` | No booking has that number. | no |
| `BOOKING_NOT_CONFIRMED` | The booking exists but is not paid (or was voided or cancelled). | no |
| `NOT_ASSIGNED_TO_EVENT` | This number is not (or no longer) a scanner for this event. Nothing was read. | no |
| `WRONG_EVENT` | The booking is for another event. | no |

`WRONG_ORGANIZER` exists in the enum but is only returned on the organizer's
own path, never to a gate session.

**Anything but `FOUND` carries only `status` and the echoed
`confirmationNumber`.** No name, no phone, no tickets.

### `SOLD_BY_YOU` (new)

```json
{
  "code": "200 OK",
  "message": "Lookup result",
  "data": {
    "status": "SOLD_BY_YOU",
    "confirmationNumber": "INN-20261118-CFAFE2"
  }
}
```

When it is returned:

- Only when **the signed-in account sold this booking as a sales agent**
  (`POST /bookings/box-office`, cash or online, from the InnBucks app or the
  agent's own Foundry sign-in). The gate sign-in carries the same account, so
  a sale made in the Super app is "yours" here too.
- Only when the booking is paid. An online sale still awaiting payment answers
  `BOOKING_NOT_CONFIRMED`, as it does for anyone.
- **Other scanners on the same team still get `FOUND`** for the same booking,
  and so does the organizer.

### `FOUND` (unchanged)

```json
{
  "code": "200 OK",
  "message": "Lookup result",
  "data": {
    "status": "FOUND",
    "confirmationNumber": "INN-20261118-CFAFE2",
    "customerName": "Rufaro Moyo",
    "customerPhoneLast4": "****4567",
    "tickets": [
      {
        "ticketNumber": "20261118-1EAVX-1YPDM",
        "bookingItemId": "053ea7ac-7da1-458e-bce8-81839cc4cb5b",
        "categoryName": "VIP",
        "holderName": "Rufaro Moyo",
        "redeemed": false
      },
      {
        "ticketNumber": "20261118-X63VB-5AZ1B",
        "bookingItemId": "9287b6bd-d1ac-4ddc-bd6a-00ddd590e69d",
        "categoryName": "VIP",
        "holderName": "Tendai Ncube",
        "redeemed": true,
        "redeemedAt": "2026-11-18T19:42:11+02:00",
        "redeemedByName": "Rudo Sibanda"
      }
    ]
  }
}
```

To admit a holder, send that ticket's `ticketNumber` to `POST /tickets/scan`
with the same `eventId`, exactly as for a QR scan. A ticket may also carry
`assignedNumber` / `numberLabel` (e.g. `"T014"`, `"Bib"`) when the organizer
numbers tickets; both are absent otherwise.

---

## 3. What the screen should do on `SOLD_BY_YOU`

Show a refusal, not an error, and say what to do next. Suggested copy:

> **You sold this booking**
> To keep tickets safe, a seller can't open their own sales at the gate. Ask
> another scanner or the organizer to look it up. If the guest has their QR
> code, you can still scan it.

- **No retry button.** It is a decision about this booking, not an outage:
  retrying returns the same answer.
- **No admit button, no ticket list.** There is nothing to admit from here.
- **Scanning the guest's QR still works.** `POST /tickets/scan` is unchanged:
  a scanner who sold a ticket can still admit it when the holder shows its QR.
  Only the lookup refuses.

---

## 4. Errors (not 200)

| HTTP | body | when | what to do |
|---|---|---|---|
| 400 | `{"code":"400 BAD_REQUEST","message":"Validation failed","data":{"confirmationNumber":"confirmationNumber is required"}}` | `confirmationNumber` missing, blank, or over 64 characters (`"confirmationNumber is too long"`) | Fix the input; field errors are keyed by field name in `data`. |
| 400 | `{"code":"400 BAD_REQUEST","message":"eventId is required","data":null}` | No `eventId` sent | Always send the event the scanner is working at. |
| 401 | `{"code":"INVALID_TOKEN","message":"Token is invalid or expired","data":null}` | Expired or invalid session | Refresh the session (`/auth/refresh`), then retry once. |
| 401 | `{"code":"TOKEN_REVOKED","message":"Token has been revoked","data":null}` | Signed out elsewhere | Sign in again with a new SMS code. |
| 401 | `{"code":"401 UNAUTHORIZED","message":"Invalid token","data":null}` | No `Authorization` header | Send the session. |
| 503 | `{"code":"503 SERVICE_UNAVAILABLE","message":"Could not confirm you can scan for this event. Please try again.","data":null}` | The scanner's access could not be checked | Show "Try again" (amber, not a rejection). Nothing was read. |

---

## 5. Gotchas checklist

- [ ] Handle `SOLD_BY_YOU` as a **refusal with guidance** (section 3), never as
      a generic error and never as `FOUND`.
- [ ] **Treat any `status` you do not recognise as a refusal**: show a generic
      "This booking can't be opened here" and list nothing. Do not crash on an
      unknown value, and never assume a missing `tickets` means "no tickets".
- [ ] **Older app builds** receive `SOLD_BY_YOU` too. Whatever they show, they
      list no tickets: the response carries none.
- [ ] Do not retry `SOLD_BY_YOU`; do retry a `503` (once, after a pause).
- [ ] `eventId` is required on every lookup and scan from a gate session.
- [ ] No `X-Tenant-Id` on any booking-service call.
- [ ] The scan (`POST /tickets/scan`) is unchanged: no new status there.
