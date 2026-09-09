# Foundry Console — Backend Requests: verified response

**In reply to:** "Foundry Console — Backend Requests", frontend, 9 September 2026
**Verified against:** `MpofuSlim/ticketing-system` `master` @ `6cf387c`,
`MpofuSlim/InnRewards` `master`, `MpofuSlim/market-place` `master` @ `e90381f`
(merge of #9, `feature/marketplace-v2-trust-discovery`).

Every claim below was checked against code, not recollection. Each item is
tagged:

- **CONFIRMED** — the FE's claim is correct, the gap is real
- **EXISTS** — it's already there; the FE is not calling it, or is calling the
  wrong thing
- **CORRECTION** — the FE's claim is wrong in a way that changes the ask
- **NOT ENFORCED / ENFORCED** — for the §4 rules

File references are `service/path:line` on the commits above.

---

## Read this first — one thing bigger than anything asked

> [!CAUTION]
> **Merchant self-service listing is broken by a cross-repo contract break.**
>
> `MpofuSlim/market-place` scopes a `MERCHANT_ADMIN` seller by the JWT's
> `merchantId` claim and refuses to create a listing without it:
>
> ```java
> // market-place  catalog/ListingService.java:524-527
> private static UUID requireMerchantId(AuthenticatedUser caller) {
>     String claim = caller.merchantId();
>     if (claim == null || claim.isBlank()) {
>         throw ApiException.forbidden("merchant_scope_missing", "Caller token carries no merchant scope");
> ```
>
> But user-service **deliberately does not mint that claim for `MERCHANT_ADMIN`**:
>
> ```java
> // ticketing-system  user-service/.../service/AuthService.java:965-973
> // Shop staff carry both shopId and merchantId stamped on their User row by
> // ShopStaffService at creation time — no lookup required. MERCHANT_ADMIN tokens
> // intentionally do NOT carry a merchantId claim; endpoints that need a merchant
> // scope read it from the request body (e.g. ShopRequest.merchantId).
> java.util.UUID loyaltyMerchantId = null;
> ...
> if (user.hasRole(User.Role.SHOP_ADMIN) || user.hasRole(User.Role.SHOP_USER)) {
>     loyaltyMerchantId = user.getLoyaltyMerchantId();
> }
> ```
>
> Consequence: **every `POST /marketplace/listings` by a `MERCHANT_ADMIN` is a
> `403 merchant_scope_missing`.** The only path that works is `SUPER_ADMIN`
> creating on a merchant's behalf with `merchantId` in the body. The
> marketplace's own `CLAUDE.md` describes MERCHANT_ADMIN self-service as the
> design; it has never been reachable through a real fleet token.
>
> InnRewards already stores what's needed to fix this — `merchants.admin_email`
> was added precisely "so AuthService can resolve a MERCHANT_ADMIN's
> merchantId at login without manual binding" (`InnRewards/.../entity/Merchant.java:80-83`)
> — but AuthService never grew that lookup. **The fix belongs in user-service:**
> at login, for a `MERCHANT_ADMIN`, resolve the loyalty merchant by
> `admin_email` (a small `GET /loyalty/internal/merchants/by-admin-email/{email}`
> on InnRewards, three-files-must-agree) and mint it as `merchantId`. That also
> unblocks the marketplace's already-built but disabled merchant new-order
> notifier (see §1).
>
> This is also the real answer to §3.2 — see there.

---

## 1. Notifications

**1.0 — CONFIRMED: no in-app notifications resource exists anywhere.**
No `/notifications` mapping in any service. The three-endpoint polling the
console does is genuinely the only way to derive a badge today.

**1.0 — CORRECTION to point 4 ("nothing reaches a non-admin").** That is not
true for events. Out-of-band delivery exists and fires:

| trigger | what happens | where |
|---|---|---|
| Event approved (real state change only) | organiser notified, email-first, WhatsApp fallback | `event-service/.../EventService.java:1035-1050` → `OrganizerNotificationGateway` |
| Event rejected | organiser notified, same channels | `EventService.java:1019-1023` |
| Marketplace order PAID | buyer gets SMS, WhatsApp fallback | `market-place` `notify/OrderPaidNotificationListener` |
| Restock | buyers who favourited get told | `market-place` V6 |

The delivery primitive behind the first two is
**`POST /users/internal/{userUuid}/notify`** (`user-service/.../InternalUserLookupController.java:84`)
— body `{subject, message}`, user-service picks the channel. Any service can
already reach a user out-of-band through it.

What genuinely does not exist: an **inbox** (persisted rows), **read state**,
an **unread count**, and a **push transport**. And one trigger is genuinely
missing — see §2.4.

**1.1 / 1.2 — Recommended shape.** Build the resource in **user-service** (it
owns identity and channels), backed by a `notifications` table:

```
GET  /notifications?unreadOnly=&page=&size=
GET  /notifications/unread-count          ← one small response, ETag on the count
POST /notifications/{id}/read
POST /notifications/read-all
POST /users/internal/{uuid}/notifications ← internal, X-Internal-Token; producers call this
```

Producers (event-service approve/reject, user-service service-request
decisions, marketplace report-actioned) write a row via the internal endpoint
**and** keep calling the existing `/notify` for out-of-band delivery. The FE's
proposed row shape (`type`, `title`, `body`, `severity`, `createdAt`, `readAt`,
`actor`, `subject{kind,id}`, `deepLink`) is fine and we'll emit `deepLink`
server-side as asked.

**1.3 — Push.** Polling `unread-count` every 60s with `If-None-Match` is
acceptable for now — that's one tiny 304 per admin per minute. SSE is a
follow-on once the resource exists; don't block on it.

**1.4 — Taxonomy.** The types that already have a producer hook and can be
emitted the day the resource lands:

| type | producer exists |
|---|---|
| `EVENT_APPROVED`, `EVENT_REJECTED` | yes — event-service |
| `SERVICE_REQUEST_APPROVED` | hook point exists, no send today (§2.4) |
| `SERVICE_REQUEST_REJECTED` | needs §2.1 first |
| `MARKETPLACE_ORDER_PAID` | yes — marketplace |
| `MARKETPLACE_REPORT_ACTIONED` | hook point exists (`PATCH /marketplace/reports/{id}`), no send today |
| `MARKETPLACE_MERCHANT_NEW_ORDER` | **built and unit-tested in marketplace, DISABLED** — needs the merchantId→admin-users lookup the "read this first" fix provides |

The rest of the FE's list (payout failed, voucher batch expiring, invoice
overdue, role changed, MFA reset, account deactivated) has no producer today;
each is a small change once the sink exists.

**1.5 — Recipients.** Requester on approve/reject, admin queue on submit.
Agreed, and it's the gap §2.4 closes.

---

## 2. Service request approval

**2.1 — CONFIRMED: approve exists, reject does not.**
`AdminServiceRequestController.java:79` is the only mutation
(`PUT /admin/service-requests/{id}/approve`).

**2.1 — CORRECTION, and it's worse than "no endpoint":** the status enum has
**no `REJECTED` value at all**:

```java
// user-service/.../entity/ServiceRequest.java:76-79
public enum Status {
    PENDING,
    APPROVED
}
```

The console is rendering, filtering and counting a state the database cannot
store. Adding reject is a **migration** (extend the CHECK / enum) plus the
endpoint, not just a controller method.

**2.2 — CORRECTION: `reason` already exists but means something else.**
`ServiceRequest.reason` (`:39-40`, `NOT NULL`, 1000 chars) is the
**requester's** justification, captured at submission. It is not a decision
reason. We'll add a separate `decision_reason` column so the two are never
conflated.

**2.3 — EXISTS.** `reviewedAt` and `reviewedBy` are on the entity (`:52-56`)
and returned by `GET /admin/service-requests`
(`ServiceRequestResponseDTO.java:43-46, :60-61`). `reviewedBy` is the numeric
reviewer `id`, not a name — say if you want it resolved.

**2.4 — CONFIRMED: approve does not notify the requester.**
`ServiceRequestService.approve` (`:139-170`) logs and returns; the `email`
it looks up at `:172` is only for the response DTO. Nothing is sent.

**Build (user-service):** `REJECTED` + migration; `PUT /{id}/reject {reason}`
(required); `decisionReason` on the record; notify the requester on both
outcomes via the existing `/notify` primitive now, and via §1 when it lands.

---

## 3. Marketplace

Marketplace is a **separate repo** (`MpofuSlim/market-place`,
`marketplace-service`), routed by the ticketing gateway
(`api-gateway/.../application.yaml:418-421`: `/marketplace/**` →
`lb://marketplace-service`; `/marketplace/internal/**` edge-denied at `:413-416`).

### 3.1 Seller approval / verification — CONFIRMED absent

No approve / reject / suspend / verify endpoints. A seller is anyone holding
`MERCHANT_ADMIN` (`market-place/.../catalog/ListingController.java:66`,
class-level `hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')`). There is no
`verified` field on `ListingResponse` — the only trust signals on it today
are `averageRating` / `reviewCount` (verified-purchase reviews,
`ListingResponse.java:70-74`) and `GET /marketplace/catalog/merchants/{merchantId}/rating`.

Your proposed shape is reasonable. Two notes: (a) it needs a seller record to
hang status on, which the marketplace doesn't have — today "seller" is just a
`merchantId` UUID on a listing; (b) it is blocked behind the "read this first"
fix, because until MERCHANT_ADMIN tokens carry a merchantId there is no
seller identity to approve.

### 3.2 Authoritative merchant identity — ANSWERED

**`marketplace.merchantId` is the InnRewards loyalty `merchants.id`. Full stop.**

The evidence: the only value that is ever placed in the JWT `merchantId`
claim is `User.loyaltyMerchantId` (`AuthService.java:970-973`), which is
stamped by `ShopStaffService.java:478` from a **loyalty** shop's `merchantId`.
The marketplace copies that claim onto `Listing.merchantId` at create time
(`market-place/.../catalog/Listing.java:23-24`) and never reads a body value for
merchants. `Listing.shopId` is likewise the loyalty shop id.

So:

- `GET /loyalty/merchants` → **authoritative**. Use it alone.
- `GET /admin/users/merchants` → returns account `userUuid`s. **Not** the
  marketplace's merchant id. Stop merging the two — that's how a listing gets
  attributed to the wrong entity.

**But** see "read this first": the claim is not minted for `MERCHANT_ADMIN`
today, so the picker is moot for self-service until user-service resolves it.
Commission can attach to `merchantId` once it does — the id is stable and
already the loyalty billing entity.

### 3.3 Catalogue browsing without authentication — EXISTS, it's public

```java
// market-place/.../security/SecurityConfig.java:34,38
.requestMatchers(HttpMethod.GET, "/marketplace/catalog/**").permitAll()
.requestMatchers(HttpMethod.GET, "/marketplace/categories").permitAll()
```

`GET /marketplace/catalog`, `/catalog/{id}`, `/catalog/{id}/image`,
`/catalog/{id}/images/{imageId}`, `/catalog/{id}/reviews`,
`/catalog/merchants/{id}/rating` and `/marketplace/categories` need no token.
**Move the screen out from behind the login.** Reporting a listing
(`POST /catalog/{id}/report`) stays `isAuthenticated()`; listings, orders,
favourites and reviews-write stay role-gated.

### 3.4 Report outcome for the reporter — CONFIRMED absent

`PATCH /marketplace/reports/{id}` (`ReportController`, SUPER_ADMIN) records
the outcome and sends nothing — no notification call in the report or
moderation code. Agreed it's a §1 consumer (`MARKETPLACE_REPORT_ACTIONED` to
the reporting buyer). The marketplace already has the fleet notification
clients wired (see its `CLAUDE.md` "Notifications"), so the send is small once
the type exists.

### 3.5 Refunds and disputes — CONFIRMED absent

```java
// market-place/.../order/OrderStatus.java
PENDING_PAYMENT, PAID, CANCELLED, EXPIRED
```

No `REFUNDED`, no dispute. `POST /marketplace/orders/{id}/cancel` exists for
the customer (pre-payment). No path back from `PAID`. Nothing named refund or
dispute anywhere in `src/main`. Agreed this is a policy decision first — and
note the fleet position on the other rails: card-not-present refunds are
supported by ZimSwitch but not modelled, and code-based InnBucks payments
have **no** real-time reversal at all (ticketing `CLAUDE.md`). Whatever the
marketplace policy is, it has to be honest about which rail the order was paid
on.

### 3.6 Image sizing — CONFIRMED

`CatalogController` image endpoints (`:224`, `:253`) take no width/resize
parameter; bytes are served as stored. A `?w=` with server-side downscale
(or a stored thumbnail at upload) is a reasonable ask; images already go
through a magic-byte check on upload, so the pipeline has a natural place for
it.

### 3.7 Commercial model — CONFIRMED nothing charged

No commission or fee code in the marketplace. Payment confirmation is the
S2S `PATCH /marketplace/internal/orders/{ref}/confirm-payment` with a
paid-amount cross-check (the 100x guard). Blocked behind §3.2 as you said —
and §3.2 is now blocked behind the "read this first" fix.

---

## 4. Rules only the frontend enforces

| # | Rule | Server | Evidence |
|---|---|---|---|
| **4.1** | Seat allocations must equal capacity before approve | **NOT ENFORCED** | `approveEvent` (`EventService.java:1035-1045`) only sets `rejected=false`. No seat-sum check anywhere in event-service — `seatCategoryGateway` is used once, for display (`:366`). |
| **4.2** | Booked category must not be deleted / repriced | **NOT ENFORCED** | `deleteCategory` (`seat-service/.../SeatCategoryService.java:378-395`) soft-deletes with no booking check. `updateCategory` accepts any positive price; the booking counts it fetches (`:239-243`) feed only the availability figure in the response. |
| **4.3** | Ticket cannot be admitted twice | **ENFORCED, race-safe** | `claimRedemption` is an atomic `UPDATE … WHERE redeemedAt IS NULL` (`booking-service/.../BookingItemRepository.java:135-142`); 0 rows → `ALREADY_REDEEMED`. Downgrade your check. |
| **4.4** | Fee floors $0.25 / 1% | **NOT ENFORCED** | No floor anywhere in InnRewards. Server validates shape only — negative, mixed fixed/percent, zero percent, zero *issue* fee (`MerchantService.java:152-174`, `:291`). A $0.01 fixed fee is accepted today. |
| **4.5** | CSV ≤ 1000 rows | **ENFORCED** | `MAX_BULK_ROWS = 1000` (`user-service/.../ShopStaffService.java:69`). Downgrade. |
| **4.6** | Banner JPEG/PNG/WebP, ≤10 MB, magic bytes | **ENFORCED** | `applyBanner` (`EventService.java:722-750`). Applies to create and to the new `PUT /events/{id}/banner`. Downgrade. |

**Builds:**

- **4.1 (event-service)** — on approve, and on any transition to on-sale, fetch
  the event's categories from seat-service, `Σ totalSeats` vs
  `event.totalCapacity`, `409` on mismatch with both numbers in the message.
  Agreed this is first.
- **4.2 (seat-service)** — refuse delete when active bookings > 0 (`409`).
  Reprice with bookings is a **policy** question: block it, or allow it and
  guarantee existing bookings keep the price they paid. Say which.
- **4.4 (InnRewards)** — floors as `LoyaltyProperties` config
  (`LOYALTY_FEE_MIN_FIXED`, `LOYALTY_FEE_MIN_PERCENT`), enforced in
  `MerchantService` and `RuleAdminService.build` so onboarding and rule edits
  can't diverge. Confirm $0.25 / 1% are the platform's numbers and not just
  the console's.

---

## 5. Smaller things

**5.1 `/events/by-organizer` — CONFIRMED restricted.**
`@PreAuthorize("hasRole('SUPER_ADMIN')")` (`EventController.java:334`).
`PRODUCT_OFFICER` / `PRODUCT_MANAGER` get `403` and your fallback is correct
behaviour today. Widening it is a one-line change — say if you want it.

**5.2 `TENANT_ADMIN` / `PLATFORM_ADMIN` — EXISTS in loyalty, not seeded in
user-service.** They are real authorities in InnRewards
(`ExchangeRateController.java:138,202`, `RuleAdminService.java:33`,
`ReportingService.java:273`) but are not among user-service's built-ins.
Since V35 roles are **data**: `POST /admin/roles` can create `TENANT_ADMIN`
today, it becomes `ROLE_TENANT_ADMIN` on the token, and loyalty's `hasRole`
checks will honour it. So the FX override is reachable — nobody has created
the role. We'll seed both in a migration so it's not a manual step per cell.

**5.3 `EVENT_ORGANISER` — resolved.** No S-spelling in code (one comment). The
V35 `UserAdminService.setRoles` validation refuses names not in the `roles`
table, so the S form cannot persist going forward. Pre-V35 rows: send the
ids and we'll correct them; or run
`SELECT user_id FROM user_roles WHERE role = 'EVENT_ORGANISER'` yourself.

**5.4 Payment code delivery — deliberate, documented.** Ticketing `CLAUDE.md`:
"The code reaches the customer ONLY via the response … there is no
out-of-band delivery … Trade-off accepted." Reopening it is a product call.
If taken, the send is cheap — the SMS/WhatsApp clients and per-user `/notify`
already exist.

**5.5 Report provenance — agreed.** The scan reports now echo the
`from`/`to` actually queried (merged today, #559). Extending `period` +
`scope` to the other exports is a small, mechanical change per report.

---

## 6. Backlog by repository, in the order we'd do it

### `MpofuSlim/ticketing-system` — user-service

1. **Mint `merchantId` for `MERCHANT_ADMIN` at login** (the "read this first"
   fix). Resolve via InnRewards `admin_email`. Unblocks §3.1, §3.2, §3.7 and
   the marketplace merchant-order notifier.
2. Service requests: `REJECTED` + migration, `PUT /{id}/reject {reason}`,
   `decisionReason`, notify requester on both outcomes (§2).
3. Notifications resource + `unread-count` + internal producer endpoint (§1).
4. Seed `TENANT_ADMIN` / `PLATFORM_ADMIN` (§5.2).

### `MpofuSlim/ticketing-system` — event-service / seat-service

5. Seat-sum-equals-capacity guard on approve/on-sale (§4.1).
6. Refuse delete of a booked category; reprice policy (§4.2).
7. Widen `/events/by-organizer` to product staff if wanted (§5.1).
8. Notification producers for approve/reject once §1 lands.

### `MpofuSlim/InnRewards`

9. `GET /loyalty/internal/merchants/by-admin-email/{email}` — the S2S lookup
   for item 1 (three-files-must-agree: controller token check, SecurityConfig,
   gateway `loyalty-internal-deny` already covers `/loyalty/internal/**`).
10. Fee floors as config, enforced on merchant create and rule edit (§4.4).

### `MpofuSlim/market-place`

11. Nothing is *required* for §3.2 / §3.3 — the FE just needs to use the
    right registry and drop the auth on catalog reads.
12. Seller record + approve/reject/suspend + `verified` on `ListingResponse`
    (§3.1) — after item 1.
13. `MARKETPLACE_REPORT_ACTIONED` producer to the reporting buyer (§3.4) —
    after §1.
14. `?w=` / stored thumbnail on catalog images (§3.6).
15. Enable the merchant new-paid-order notifier once item 1 gives it a
    resolver.
16. Refund/dispute model — after a policy decision (§3.5).

### Frontend — can do now, no backend change

- Use `GET /loyalty/merchants` alone for the merchant picker (§3.2).
- Serve the catalogue unauthenticated (§3.3).
- Downgrade 4.3, 4.5, 4.6 to fast-fails; keep 4.1, 4.2, 4.4 as guards until
  the server has them.
- Stop rendering a `REJECTED` filter for service requests until §2 ships —
  the value cannot currently exist.
