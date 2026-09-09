# Scan Reports — Frontend Integration Guide

**Change:** the Scan Reports surface (`/scans/**`) now updates as scans happen,
and returns timestamps already in the cell's local clock.

**You do not need to change anything for this to work.** Both fixes are
server-side. This guide explains what changed so you can (a) understand the new
values, and (b) optionally simplify.

Merged in **PR #559** (`booking-service`). Anchored to the merged code on
`master` (`6cf387c`).

---

## 1. Base URL, auth, headers

| | |
|---|---|
| **Base URL** | the API gateway, e.g. `https://<host>/foundry` |
| **Auth** | `Authorization: Bearer <JWT>` — required on every endpoint |
| **`X-Tenant-Id`** | **not used** by booking-service. Do not send it. |
| **Gateway route** | unchanged — `scans-route` (`Path=/scans/**`) already covered these. |

Roles:

| endpoint | roles |
|---|---|
| `/scans/me`, `/scans/me/stats` | `EVENT_ORGANIZER`, `TEAM_MEMBER` |
| `/scans/events/{id}`, `/scans/events/{id}/stats`, `/scans/team-stats` | `EVENT_ORGANIZER`, `SUPER_ADMIN`, `PRODUCT_OFFICER`, `PRODUCT_MANAGER` |

Per-event endpoints additionally verify the caller **owns** the event
(`organizerUuid` == the event's `tenantUserUuid`); `SUPER_ADMIN` bypasses that.

---

## 2. What changed

### 2.1 `to` is now optional — and this is the important one

```
GET /scans/events/{eventId}?from=...&to=...     # to is now OPTIONAL
```

**Omit `to` and the window runs up to the instant the request is handled.**

This is the recommended shape for any live view. It is a *deletion*, not new
parsing — if you never make the change, everything below still works.

**Why it matters.** The bound is applied as a closed `BETWEEN from AND to`. A
screen that computes "now" once when it mounts and re-sends that same value on
every refresh pins its window to page-load time, so a scan performed a minute
later is `> to` and never appears no matter how many times the operator
refreshes. That was the reported bug.

### 2.2 A supplied `to` is widened server-side

If you do send `to`, it is **rounded up to the end of its market-local day**.
So a `to` of `2026-09-09T07:44:00Z` (09:44 Harare) is queried as
`2026-09-09T21:59:59.999999999Z` — the end of 9 September, local.

Consequences for you:

- A frozen `to` now still shows **the rest of that day's** scans. The reported
  bug is fixed without any client change.
- **A historical range keeps its exact meaning.** "Up to the 3rd" still ends on
  the 3rd — it is *not* silently extended to now.
- The `from`/`to` echoed back on the stats responses are the bounds **actually
  queried**, so they may not byte-match what you sent. Render those if you want
  to show the effective window; don't assert equality with your request.

**One edge remains, and only omitting `to` avoids it:** a screen left open
since before midnight sends a `to` on *yesterday's* local day, so scans after
midnight fall outside it until the range is re-picked.

### 2.3 Timestamps arrive in the market's clock, not UTC

**Before:** `"attemptedAt": "2026-09-09T06:10:22Z"`
**Now:** `"attemptedAt": "2026-09-09T08:10:22+02:00"`

Same instant. The offset is explicit, so it is still unambiguous ISO-8601 and
`new Date(s)` parses it correctly — but the **wall clock in the leading
characters is the one the scanner was standing in**. A scan made at 08:10 in
Harare now reads `08:10`, printed verbatim.

This applies to `attemptedAt` on every scan row and to `from`/`to` on every
stats response.

> The offset comes from the **cell's market**, not the viewer's browser. A ZW
> cell serves `+02:00`, KE `+03:00`, NG `+01:00`. Don't hardcode `+02:00`.

### 2.4 Responses are `Cache-Control: no-store`

Previously these carried no cache header at all. If you added your own
cache-busting query param as a workaround, you can drop it.

---

## 3. Endpoints

All five take `from` (**required**) and `to` (**optional**). The two listing
endpoints also take `page` (default `0`) and `size` (default `20`, **max 100**).

| endpoint | returns |
|---|---|
| `GET /scans/me` | page of the caller's own scan attempts |
| `GET /scans/me/stats` | the caller's outcome breakdown |
| `GET /scans/events/{eventId}` | page of scan attempts for one event |
| `GET /scans/events/{eventId}/stats` | outcome breakdown for one event |
| `GET /scans/team-stats` | leaderboard, most active scanner first |

### Listing response

```json
{
  "code": "200 OK",
  "message": "Event scan attempts retrieved",
  "data": {
    "content": [
      {
        "id": "9b1f3c2e-6a47-4f7c-9d2b-1d6f0a1e5b91",
        "attemptedAt": "2026-09-09T08:10:22+02:00",
        "outcome": "ALLOWED",
        "ticketNumber": "20260901-08642P",
        "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
        "bookingId": "8d2c3e4a-1f5b-46a7-8c9d-0e1f2a3b4c5d",
        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "scannerEmail": "nyanyiwast@hotmail.com",
        "scannerDisplayName": "Tariro Chikomo",
        "scannerUserUuid": "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 412,
    "totalPages": 21
  }
}
```

Rows are **newest first**. `bookingItemId` / `bookingId` / `eventId` are `null`
when the ticket lookup failed (i.e. `TICKET_NOT_FOUND`).

### Stats response

```json
{
  "code": "200 OK",
  "message": "Event scan stats retrieved",
  "data": {
    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "from": "2026-09-02T09:44:00+02:00",
    "to": "2026-09-09T23:59:59.999999999+02:00",
    "total": 2,
    "byOutcome": {
      "ALLOWED": 1,
      "ALREADY_REDEEMED": 1,
      "WRONG_ORGANIZER": 0,
      "NOT_ASSIGNED_TO_EVENT": 0,
      "TICKET_NOT_FOUND": 0,
      "BOOKING_NOT_CONFIRMED": 0
    }
  }
}
```

**`byOutcome` is always zero-filled** — all six keys are present on every
response, so no conditional lookups are needed. `total` is their sum.

### The six outcomes

Note two of them differ from the labels currently on the screen:

| enum value | screen label |
|---|---|
| `ALLOWED` | Allowed |
| `ALREADY_REDEEMED` | Already Redeemed |
| `WRONG_ORGANIZER` | Wrong Organizer |
| `NOT_ASSIGNED_TO_EVENT` | **Not Assigned** |
| `TICKET_NOT_FOUND` | Ticket Not Found |
| `BOOKING_NOT_CONFIRMED` | **Not Confirmed** |

---

## 4. Error handling

All errors use the standard envelope: `{ "code", "message", "data": null }`.
These are read-only endpoints with no per-row errors — every failure is
top-level.

| status | `message` | when |
|---|---|---|
| `400` | `'from' is required.` | `from` missing |
| `400` | `'from' (X) must not be after 'to' (Y).` | inverted range |
| `400` | `'from' (X) must not be in the future.` | future `from` with no `to` |
| `400` | `'page' must be >= 0.` | negative page |
| `400` | `'size' must be between 1 and 100.` | size out of range |
| `400` | `... missing organizer identity` | legacy token with no organizer claim |
| `401` | `Invalid token` | missing / invalid JWT |
| `403` | `Forbidden - insufficient role` | wrong role, or not the owning organizer |
| `404` | event not found | unknown `eventId` |

---

## 5. Examples

### Live view — recommended

```js
// No `to` at all. The window always runs to right now, per request.
const qs = new URLSearchParams({
  from: sevenDaysAgo.toISOString(),
  page: '0',
  size: '20',
});
const res = await fetch(`${BASE}/scans/events/${eventId}?${qs}`, {
  headers: { Authorization: `Bearer ${token}` },
});
const { data } = await res.json();
```

### Rendering a timestamp

```js
// Printed verbatim, this is already the operator's local time:
row.attemptedAt.slice(11, 19);        // "08:10:22"

// Or parse it — the offset is explicit, so this is also correct:
new Date(row.attemptedAt).toLocaleString();
```

### Historical range

```js
// Send `to` when the operator genuinely picked an end date. It will be
// widened to the end of that local day, which is what "up to the 3rd" means.
const qs = new URLSearchParams({
  from: '2026-09-01T00:00:00Z',
  to:   '2026-09-03T00:00:00Z',
});
```

### curl

```sh
curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/scans/events/$EVENT_ID/stats?from=2026-09-02T07:44:00Z"
```

---

## 6. Gotchas checklist

- [ ] **Prefer omitting `to`** on live views. It's the only shape immune to both
      the frozen-window bug and the midnight edge.
- [ ] **Don't assert the echoed `from`/`to` equal what you sent** — they are the
      bounds actually queried, after widening.
- [ ] **Timestamps now end in `+02:00`, not `Z`.** If anything asserts on a
      trailing `Z`, or appends `'Z'` to these strings before parsing, it will
      now be wrong. Strip that — the offset is already there.
- [ ] **Don't hardcode `+02:00`.** It's the cell's market offset; KE is `+03:00`,
      NG `+01:00`.
- [ ] **Don't convert these timestamps yourself.** They're already local. Applying
      a second conversion double-shifts them.
- [ ] `byOutcome` is always fully populated — no need to guard for missing keys.
- [ ] `size` is capped at **100**; a larger value is a `400`, not a silent clamp.
- [ ] `NOT_ASSIGNED_TO_EVENT` and `BOOKING_NOT_CONFIRMED` are the enum names
      behind the "Not Assigned" / "Not Confirmed" tiles.
- [ ] Rows come back **newest first** — don't re-sort ascending by accident.
- [ ] **No `X-Tenant-Id`** on any booking-service call.
- [ ] Responses are `no-store`; drop any cache-busting param you added.

---

## 7. What did *not* change

- Request paths, roles, ownership rules, page/size semantics — all identical.
- `from` is still required and still an ISO-8601 instant.
- The scan endpoint itself (`POST /tickets/scan`) is untouched.
- No new gateway route, no migration, no new config or env var.
- CSV / XLSX exports built from this JSON inherit the local timestamps
  automatically — no export-side change needed.
