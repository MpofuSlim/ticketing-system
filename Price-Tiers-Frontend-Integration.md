# Price Tiers (Early Bird, Phases, "First 100") — Frontend Integration Guide

**For:**
- the **Foundry portal**, where organizers set tiers and read the report (§4);
- the **Super app** and **Ticketize web**, which show prices and sell tickets (§5).

Each section names its client.

**Change:** a ticket category can now sell below its normal price in ordered
**price tiers**. For example, VIP normally costs **150.00**:

| Tier | Price | Closes |
|---|---|---|
| Early bird | 100.00 | after the first 100 tickets or at the end of 8 September |
| Phase 1 | 120.00 | at the end of 20 September |
| Full price | 150.00 | (after both tiers close) |

The backend works out the price on every read and charges every booking by
the same rule. A client never computes a price itself.

Merged in **PR #723** (seat-service V9, booking-service V26, event-service).
Anchored to the merged code (master `37d8540`).

> [!NOTE]
> **Not in this release** (separate PR, not merged yet — don't build against them):
> - tier start dates, a per-customer limit, and refunds that keep their place;
> - promo codes;
> - "remind me before the price goes up";
> - price-change history;
> - projected revenue in analytics;
> - the tier name printed on the ticket page.
>
> A separate guide will cover them when that PR merges.

---

## 1. Base URL, auth, headers

| | |
|---|---|
| **Base URL** | the API gateway, e.g. `https://<host>/foundry` |
| **Auth (writes)** | `Authorization: Bearer <JWT>` on `POST /seat-categories`, `PUT /seat-categories/{id}`, `PUT /seat-categories/{id}/price-tiers` and the organizer report |
| **Roles (writes)** | `EVENT_ORGANIZER` (own events only) or `SUPER_ADMIN` (any event) |
| **Public reads** | `GET /seat-categories?eventId=`, `GET /seat-categories/{id}` and `GET /events/{id}` need **no token** |
| **`X-Tenant-Id`** | **not used** by seat, event or booking service. Do not send it. |
| **Gateway** | no new route — the existing `/seat-categories/**`, `/events/**`, `/bookings/**` and `/event-organizer/**` routes cover everything here |

---

## 2. The rule (read this once)

- Tiers apply **in the order you send them**. The price is the **first tier that
  is still open**; once every tier has closed, the category's `price` applies.
- A tier **closes at `endsAt` or after `quantity` tickets, whichever comes
  first**. It must have at least one of the two.
- **`price` always means the full price.** `currentPrice` is what a booking made
  now pays.
- "The first 100" is exact. The count is taken under the stock lock at
  booking time, and it counts tickets **held or sold** (PENDING + CONFIRMED).
  A hold that expires unpaid frees its place, so a `SOLD_OUT` tier can reopen
  if its end has not passed.
- **One booking can straddle a boundary.** If 2 Early bird tickets are left and
  a customer books 3, they get 2 at 100.00 and 1 at the next tier's price.
- **A sold ticket never changes price.** Editing or removing tiers only affects
  the next booking.

---

## 3. Times: what you send, what you get back

| Direction | Format | Example |
|---|---|---|
| **You send** `endsAt` | the organizer's **local wall clock, no zone**, exactly as typed | `"2026-09-08T23:59:59"` (runs through 8 September) |
| **You get back** `endsAt` | the market offset (Harare on the ZW cell) | `"2026-09-08T23:59:59+02:00"` |

- **Render the returned string verbatim.** Do no timezone arithmetic, and don't
  append `Z`. If you send a value with `Z` or an offset, it is taken as that
  exact instant and never shifted twice.
- When re-sending a kept tier, you may send its `endsAt` back exactly as you
  received it (with the offset).

---

## 4. Foundry portal (organizer console)

### 4.1 Create a category with tiers — `POST /seat-categories`

`priceTiers` is optional on the existing create body. Send at most 10 tiers, in
the order they apply.

```json
{
  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "VIP",
  "description": "Front rows",
  "price": 150.00,
  "priceTiers": [
    { "name": "Early bird", "price": 100.00, "endsAt": "2026-09-08T23:59:59", "quantity": 100 },
    { "name": "Phase 1",    "price": 120.00, "endsAt": "2026-09-20T23:59:59" }
  ],
  "sections": [
    { "section": "A", "seatCount": 25 },
    { "section": "B", "seatCount": 25 }
  ]
}
```

| Tier field | Type | Required | Rule |
|---|---|---|---|
| `name` | string ≤ 60 | yes | unique within the category (ignoring case) |
| `price` | number | yes | > 0, at most 2 decimals, **lower than the category `price`** |
| `endsAt` | string | one of `endsAt` / `quantity` | local time, no zone; must be in the future |
| `quantity` | integer ≥ 1 | one of `endsAt` / `quantity` | tickets that sell at this tier |
| `id` | uuid | no | only when replacing (§4.2) |

The response is **`201`**, with the category in the list shape (§4.3).

### 4.2 Change tiers later — `PUT /seat-categories/{id}/price-tiers`

The body is the **whole list**, which **replaces** the current one:

```json
{
  "tiers": [
    { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
      "endsAt": "2026-09-08T23:59:59", "quantity": 150 },
    { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
      "endsAt": "2026-09-20T23:59:59" },
    { "name": "Phase 2", "price": 140.00, "endsAt": "2026-09-27T23:59:59" }
  ]
}
```

- **Send the `id` of every tier you keep.** A kept tier is updated in place, so
  the tickets it has already sold still count toward its `quantity`.
  - A tier sent **without** an `id` is new, and its count starts at 0.
  - A stored tier **left out** of the list is deleted. Its sold tickets keep
    their tier name.
- `"tiers": []` removes every tier.
- Reordering is fine: the list order is the order of application.
- A kept tier whose end has already passed may be sent back unchanged. A
  **new** or **changed** end must be in the future.
- The response is **`200`**, message `"Price tiers set"`, with the category in
  the list shape.
- `PUT /seat-categories/{id}` (rename, description, full price) **never touches
  the tiers**. An older console build therefore cannot wipe them.

### 4.3 Read back — `GET /seat-categories?eventId=…`

The same shape comes back from create, update and set-tiers:

```json
{
  "code": "200 OK",
  "message": "Categories retrieved successfully",
  "data": [
    {
      "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
      "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "VIP",
      "description": "Front rows",
      "price": 150.00,
      "currentPrice": 100.00,
      "currentTierName": "Early bird",
      "priceTiers": [
        { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
          "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 100, "sold": 37, "remaining": 63, "status": "ACTIVE" },
        { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
          "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "sold": 0, "status": "UPCOMING" }
      ],
      "availableSeats": 13,
      "sections": [ { "section": "A", "seatCount": 25 }, { "section": "B", "seatCount": 25 } ]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `price` | the full price (what the organizer edits) |
| `currentPrice` | what one ticket costs if booked **now** |
| `currentTierName` | the ACTIVE tier's name; `null` at full price |
| `priceTiers[].status` | `ACTIVE` (the price now), `UPCOMING` (applies once the tiers before it close), `ENDED` (past `endsAt`), `SOLD_OUT` (`quantity` reached; can reopen if a hold expires before `endsAt`) |
| `priceTiers[].sold` | tickets held or sold at the tier. **Absent** if booking-service could not be asked. |
| `priceTiers[].remaining` | `quantity − sold`, never below 0. **Absent** with no `quantity` or no count. |

If `sold` is absent, show the tier without counts. The listing does not fail.

### 4.4 Editing the full price — `PUT /seat-categories/{id}`

The full price must stay **above** every tier that has not passed its end.
`SOLD_OUT` tiers count too, because they can reopen. Otherwise the edit is
refused with this message:

```json
{ "code": "400 BAD_REQUEST",
  "message": "The ticket price must be higher than the price tier \"Phase 1\" (120.00). Lower or remove that tier first.",
  "data": null }
```

In the UI, offer to edit the tiers first.

### 4.5 Sales by tier — `GET /event-organizer/reports/by-category?eventId=…`

Each category row now carries `priceTiers`:

- rows appear in the order each tier first sold;
- the `"Full price"` row (`priceTierId: null`) is always last;
- the tier rows add up to the category's `ticketsSold` and `revenue`.

```json
{ "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11", "categoryName": "VIP",
  "ticketsSold": 120, "revenue": 13100.00,
  "priceTiers": [
    { "priceTierId": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "ticketsSold": 100, "revenue": 10000.00 },
    { "priceTierId": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "ticketsSold": 15, "revenue": 1800.00 },
    { "priceTierId": null, "name": "Full price", "ticketsSold": 5, "revenue": 750.00 }
  ] }
```

`name` is the tier's name **when the tickets were sold**, so a tier renamed
later still reports under the name it sold as.

### 4.6 Guest list

`GET /bookings/by-event/{eventId}` and `GET /bookings/by-category/{categoryId}`
now carry `priceTierName` on each ticket. It is **absent** when the ticket was
sold at full price.

---

## 5. Super app / Ticketize web (customer side)

### 5.1 Event page — `GET /events/{id}` only

> [!WARNING]
> **Correction (2026-10-22): the event LISTS carry no prices.** `GET /events`,
> `GET /events/active`, search and by-country return `"seatCategories": null`
> for every event. Only `GET /events/{id}` fills it, and so only the event page
> can show tiers. The Swagger examples for the list endpoints showed
> categories, which was wrong; the first version of this guide repeated it.
> Event cards and Discover therefore have no price to show or filter on until
> the backend adds one (not built yet).

Each seat category gains three fields:

```json
{
  "name": "VIP",
  "categoryPrice": 150.00,
  "currentPrice": 100.00,
  "currentTierName": "Early bird",
  "priceTiers": [
    { "name": "Early bird", "price": 100.00, "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 100, "remaining": 63, "status": "ACTIVE" },
    { "name": "Phase 1", "price": 120.00, "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "status": "UPCOMING" }
  ],
  "sections": [ { "section": "A", "seatCount": 25, "price": 150.00 } ]
}
```

- **Show `currentPrice` as the ticket price.** `categoryPrice` and
  `sections[].price` stay the full price. Show it struck through when
  `currentTierName` is set, e.g. "~~150.00~~ **100.00** · Early bird ends 8 Sep 23:59".
- `remaining` here may be up to 30 seconds old ("63 left at this price").
- A category with no tiers has `priceTiers: []`, `currentTierName: null` and
  `currentPrice == categoryPrice`.

### 5.2 Booking — `POST /bookings` (no request change)

**Send nothing new.** The backend prices each ticket at booking time. Read the
price from the **response**:

- `totalAmount`: what the customer pays;
- `items[i].priceAtBooking`: each ticket's own price;
- `items[i].priceTierName`: e.g. `"Early bird"`, **absent** at full price.

```json
"items": [
  { "categoryName": "VIP", "priceAtBooking": 100.00, "priceTierName": "Early bird", "ticketNumber": "20260905-7KQ4M-9XZ2B" },
  { "categoryName": "VIP", "priceAtBooking": 100.00, "priceTierName": "Early bird", "ticketNumber": "20260905-3HD8W-1PQ7R" },
  { "categoryName": "VIP", "priceAtBooking": 120.00, "priceTierName": "Phase 1",    "ticketNumber": "20260905-9MN2C-4TV6K" }
]
```

The customer's own ticket views (`/bookings/my`, a single booking, the
phone-wallet and magic-link views) carry `priceTierName` the same way.

---

## 6. Error handling

All refusals use the standard envelope:
`{ "code": "400 BAD_REQUEST", "message": "...", "data": ... }`.

### 6.1 Top-level (`data: null`) — show `message` as-is

These come from `POST /seat-categories` and `PUT /seat-categories/{id}/price-tiers`.
They are written for the organizer and name the tier.

| When | `message` |
|---|---|
| tier not below the full price | `The price of "Early bird" must be lower than the ticket price (150.00).` |
| neither end nor quantity | `"Early bird" needs an end date, a number of tickets, or both.` |
| end in the past | `The end of "Early bird" must be in the future.` |
| unreadable end | `The end of "Early bird" must be a date and time like 2026-09-08T23:59:59.` |
| duplicate name | `Two price tiers are named "Phase 1". Give each tier its own name.` |
| more than 2 decimals | `The price of "Early bird" can have at most 2 decimal places.` |
| price ≤ 0 | `The price of "Early bird" must be greater than 0.` |
| quantity < 1 | `The number of tickets for "Early bird" must be at least 1.` |
| blank name | `Every price tier needs a name.` |
| same `id` twice | `Price tier <id> is listed twice.` |
| `id` from another category | `Price tier <id> does not belong to this category.` |
| more than 10 | `At most 10 price tiers per category.` |
| full price edit (§4.4) | `The ticket price must be higher than the price tier "Phase 1" (120.00). Lower or remove that tier first.` |

### 6.2 Per-field (bean validation) — `message: "Validation failed"`

`data` maps a field path to its message. Highlight that row and field:

```json
{ "code": "400 BAD_REQUEST", "message": "Validation failed",
  "data": { "priceTiers[0].price": "Tier price must be greater than 0" } }
```

The path is `priceTiers[i].…` on create and `tiers[i].…` on `PUT …/price-tiers`.
A missing `tiers` key gives `"tiers is required (send [] to remove every tier)"`.

### 6.3 Others

| Status | When |
|---|---|
| `401` | missing or invalid token on a write |
| `403` `"Forbidden - insufficient role"` | not the event's organizer, or not SUPER_ADMIN |
| `404` `"Seat category not found"` | unknown category id |

---

## 7. Gotchas checklist

- [ ] **Show `currentPrice`, not `price` / `categoryPrice`,** as the price a customer pays now.
- [ ] **Never compute a total client-side.** Show `totalAmount` from the booking response; one booking can straddle two tiers.
- [ ] **Keep tier `id`s** when editing. Omitting them resets a tier's sold count (a new tier) or deletes it (left out).
- [ ] `PUT …/price-tiers` **replaces the whole list**: always send every tier you want to keep.
- [ ] Send `endsAt` as typed, **no zone**. Render returned `endsAt` **verbatim**.
- [ ] `sold` / `remaining` can be **absent**. Render the tier without counts; don't treat absent as 0.
- [ ] A `SOLD_OUT` tier can return to `ACTIVE` when a hold expires. Refresh rather than cache the status.
- [ ] The event page's `remaining` can be ~30 s old. The booking response is the truth.
- [ ] Tier prices must stay **below** the full price. Lowering the full price may need tier edits first (§4.4).
- [ ] `priceTierName` is **absent** (not `null`) at full price. Check for the key.
