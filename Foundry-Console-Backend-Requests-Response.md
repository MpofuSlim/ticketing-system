# Foundry Console — Backend Response

**To:** frontend (Foundry admin console, `nyanyiwast/innbucks-ticketing`)
**Re:** *Foundry Console — Backend Requests*, 9 September 2026
**Date:** 9 September 2026

Answers to every item, in your numbering. Each one was **checked against the
code**, not answered from memory — where you marked ❓ we went and looked, and
in four places you were right about a gap we had not confirmed.

## Legend

| | |
|---|---|
| ✅ **Shipped** | built and merged — details below |
| 🔵 **Already exists** | it's there today; you can delete the workaround |
| ⚠️ **Gap confirmed** | you were right, the server does not do this — keep your check |
| 🟡 **Partly** | some of it holds, the specific thing you asked about doesn't |
| ⛔ **Needs a decision** | not an endpoint question yet |

## Summary

| # | Item | Status |
|---|---|---|
| 1 | Notifications resource | ⛔ not built — see §1 |
| 2.1–2.4 | Service request reject + reason + attribution + notify | ✅ **shipped** |
| 3.1 | Marketplace seller approval + badge | ✅ **shipped** |
| 3.2 | Authoritative merchant id | ✅ **answered + fixed** |
| 3.3 | Public catalogue | 🔵 **already public** |
| 3.4 | Report outcome to reporter | ⛔ blocked on §1 |
| 3.5 | Refunds / disputes | ⛔ policy first |
| 3.6 | Image sizing | ⚠️ gap confirmed |
| 3.7 | Commercial model | ⛔ nothing charged today |
| 4.1 | Capacity vs allocations on approve | ⚠️ **gap confirmed — keep your guard** |
| 4.2 | Reprice/delete a booked category | ⚠️ **gap confirmed — nothing guards it** |
| 4.3 | Double admission | 🔵 server-enforced, atomically |
| 4.4 | Fee floors | 🟡 zero refused; **the $0.25/1% floors are yours alone** |
| 4.5 | CSV 1000-row cap | 🔵 enforced |
| 4.6 | Banner validation | 🔵 enforced |
| 5.1 | `/events/by-organizer` + product staff | ⚠️ SUPER_ADMIN only — keep the fallback |
| 5.2 | `TENANT_ADMIN` / `PLATFORM_ADMIN` | ⚠️ real defect — **fixable without code** |
| 5.4 | Payment code delivery | ⛔ deliberate today |
| 5.5 | Report provenance | ⚠️ gap confirmed |

---

# 1. Notifications ⛔

**You are right, and nothing has been built yet.** There is no notifications
resource, no unread count, no read state and no push transport. The bell is
still three list fetches.

What *did* land is narrower than §1 and worth separating from it: service-request
decisions now push an **outbound** email/WhatsApp to the requester (§2.4). That
closes the specific "the requester is never told" case for **one** event type. It
is not a notifications resource — there is nothing to GET, nothing to mark read,
no deep link.

This is the largest open item and it needs a decision on scope before it is
built (schema, retention, fan-out, and whether SSE or polling+`ETag`). We are not
going to guess at your taxonomy in §1.4 — the recipient rules in §1.5 in
particular are policy, not plumbing.

**Interim, so you can stop the worst of the polling:** nothing we can offer
today removes the three fetches. If the 60s interval is hurting, reduce it — none
of the three collections changes fast enough to need a minute.

---

# 2. Service request approval ✅ SHIPPED

**`ticketing-system` PR #561, merged (`3a4a758`), migration `V36`.**

A full integration guide is attached separately
(`ServiceRequest-Rejection-Frontend-Integration.md`) — the essentials:

### 2.1 Reject endpoint ✅

```
PUT /admin/service-requests/{id}/reject
Content-Type: application/json

{ "reason": "Business verification outstanding — please complete it and re-apply." }
```

`reason` is **required**, non-blank, max 1000 chars, and shown to the requester
verbatim. Permission: `service-requests:approve` — deliberately the **same** as
approve, because both are the power to decide. There is no separate reject
permission to gate your UI on.

### 2.2 Reason on the record ✅

`decisionReason` on the response and on `GET /admin/service-requests`.

> **`reason` and `decisionReason` are different people.** `reason` is the
> **requester's** justification (`NOT NULL`); `decisionReason` is the
> **reviewer's**. Writing the admin's words into `reason` would erase the
> applicant's own case. Don't render them in the same field.

### 2.3 Decision attribution 🔵 already existed

You asked for `decidedBy` / `decidedAt`. They exist under different names —
**`reviewedBy`** (the admin's user id) and **`reviewedAt`** — and were on the
record before this change. Approvals were never anonymous; the field names just
didn't match what you looked for.

### 2.4 Notify the requester ✅ both outcomes

Approval used to be **silent**. Both outcomes now notify, email-first with
WhatsApp fallback, fired after the decision transaction commits (so a rolled-back
decision never announces itself).

The approval copy tells them to **sign in again** — the grant rides in the JWT
and is invisible until a fresh token is minted. Please mirror that in the
admin-side confirmation too, or you'll field *"it says approved but I still can't
see it"*.

### ⚠️ Two corrections to the EXISTING approve endpoint

**You already call this one, so this is a behaviour change on you.** Both
decision paths used to throw a bare `RuntimeException`, which the global handler
collapses into a 400 reading *"We couldn't process your request. Please try
again."* — inviting an admin to retry something that can never succeed.

| Case | Before | Now |
|---|---|---|
| not found | 400, generic text | **404**, real message |
| already decided | 400, generic text | **400 naming the status it holds** |

If your approve handler special-cases that generic string, or assumes 400 is the
only failure, update it. The "already decided" message names the status, so you
can say *"a colleague already approved this"* and re-fetch the queue.

### Nullable fields are OMITTED, not `null`

The response DTO is `@JsonInclude(NON_NULL)`. `decisionReason`, `reviewedAt`,
`reviewedBy`, `userEmail` and `userFullName` are **absent from the payload**
when unset. Use optional chaining; don't assume the key exists.

The last two matter because **reject tolerates a deleted requester** (approve
does not — there'd be nobody to grant to). A legitimately closed row can carry no
name at all.

### A rejection is not a ban

The pending-uniqueness index is scoped `WHERE status = 'PENDING'`, so a rejected
row does **not** block re-requesting the same bundle. Don't render "permanently
declined" or grey out the bundle.

---

# 3. Marketplace

## 3.1 Seller approval and verification ✅ SHIPPED

**`market-place` PR #27, migration `V8`.** Everything you asked for, with one
deliberate difference from your proposed shape.

```
GET  /marketplace/admin/sellers?status=&page=&size=    queue, oldest first
PUT  /marketplace/admin/sellers/{merchantId}/approve   { note?, displayName? }
PUT  /marketplace/admin/sellers/{merchantId}/reject    { note }   ← required
PUT  /marketplace/admin/sellers/{merchantId}/suspend   { note }   ← required
PUT  /marketplace/admin/sellers/{merchantId}/reinstate { note? }
```

SUPER_ADMIN only, class-level. Keyed by **`merchantId`**, not a surrogate seller
id — a merchant *is* the seller here, so "one trust record per merchant" is true
by construction. Statuses: `PENDING | APPROVED | REJECTED | SUSPENDED`
(**`reinstate`** is the fourth verb you didn't ask for, but suspension needs a way
back).

`displayName` is read on **approve only** — naming a seller is part of vouching
for them.

### The badge, on every listing payload

```json
"seller": {
  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
  "displayName": "Rudo Traders",
  "verified": true,
  "since": "2026-04-01T09:15:00Z"
}
```

- **`verified` is `APPROVED` only.** Never inferred from a merchant merely
  existing — exactly the claim you said you couldn't fake, so we didn't either.
- **`displayName` is nullable.** This service holds ids, not names, and there's
  no merchant-name lookup in it. An admin sets it on approve. Render the listing
  without a seller name rather than inventing one. (Resolving it automatically
  from InnRewards is a clean follow-up.)
- **`since`** is their first listing for pre-V8 merchants, so the queue order
  reflects how long they've actually traded here.
- Resolved with **one batched query per page**, so a page of listings costs the
  same regardless of size.

### Two things to know before you build the screen

**Only `REJECTED` and `SUSPENDED` stop a seller publishing.** A `PENDING` seller
can still trade, unbadged. Making PENDING a hard gate turns this into an
approval-queued marketplace, which is a **product decision about onboarding
friction** — say the word and it's a one-line change, but we weren't going to
make it as a side effect of adding a trust record.

**Every existing merchant was backfilled as `PENDING`, not `APPROVED`.** None was
ever vetted, so marking them approved would have put a verified badge on the whole
catalogue on day one — the platform asserting something it hasn't done. Your
queue will therefore have a real backlog in it on first load. That backlog is the
point; nobody is blocked by it.

**Suspend also takes the seller's live listings down**, in the same transaction.
Reinstate deliberately does **not** re-publish them — the seller chooses what goes
back on sale.

## 3.2 One authoritative merchant identity ✅ ANSWERED — and it was broken

**`GET /loyalty/merchants` is authoritative for `marketplace.merchantId`.**
`GET /admin/users/merchants` returns account `userUuid`s and is *not* the same
id space. **Stop merging the two registries in your picker** — query loyalty only.

Verifying this turned up something you didn't ask about but were blocked by:

> **MERCHANT_ADMIN self-service listing has never worked in production.**
> marketplace-service scopes a seller *exclusively* from the JWT's `merchantId`
> claim and refuses without one (`403 merchant_scope_missing`) — but
> MERCHANT_ADMIN tokens deliberately carried no such claim. Only the
> SUPER_ADMIN on-behalf path worked.

**Fixed in `ticketing-system` PR #560, merged.** A MERCHANT_ADMIN's JWT now
carries `merchantId`, resolved from loyalty by the admin's email. No FE change
needed — it appears on the next login.

Two behaviours to expect:

- **An admin owning several merchants gets NO claim** and the same clean 403 as
  before. A claim is singular and the consumer treats it as authoritative
  ownership; minting one of several would attribute listings — and therefore
  commission — to an arbitrary merchant, surfacing at invoicing rather than at the
  call. Widening this needs an explicit merchant selector on your side; tell us if
  you have multi-merchant admins in practice.
- **It can never fail a login.** If loyalty is down the token mints *without* the
  claim: the user signs in and only merchant-scoped calls are refused.

## 3.3 Catalogue browsing without authentication 🔵 ALREADY PUBLIC

You're calling them authenticated unnecessarily. Verified in
`marketplace-service`'s `SecurityConfig`:

```
GET /marketplace/catalog/**   permitAll
GET /marketplace/categories   permitAll
```

Both are already anonymous (GET only — writes under `/catalog/**` stay
authenticated), and it's pinned by an integration test. **Move the screen out
from behind the login.** Do not send an `Authorization` header on those two.

## 3.4 Report outcome for the reporter ⛔

Agreed, and agreed it's best solved by §1 rather than a bespoke endpoint. Held
behind it.

## 3.5 Refunds and disputes ⛔ POLICY FIRST

Confirmed: `PENDING_PAYMENT → PAID` has no path back, in marketplace or on the
payment rails. Worth knowing why the payment side is hard, so the policy
conversation is grounded:

- **InnBucks 2D code:** real-time reversals are **not available** for code-based
  transactions. Refunds there are an operator procedure by the provider's design.
- **ZimSwitch card:** card-not-present refunds *are* supported by the platform
  but are not modelled in our code yet.
- **EcoCash:** refunds *are* supported upstream but likewise not modelled.

So "refund the buyer" is not one switch. Write your copy against an **operator
procedure**, not a self-service action, until this is decided.

## 3.6 Image sizing ⚠️ GAP CONFIRMED

There is no resizing, no `?w=`, no `thumbnailUrl`, nothing — images are stored
and served at original resolution. You are right that a grid of full-size photos
on metered data is expensive, and there is no server-side mitigation to point you
at today.

## 3.7 Commercial model ⛔

**Nothing is charged on a marketplace order today.** The billing machinery exists
at platform level (loyalty invoicing bills merchants for *voucher* activity), but
no marketplace order touches it. Treat orders as non-chargeable when designing the
seller and admin screens; if that changes it will be a deliberate decision, and
§3.2 is now unblocked either way.

---

# 4. Rules only the frontend enforces

Two of these are real. **Do not downgrade 4.1 or 4.2 from guards to fast-fails.**

## 4.1 Capacity vs seat allocations ⚠️ GAP CONFIRMED — your #1 was right

`EventService.approveEvent` **only flips `rejected = false`**. It does not call
seat-service, does not sum category allocations, and does not compare them to
`totalCapacity`. There is no cross-service check at any point in approval.

**Your `EventDetail` block is the only thing preventing oversell.** Keep it, and
keep treating it as the guard. Anyone calling the API directly — a script, a
second client, a stale bundle of yours — can approve a mis-configured event today.

This is the one we agree should be fixed first, for exactly the reason you gave.

## 4.2 Reprice or delete a booked seat category ⚠️ GAP CONFIRMED

Also real, and it's worse than "unguarded on your side":

- `SeatCategoryService.deleteCategory` soft-deletes after an **ownership check
  only**. No booking check.
- `SeatCategoryService.updateCategory` sets the new price after validating
  **positivity, name-uniqueness and ownership**. No booking check.

So a category with paid tickets against it can be deleted or repriced right now,
by its owning organizer, through the normal API.

**This needs a policy decision before an endpoint change**, and it's yours to make:
does repricing a booked category (a) get refused outright, (b) get allowed and
leave existing bookings at their paid price, or (c) get allowed only while zero
bookings exist? (b) is what the data already does — bookings store their own price
— so the honest options are really "refuse" vs "allow and say so in the UI". Tell
us which and we'll enforce it.

## 4.3 A ticket cannot be admitted twice 🔵 SERVER-ENFORCED, ATOMICALLY

Solid. `TicketScanService` claims the redemption with a **conditional `UPDATE`**
that returns rows-affected: `1` means this scanner won, `0` means someone else
already redeemed it. First scanner wins, decided in the database, so two
simultaneous scans cannot both succeed. The loser gets `ALREADY_REDEEMED` with the
original `redeemedAt` / `redeemedByName` so gate staff can see who admitted them
and when.

Downgrade your client check to a fast-fail with confidence.

## 4.4 Issuing-fee floors 🟡 PARTLY — the floors are yours alone

Careful, this one is half-true and the half that's missing is the half you asked
about.

**What the server enforces:** a fee may not be **zero**. Creating a merchant whose
effective issue fee resolves to zero is refused (`MERCHANT_ZERO_ISSUE_FEE`), a
zero issue fee on a rule is refused (`RULE_ZERO_ISSUE_FEE`), a `PERCENTAGE` fee of
0 is refused (`FEE_PERCENT_ZERO`), negatives are refused (`FEE_NEGATIVE`), and the
type/value combinations are validated.

**What it does not enforce:** the **$0.25 fixed / 1% percentage floors**. Those
exist only in your `utils/feeModes.js`. The server would happily accept `$0.01`
fixed or `0.1%`.

So your risk column is right for the floors specifically. **Keep that check as a
guard.** If the floors are genuinely platform policy rather than a UI convention,
say so and we'll add them server-side — that's a two-line change, we just aren't
going to invent a platform price floor on our own initiative.

(One deliberate exception worth knowing: a merchant *can* be onboarded unbilled
via `waiveFees: true` **with a mandatory reason**, which records who decided it.
That's what makes "free on purpose" distinguishable from "free by accident".)

## 4.5 CSV bulk upload capped at 1000 rows 🔵 ENFORCED

`ShopStaffService.MAX_BULK_ROWS = 1000`, server-side. Your fast-fail is fine to
keep as a UX nicety.

## 4.6 Banner validation 🔵 ENFORCED, INCLUDING MAGIC BYTES

All three, in `EventService`:

- **≤ 10 MB** (`MAX_BANNER_BYTES`)
- **JPEG / PNG / WebP** content-type allow-list → *"Please upload a JPG, PNG, or WEBP image."*
- **Magic-byte signature sniff** before storing → *"Please upload a valid image file (JPG, PNG, or WEBP)."*

GIF is deliberately rejected. Downgrade to a fast-fail.

---

# 5. Smaller things

## 5.1 `/events/by-organizer` and product staff ⚠️ KEEP THE FALLBACK

`GET /events/by-organizer` is **`@PreAuthorize("hasRole('SUPER_ADMIN')")`**.
`PRODUCT_OFFICER` and `PRODUCT_MANAGER` will get a 403. **Do not delete your
fallback.**

Broader point you'll want: those two roles currently **grant nothing anywhere**.
No `@PreAuthorize` in the fleet names either of them, so a holder is authorized
for exactly what a role-less account is. They exist as labels awaiting a decision
about their remit. If your console implies they can do things, it's ahead of the
backend — tell us what they should reach and we'll add them to the specific
checks.

## 5.2 `TENANT_ADMIN` and `PLATFORM_ADMIN` ⚠️ REAL DEFECT — fixable without a deploy

You found a genuine inconsistency. Confirmed both halves:

- Neither name exists among user-service's built-in roles.
- loyalty-service really does name them:
  `@PreAuthorize("hasAnyRole('TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')")` on
  `POST` and `DELETE /loyalty/exchange-rates/override`.

**The endpoint is not unreachable** — `SUPER_ADMIN` is in that list, so the tenant
FX override works today for a super admin. What doesn't work is *delegating* it to
a tenant admin, which was the point of scoping it that way.

**The fix needs no code.** Since user-service V35, **roles are data**: an operator
can create a role named `TENANT_ADMIN` through `POST /admin/roles` and assign it
via `PUT /admin/users/{id}/roles`. Loyalty checks `hasRole`, which matches on the
role name carried in the token, so a created role will satisfy that check
immediately.

What it *can't* do is grant loyalty **permissions** — the role would need whatever
permissions the rest of your tenant-admin screens require, and those are a fixed
code-defined vocabulary. So: creating the role unblocks the FX override; deciding
what else a tenant admin may do is a separate conversation.

## 5.3 `EVENT_ORGANISER` vs `EVENT_ORGANIZER` 🔵

The Z spelling is correct and is what the enum carries. **Yes please — send the
affected account ids** if any exist with the S form; that's a data correction we'd
rather make from your list than from a guess.

Worth knowing: the S spelling would now *persist* rather than being rejected at
the edge. Role names used to be bound to a Java enum, which 400'd an unknown name
before the controller ran; they're validated against the roles table now instead.
An unknown name is still refused — but the failure mode if one ever slipped
through is a user who authenticates fine and is refused everywhere.

## 5.4 Payment code delivery ⛔ DELIBERATE, but arguable

The code reaching the customer **only** via the payment-request response is a
deliberate trade-off, not an oversight: an out-of-band delivery adds a
notification dependency to the checkout path, and an outage there hides itself.
The accepted cost is exactly the one you describe — drop the response and the code
is lost.

Your point about paying on someone else's device is the strongest argument against
it we've heard, because no amount of defensive UI on your side can recover that
case. Worth re-opening; it's a decision, not a limitation.

## 5.5 Report provenance on exports ⚠️ AGREED

Reports don't state their period and scope, and once exported the numbers lose
their context. Reasonable, self-contained, and we'd take it as a batch — send the
list of report endpoints you export from and the `period` / `scope` shape you want
stamped on them.

---

# 6. Where that leaves your ordered list

| Your priority | Outcome |
|---|---|
| 1. Notifications + unread count | ⛔ **still open** — biggest remaining item, needs scope decisions (§1.4/§1.5 are policy) |
| 2. Service request reject + reason | ✅ **shipped** (#561) |
| 3. Confirm §4.1 oversell guard | ⚠️ **you were right — no server guard. Keep yours.** |
| 4. Authoritative marketplace merchant id | ✅ **answered (loyalty) + the claim that made it usable is shipped** (#560) |
| 5. Marketplace seller approval | ✅ **shipped** (#27) |
| 6. Confirm the ❓ items in §4 | Done: 4.3/4.5/4.6 downgrade to fast-fails; **4.1 and 4.2 stay guards**; 4.4 keep the floors |
| 7. Everything else | Answered above |

## What we need back from you

1. **§4.2** — refuse a reprice on a booked category, or allow it? (See the three options.)
2. **§4.4** — are $0.25 / 1% platform policy? If yes we'll enforce them.
3. **§3.1** — should `PENDING` sellers be blocked from publishing, or keep trading unbadged?
4. **§1.4 / §1.5** — the event taxonomy and recipient rules, before notifications can be built.
5. **§5.2** — confirm you want a `TENANT_ADMIN` role created, and what else it should reach.
6. **§5.3** — the affected account ids, if any.
7. **§5.5** — which report endpoints, and the `period`/`scope` shape.

Happy to go through any of it. Two of your ❓ items (§3.3, and the attribution
half of §2.3) turned out to be things that already existed — so the instinct to
ask rather than assume was the right one.
