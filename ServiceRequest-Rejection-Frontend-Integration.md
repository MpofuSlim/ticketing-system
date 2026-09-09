# Service Request Rejection — Frontend Integration

**Service:** `user-service` · **Merged:** PR #561 (`3a4a758`) · **Migration:** V36

This covers the new **reject** decision on the admin service-request queue, plus
two corrections to the **existing approve** endpoint that change what your error
handling should expect.

---

## 1. Why this exists

`ServiceRequest.Status` was `PENDING, APPROVED` only, and there was no reject
endpoint. An admin who decided *against* a request had **no action available**,
so the row stayed `PENDING` forever — the queue could not drain.

The console was already rendering, filtering and counting a `REJECTED` status
that the database could not store. It can now.

---

## 2. Base URL, auth, headers

| | |
|---|---|
| Base URL | the API gateway origin (`/foundry` prefix in staging/prod, stripped by nginx) |
| Route | `/admin/**` → `lb://user-service` (`user-admin-route`) — already existed, **no gateway change was needed** |
| Auth | `Authorization: Bearer <jwt>` |
| Permission | `service-requests:approve` — the **same** permission as approve |
| `X-Tenant-Id` | **Not required.** This is a platform-admin surface, not tenant-scoped. |
| Content-Type | `application/json` on the reject call |

> **Reject and approve share one permission, deliberately.** Both are the power
> to *decide*. A role that could approve but not reject could only ever say yes,
> which recreates the undrainable queue. Do not build UI that shows Approve but
> hides Reject based on a separate permission — there isn't one.

---

## 3. Endpoints

### 3.1 `GET /admin/service-requests` — the pending queue

Unchanged, except that decided rows now carry `decisionReason`. Returns
`status=PENDING` rows only, oldest first.

Permission: `service-requests:read`.

### 3.2 `PUT /admin/service-requests/{id}/reject` — **new**

**Request body — `reason` is required:**

```json
{
  "reason": "Your business verification is still outstanding — please complete it and re-apply."
}
```

| Field | Type | Rules |
|---|---|---|
| `reason` | string | **required**, non-blank, max **1000** chars. Trimmed server-side. |

`reason` is **shown to the requester verbatim** in their notification. Write the
input's placeholder/help text for that audience — this is not an internal note.
A refusal that doesn't say why is exactly what makes someone re-submit the
identical request.

**200 response:**

```json
{
  "code": "200 OK",
  "message": "Service request rejected",
  "data": {
    "id": 14,
    "userId": 42,
    "userEmail": "alice@innbucks.co.zw",
    "userFullName": "Alice Moyo",
    "service": "marketplace",
    "reason": "We want to sell our products on the InnBucks marketplace.",
    "status": "REJECTED",
    "createdAt": "2026-08-05T18:45:00Z",
    "reviewedAt": "2026-08-05T19:15:00Z",
    "reviewedBy": 1,
    "decisionReason": "Your business verification is still outstanding — please complete it and re-apply."
  }
}
```

### 3.3 `PUT /admin/service-requests/{id}/approve` — unchanged body, **changed errors**

No request body. Still grants the bundle + role. See §5 for what changed.

---

## 4. `reason` vs `decisionReason` — two different people

This is the single easiest thing to get wrong on this screen.

| Field | Whose words | Nullable |
|---|---|---|
| `reason` | **the requester's** own justification for wanting the bundle | never (`NOT NULL`) |
| `decisionReason` | **the reviewer's** reason for the decision | yes |

They are separate columns. Writing the admin's words into `reason` would
overwrite the applicant's own case for their request.

`decisionReason` is:

- **always present** on a `REJECTED` row,
- **optional** on an `APPROVED` row (approve takes no note today, so it is
  absent in practice),
- **absent** while `PENDING`, and on every pre-V36 row.

> ⚠️ **`decisionReason` is OMITTED, not `null`.** `ServiceRequestResponseDTO` is
> annotated `@JsonInclude(NON_NULL)`, so when there is no value the **key is not
> in the payload at all**. Use `data.decisionReason ?? null` / optional chaining;
> do not assume the key exists. The same applies to `reviewedAt`, `reviewedBy`,
> and to `userEmail`/`userFullName` (see §7). *Note: the Swagger example on the
> approve endpoint shows `"decisionReason": null` — that example is wrong; the
> key is absent. The guide is correct, the Swagger example needs a one-line fix.*

---

## 5. Error handling

### 5.1 Top-level errors (both endpoints)

All use the standard `ApiResult` envelope.

| Status | When | Body |
|---|---|---|
| **404** | request id doesn't exist | `{ "code": "404 NOT_FOUND", "message": "Service request not found: 99", "data": null }` |
| **400** | already decided | `{ "code": "400 BAD_REQUEST", "message": "Service request 14 is not pending (status=APPROVED).", "data": null }` |
| **403** | caller lacks `service-requests:approve` | standard 403 |

The "already decided" message **names the status the row already holds**, so you
can surface *"A colleague already approved this"* rather than a generic failure.
Re-fetch the queue on this error — your list is stale.

### 5.2 Per-field (bean validation) — reject only

A missing or blank `reason` fails bean validation, which uses a **different
envelope**: the per-field messages are in `data`, and `message` is the constant
string `"Validation failed"`.

```json
{
  "code": "400 BAD_REQUEST",
  "message": "Validation failed",
  "data": { "reason": "reason is required" }
}
```

Over 1000 chars gives `{ "reason": "reason must be 1000 characters or fewer" }`.

**So a 400 on reject has two possible shapes.** Branch on whether `data` is a
non-null object: if it is, render the field errors against the inputs; if it is
`null`, render `message` as a toast.

---

## 6. ⚠️ Two corrections to the EXISTING approve endpoint

These are behaviour changes on an endpoint you already call. **The old Swagger
documented a 404 that the code did not actually return.**

Before this PR, both failure paths threw a bare `RuntimeException`, which
`GlobalExceptionHandler` collapses into:

```json
{ "code": "400 BAD_REQUEST", "message": "We couldn't process your request. Please try again.", "data": null }
```

That invited an admin to **retry an operation that can never succeed**, and hid
that a colleague had already decided the row.

| Case | Before | Now |
|---|---|---|
| not found | 400, generic "try again" text | **404**, real message |
| already decided | 400, generic "try again" text | **400 naming the status it holds** |

If your approve error handler special-cases that generic string, or assumes 400
is the only failure code, update it. `reject` was built correctly from the
start; `approve` was fixed alongside it.

---

## 7. Requester notifications — both outcomes now notify

Previously **approval was silent** — a merchant learned their request was
granted by opening the app and noticing a new menu item.

Both outcomes now publish `ServiceRequestDecided`, consumed
`AFTER_COMMIT` and dispatched email-first / WhatsApp-fallback.

- The **approval** copy tells them to **sign in again** — the grant rides in the
  JWT and is invisible until a fresh token is minted. Mirror that in any
  in-app confirmation you show the admin, or you'll get *"it says approved but
  I still can't see it"* tickets.
- The **rejection** copy carries the reviewer's `reason` and says they may
  apply again.

Nothing is sent if the decision transaction rolls back.

**Deleted requesters:** approve requires the user to still exist; **reject does
not**. A request whose account was deleted can still be closed — nothing is
granted, so there is nothing to grant to a missing user. In that case
`userEmail` and `userFullName` are **absent from the response** (per
`NON_NULL`), and nobody is notified. Render a placeholder rather than crashing
on a missing name.

---

## 8. A rejection is not a ban

The pending-uniqueness index is scoped `WHERE status = 'PENDING'`, so a rejected
row does **not** block that user re-requesting the same bundle later. A rejection
decides *one request*.

Don't render "permanently declined" or grey out the bundle. If your UI offers a
re-apply affordance, it will work.

---

## 9. Gotchas checklist

- [ ] `reason` is required on reject — client-side validate before sending, but still handle the 400.
- [ ] A 400 on reject has **two shapes**; branch on `data != null`.
- [ ] `decisionReason` / `reviewedAt` / `reviewedBy` / `userEmail` / `userFullName` are **absent when null**, not `null`.
- [ ] Don't confuse `reason` (requester's) with `decisionReason` (reviewer's).
- [ ] Update approve's error handling: **404 is now possible** where it never was.
- [ ] On "is not pending (status=…)", re-fetch the queue — your list is stale.
- [ ] Reject uses the **same permission** as approve; there is no separate reject permission to gate on.
- [ ] Timestamps carry the `Z` suffix (`UtcJsonTimeConfig`). Parse as UTC, render local.
- [ ] `status` is now one of `PENDING | APPROVED | REJECTED`.
- [ ] No `X-Tenant-Id` on this surface.
- [ ] Tell the admin the approved user must sign in again for the grant to take effect.
