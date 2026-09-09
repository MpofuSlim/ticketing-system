# Foundry Console — Backend Fixes, Frontend Integration

**One document for the whole batch.** Everything you asked for in
`Foundry-Console-Backend-Requests-Response.md` that has now been built, with the
API changes you need to act on.

**Last updated:** 2026-09-09

| § | Your request | Services | Status |
|---|---|---|---|
| §4.2 | Block deleting a seat category with bookings | `seat-service` | ✅ **Live on master** (#563) |
| §4.2 | Block repricing a category with bookings | — | ❌ **Not built — see §2.4, your premise was wrong** |
| §5.1 | `/events/by-organizer` 403s for product staff | `event-service` | ✅ **Live on master** (#565) |
| §5.5 | Reports don't say what period they cover | `booking-service` | ✅ **Live on master** (#565) |
| §4.1 | Oversell guard (your #1 risk) | `seat-service`, `event-service` | 🕐 **Built, in review** (#567) — §5 |
| §1 | Notifications / the bell | `user-service`, `api-gateway` | 🕐 **Built, in review** (#566) — §5 |

> **§4.1 and §1 are NOT live yet.** The response doc you have says §4.1 is
> unguarded and tells you to keep your client-side checks — that is still true
> today and stops being true when #567 merges. **Keep those checks until I tell
> you this document has a §4.1 section in it.** I will extend *this same file*
> rather than sending you a new one.

---

## 1. Base URL, auth, headers

Common to everything below:

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Auth | `Authorization: Bearer <jwt>` |
| `X-Tenant-Id` | **Not required** on any endpoint in this document. These are platform/event surfaces, not tenant-scoped. |
| Envelope | the standard `ApiResult`: `{ "code", "message", "data" }` |

**No gateway changes were needed for anything in §2–§4** — `/seat-categories/**`,
`/events/**` and `/event-organizer/**` were already routed. No request shape
changed anywhere. Every change below is either a **new failure response** or an
**additive response field**.

---

## 2. §4.2 — Deleting a seat category with active bookings

**Service:** `seat-service` · **Merged:** #563 (`217d48d`) · **Migration:** none

### 2.1 Why

Deleting a seat category was an unguarded soft-delete. Nothing consulted
booking-service, so a category with live tickets could be removed and those
tickets would point at a category that no longer existed. Your client-side check
was the only thing standing between an operator and a broken event.

The check now lives in the backend, where it cannot be bypassed by a direct API
call, a stale tab, or a second operator racing the first.

### 2.2 `DELETE /seat-categories/{id}`

Success is unchanged:

```json
{ "code": "200 OK", "message": "Seat category deleted successfully", "data": null }
```

**New — `409 CONFLICT`, the category has active bookings:**

```json
{
  "code": "409 CONFLICT",
  "message": "'VIP Front Row' has 12 active bookings and cannot be deleted. Cancel or refund them first.",
  "data": null
}
```

The message is built server-side, **names the category and the exact count**, and
gets singular/plural right (`1 active booking` / `12 active bookings`). Show it
verbatim — you don't need to compose copy or re-fetch a count to fill a template.

"Active" is whatever booking-service counts as live for that category. A category
whose bookings have all been cancelled or refunded deletes normally.

**New — `503 SERVICE_UNAVAILABLE`, we could not check:**

```json
{
  "code": "503 SERVICE_UNAVAILABLE",
  "message": "Cannot verify whether this category has bookings right now. Please try again shortly.",
  "data": null
}
```

### 2.3 The 503 is the one most likely to be mishandled

It means booking-service did not answer, so the guard **refused rather than
guessing**. Nothing was deleted; the category is exactly as it was.

> **The guard is fail-closed, on purpose.** Several of our service-to-service
> clients deliberately degrade to an empty result on failure, because their
> callers are read paths that must survive an outage. A *delete guard* must not
> inherit that: an empty result would read as "no bookings" and permit the very
> deletion the guard exists to prevent.

**Client behaviour:** treat 503 as retryable. Show the message, keep the dialog
open, offer "Try again". Do **not** mark the row deleted optimistically, and do
**not** fall back to your own client-side check to decide it's safe.

| Status | Meaning | Retry? | Deleted? |
|---|---|---|---|
| `200` | Deleted | — | Yes |
| `404` | No such category, or already deleted | No | — |
| `409` | Has active bookings | No — operator must cancel/refund first | No |
| `503` | booking-service unreachable | **Yes** | No |

### 2.4 Repricing is still allowed — and please remove your block

§4.2 also asked us to **block editing a category's price when it has bookings**,
on the stated grounds that repricing would invalidate tickets already paid for.

**We did not build that, because the premise does not hold.**
`BookingItem.priceAtBooking` is frozen at purchase time. A booking records the
price the customer actually paid; nothing reads the category's *current* price at
render, refund, or reconciliation. Changing `SeatCategory.price` therefore affects
**future** bookings only and leaves every existing ticket, receipt and revenue
figure exactly as it was.

Blocking the edit would have removed a legitimate operator action — adjusting the
price of a tier that is still selling — to prevent a consequence that cannot
occur.

`PUT /seat-categories/{id}` still accepts a price change with active bookings and
returns `200`. **Remove any client-side warning or block around repricing**, and
drop the "this will affect existing tickets" copy — it won't.

---

## 3. §5.1 — `/events/by-organizer` for platform staff

**Service:** `event-service` · **Merged:** #565 (`5ad9c12`)

**Before:** `hasRole('SUPER_ADMIN')` · **Now:** `SUPER_ADMIN`, `PRODUCT_OFFICER`,
`PRODUCT_MANAGER`

That set is `AuthenticatedCaller.PLATFORM_STAFF_ROLES` — event-service's existing
definition of *"sees every organizer's events, not just their own"*. Both product
roles were already in it: they can already open an unpublished event and list
inactive ones. This endpoint being `SUPER_ADMIN`-only was an inconsistency, not a
tighter decision — which is why it 403'd your organiser filter for exactly the
staff whose job is reviewing events.

Request, response and pagination are **unchanged**:

```
GET /events/by-organizer?organizerUuid=<uuid>&from=2026-08-01&to=2026-09-01&venue=&page=0&size=20
```

```json
{
  "code": "200 OK",
  "message": "Events retrieved successfully",
  "data": { "content": [ … ], "totalElements": 42, "totalPages": 3, "number": 0, "size": 20 }
}
```

**You can delete the 403 fallback** you built for product staff. They get the
real answer now.

> ### Reads widened; writes did **not**
>
> `callerMayPublishAnyEvent` is still `SUPER_ADMIN` + `PRODUCT_MANAGER` only,
> because `PRODUCT_OFFICER` is read-only. *"May look at anyone's event"* and
> *"may act on anyone's event"* are different questions and stay different sets.
>
> **Keep gating action buttons on the role, not on whether the fetch
> succeeded.** A successful `by-organizer` load does not imply write access.

---

## 4. §5.5 — Reports carry their own provenance (`meta`)

**Service:** `booking-service` · **Merged:** #565 (`5ad9c12`)

### 4.1 Why

Every report endpoint **defaults its window** when `from`/`to` are omitted. The
body then describes a period the caller never named, and once exported the
context is gone entirely — a column of figures about nothing in particular.
`meta` puts the resolved period and scope in the response.

### 4.2 The four JSON reports

All under `/event-organizer/reports/`: `revenue/summary`, `revenue/by-event`,
`revenue/by-category`, `sales/timeseries`.

Each response now carries a `meta` sibling alongside `data`:

```json
{
  "code": "200 OK",
  "message": "Revenue summary retrieved",
  "data": { "grossRevenue": 18450.00, "ticketsSold": 613, "bookings": 288 },
  "meta": {
    "from": "2026-08-11",
    "to": "2026-09-09",
    "eventId": null,
    "organizerUuid": "9c1b2f3a-4d5e-6f70-8192-a3b4c5d6e7f8",
    "platformWide": false
  }
}
```

| Field | Type | Meaning |
|---|---|---|
| `from` | `LocalDate` | First day included, **inclusive** |
| `to` | `LocalDate` | Last day included, **inclusive** |
| `eventId` | `UUID \| null` | Event filtered to, or `null` for every event in scope |
| `organizerUuid` | `UUID \| null` | Whose data this is, or `null` when platform-wide |
| `platformWide` | `boolean` | `true` when the report spans every organizer |

**`from`/`to` are the RESOLVED window, not what you sent.** They come from the
same range resolution the queries use, so they cannot disagree with the rows. If
you omit both and the default is the last 30 days, `meta` tells you *which* 30
days — echoing your request parameters back would have said `null` to `null`.

**`platformWide` is explicit, not inferred from a null `organizerUuid`**, because
a null reads equally like "unknown".

### 4.3 `meta` appears only where it is set

It lives on the shared `ApiResult` envelope with field-level
`@JsonInclude(NON_NULL)`, overriding the class-level `ALWAYS`.

**Every other booking-service endpoint is byte-for-byte unchanged** — they do not
emit `"meta": null`. Only these four carry the key. If you have a shared response
type, model `meta` as **optional**.

### 4.4 CSV export: the period is in the filename

`GET /event-organizer/reports/bookings/export` — body and columns unchanged. What
changed is the header:

```
Content-Disposition: attachment; filename="organizer-bookings_2026-08-11_to_2026-09-09.csv"
```

Pattern: `organizer-bookings_<from>_to_<to>.csv`, using the **same** resolved
window as the JSON reports, so a downloaded file and an on-screen report cannot
disagree.

> **Why the filename and not a preamble row.** A leading comment line breaks every
> parser that treats line 1 as the header, Excel included. The filename is what
> actually survives into someone's Downloads folder.

If you set the download name in JS, **read it from `Content-Disposition`** rather
than composing your own — otherwise you lose the period exactly where it matters
most.

### 4.5 Errors

Nothing new. An inverted range (`from` after `to`) still `400`s, and fails
identically whether or not you read `meta` — the meta resolves through the same
code path, so it can't succeed while the query fails.

---

## 5. Built but not live yet

Both are complete, tested and in review. **Do not build against them yet**, and
do not remove any client-side mitigation you have for them. I'll add a full
section to *this document* for each one when it merges.

### #567 — §4.1, the oversell guard (your #1 risk)

Will add two new `409`s and their `503`s:

- `POST /seat-categories` — refused when the new category pushes the event's
  total allocation past its declared capacity.
- `PUT /events/{id}` — refused when lowering `totalCapacity` below what is
  already allocated across categories.

Under-allocation stays legal, so incremental seat-map building keeps working.

**Operational note for whoever deploys it:** an event that is *already*
over-allocated can only have its capacity edited **upward**, to at least the
current allocation. Existing rows are not migrated — run the detection query in
the PR before deploying.

### #566 — §1, the notifications resource

Will add `GET /notifications`, `GET /notifications/unread-count` (ETagged),
`POST /notifications/{id}/read` and `POST /notifications/read-all`, replacing the
badge you currently fabricate by polling `/admin/users`,
`/admin/service-requests` and `/events/inactive` every 60s.

Note in advance: **there is no SSE.** Polling `unread-count` with
`If-None-Match` is the intended transport, and it is cheap because a match is a
`304`.

---

## 6. Gotchas checklist

**Seat category delete (§2)**

- [ ] Show `409` `body.message` verbatim — it already names the category and count.
- [ ] Handle `503` as **retryable**, row intact. This case did not exist before.
- [ ] Never read a `503` as "probably fine, no bookings" — that inverts the guard.
- [ ] You may now delete your client-side active-booking check on delete. The
      server's count is authoritative and race-free.
- [ ] **Remove the repricing block/warning** (§2.4).

**by-organizer (§3)**

- [ ] Delete the 403 fallback for `PRODUCT_OFFICER` / `PRODUCT_MANAGER`.
- [ ] Keep write-action gating on the **role** — a successful fetch is not write
      access. `PRODUCT_OFFICER` is read-only.

**Reports (§4)**

- [ ] Model `meta` as **optional** on any shared envelope type — it is *absent*
      on non-report endpoints, not null.
- [ ] Display `meta.from`/`meta.to` on the report header, especially when the
      user picked no range. That is the whole point.
- [ ] Use `meta.platformWide`, not `organizerUuid == null`.
- [ ] Take the CSV filename from `Content-Disposition`; don't rebuild it.
- [ ] `from`/`to` are **inclusive** on both ends.

**Not yet live (§5)**

- [ ] **Keep** your client-side oversell checks until §4.1 appears in this doc.
- [ ] **Keep** the polled badge until §1 appears in this doc.
