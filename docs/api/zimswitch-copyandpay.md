# ZimSwitch Online (COPYandPAY) — distilled integration notes

Source of truth: <https://zimswitch.docs.oppwa.com/integrations/widget> (plus
the `COPYandPAY API` / `Advanced Options` pages in the same section). ZimSwitch
Online is a white-label of **ACI Worldwide's OPPWA** platform, so the wire
contract is the standard COPYandPAY one; the ZimSwitch-specific parts are the
host, the entity, and the private-label brand.

This file distills the parts ticketing consumes so they're greppable. If the
wire shape observed in UAT/production diverges, update this file **and** the
`ZimswitchCopyPayClientContractTest` stubs in the same PR.

> **Provenance.** The three-step flow, the `integrity` parameter, the
> `baseUrl + resourcePath` status call, the checkout-reuse semantics, the
> one-shot status read and the 2-calls-per-minute throttle are transcribed
> from the ZimSwitch widget page. Anything marked **UNVERIFIED** below was
> not on the pages read and must be confirmed before go-live.

**This is the CARD rail — additive, not a replacement.** Ticket payments keep
the InnBucks 2D-code rail (`docs/api/innbucks-merchant-api.md`); COPYandPAY
adds card acceptance alongside it. See `Payment.paymentRail`.

## Platform conventions

- `{{baseUrl}}` is environment-specific; UAT is `https://eu-test.oppwa.com`.
  **The base URL must end in `/`** when concatenated with a `resourcePath`.
- Auth is `Authorization: Bearer <access token>` on every call, plus
  `entityId` as a *request parameter* (not a header) identifying the channel.
- Requests are **`application/x-www-form-urlencoded`**, not JSON. All
  parameters go in the POST **body**, never the URL.
- **Amounts are in MAJOR units with exactly 2 decimals** (`"92.00"`), i.e.
  dollars — the OPPOSITE of the InnBucks Merchant API's cents. This service
  now talks to both rails, so the conversion is per-rail and each has its own
  echo cross-check. See the 100x guard note below.
- Response envelope: every response carries `result.code` + `result.description`,
  an `id`, `buildNumber`, `ndc` and `timestamp`.
- `testMode=EXTERNAL` routes UAT transactions to the external test system
  rather than the gateway's internal simulator. TICKETIZE's UAT entity is set
  up for `EXTERNAL`.

## 1. Prepare the checkout (server-to-server)

```
POST {{baseUrl}}/v1/checkouts
Authorization: Bearer <token>
Content-Type: application/x-www-form-urlencoded

entityId=<entity>&amount=92.00&currency=USD&paymentType=DB&integrity=true
```

- `paymentType=DB` — debit (immediate capture). `PA` (preauth) + `CP` (capture)
  is the two-step alternative; ticketing uses `DB`.
- `integrity=true` — **always send this.** It makes the response carry an
  `integrity` value (an SRI digest) for the widget `<script>` tag, so a
  tampered third-party script that is about to handle card entry fails closed
  in the browser. Matches the repo's A08 pin-everything-immutable posture.
- `merchantTransactionId` — our `TKT-PMT-<uuid>` payment reference. This is the
  handle that ties a gateway transaction back to a ledger row; always send it.

Response (`200`):

```json
{
  "result": { "code": "000.200.100", "description": "successfully created checkout" },
  "buildNumber": "...",
  "timestamp": "...",
  "ndc": "...",
  "id": "8a82944a4cc25ebf014cc2c782423202",
  "integrity": "sha384-..."
}
```

`000.200.100` = checkout created. The `id` is the **checkoutId**.

## 2. Create the payment form (browser)

```html
<script src="{{baseUrl}}/v1/paymentWidgets.js?checkoutId={checkoutId}"
        integrity="{integrity}"
        crossorigin="anonymous"></script>

<form action="{shopperResultUrl}" class="paymentWidgets" data-brands="VISA MASTER AMEX"></form>
```

- Card data goes **browser → gateway directly**. It never touches our DOM or
  our servers — that is the whole basis of the SAQ-A scope. Do not proxy,
  log, or server-side-post card fields, ever.
- Multiple `<form>` elements with different `data-brands` render separate
  branded forms.
- **UNVERIFIED — the `data-brands` value for TICKETIZE's `Private label`
  payment mode.** The doc's examples are all `VISA MASTER AMEX`; the
  supported-brand list was collapsed on the page read. Configured as
  `zimswitch.brands` so it is a config change, not a code change.

### Checkout id lifetime — read this before touching retry logic

Verbatim from the doc:

> A checkout id expires when a payment has been finalized successfully by
> user, but not later than 30 minutes. Before it expires, it can be used
> multiple times in order to retrieve a valid payment form. […] Therefore you
> don't have to generate a new checkout ID in such scenarios. **However be
> aware that such cases can generate multiple transactions in the system, for
> example one (or more) failed and another one successful, based on the same
> checkout id.**

Two consequences the code depends on:

1. **Never mint a fresh checkout on retry** while the current one is live —
   reuse the stored `checkoutId`. Same rule as "code generation is NEVER
   retried" on the InnBucks rail, for the same reason.
2. **One ledger row ≠ one gateway transaction.** A single checkout can carry
   several attempts (reload, back button, a decline then a success). The
   status response is what resolves which one counts.

## 3. Get the payment status (server-to-server)

The shopper is redirected to `shopperResultUrl?resourcePath=/v1/checkouts/{checkoutId}/payment`,
and the documented call is `GET {{baseUrl}} + resourcePath`:

```
GET {{baseUrl}}/v1/checkouts/{checkoutId}/payment?entityId=<entity>
Authorization: Bearer <token>
```

> **SECURITY — do not follow the documented happy path literally.**
> `resourcePath` arrives as a query parameter on a browser redirect, i.e. it is
> attacker-controllable. Concatenating it onto our base URL builds a
> server-side request URL — carrying our Bearer token — out of untrusted
> input: a textbook SSRF / credential-exfiltration shape. Our client
> **ignores the supplied `resourcePath` entirely** and rebuilds the path from
> the `checkoutId` we stored when we prepared the checkout. The inbound
> `resourcePath` is validated against
> `^/v1/checkouts/[A-Za-z0-9.\-]{1,64}/payment$` and compared to our own
> checkout id purely as a mismatch signal.

### The successful read is ONE-SHOT

> Once a status response is successful the checkout identifier can't be used
> anymore.

After the first successful status read the checkoutId is dead and further
lookups must go to the **Transaction Reports** endpoint. So the first
successful read is the only chance to capture the outcome via this route, and
it MUST be persisted in the same transaction that consumes it. This is why the
status path writes through `PaymentRecordService` before doing anything else,
and why a failed order-confirm lands in `COMPLETED_UNCONFIRMED` (the reconciler
then retries the *confirm*, never the status read).

**Accepted residual risk:** the outcome can still be lost between the gateway
sending the success response and our first ledger write — a crash in that
millisecond window, or a read TIMEOUT on the very request that consumed the
one-shot answer (the retry then sees `200.300.404`). Such a row expires via
the NOT_FOUND-past-deadline rule even though the customer paid. The safety
net is ZimSwitch-side records: the Transaction Reports endpoint / settlement
recon for card rows (both on the "Not yet modelled" list) — until then it is
an operator query against the gateway portal, keyed by our
`merchantTransactionId`.

### Throttle

> Per checkout, it is allowed to send two get payment requests in a minute.

The reconciler must respect this — see `zimswitch.status-poll-min-interval`.

### Verify the echo (the 100x guard)

The doc explicitly recommends comparing the returned **ID(s), Amount,
Currency, Brand and Type** against what was sent. That is the same
amount-echo cross-check the InnBucks rail already does, and it is mandatory
here: a `9200` sent where `92.00` was meant is a 100x charge, and this service
now has two rails with opposite unit conventions.

## Result codes

Classified by regex on `result.code` (`ZimswitchResultCode`):

| Meaning | Pattern | Resolution behaviour |
|---|---|---|
| Success | `^(000\.000\.\|000\.100\.1\|000\.[36])` | echo-verify → money fact persisted (`COMPLETED_UNCONFIRMED`) → order confirm → `SUCCEEDED` |
| Success, needs manual fraud review | `^(000\.400\.0[^3]\|000\.400\.100)` | as above, plus a journal note with the review flag |
| Pending / still open | `^(000\.200)` | stays `TOKEN_ISSUED`, poll again |
| No payment for this checkout | exactly `200.300.404` | `CHECKOUT_NOT_FOUND` — see below |
| Everything else | — | decline: journalled, row **stays open** (see below) |

Nuances the classifier table can't carry:

- **`200.300.404` is the NORMAL answer while the shopper still has the form
  open** (nothing submitted yet), and also what a dead checkout answers.
  Before the local deadline it means "keep waiting"; after the deadline +
  grace (past the gateway's own 30-minute ceiling, when no new transaction
  can exist) it is a POSITIVE never-paid answer and the row is `EXPIRED`,
  freeing the order's payment slot.
- **A decline does NOT close the row.** The checkout stays alive upstream
  and the shopper can retry another card on the same checkout (the
  documented multi-transaction reuse). The decline is journalled verbatim;
  an unpaid row lapses via the `200.300.404`-past-deadline rule.
- A pending row is **never** auto-failed early — blocked slot beats double
  charge, same rule as the InnBucks rail.
- An unmatched code classifies as a decline, never as success — the success
  families (`000.*`) are the stable part of the OPPWA taxonomy.

`000.200.100` ("successfully created checkout") is a *prepare-checkout*
success, not a payment success — do not feed it through the payment
classifier.

## How it lands in payment-service

- One `payment` ledger row per attempt, `payment_rail=ZIMSWITCH_CARD` (V13);
  `checkout_id`/`checkout_integrity` are the card twins of the InnBucks
  `code_auth_number`/QR columns and `code_expires_at` doubles as the checkout
  deadline, so the staleness sweeps and workbasket cover both rails
  unchanged.
- `ZimswitchCardPaymentService.startCheckout` mirrors
  `InnbucksPaymentService.processPayment` step for step (slot check → gateway
  fetch → hold extension → PENDING row → upstream call → `TOKEN_ISSUED`).
- Resolution (`resolveOpenCheckout`) is shared verbatim by the reconciler's
  card poll and the customer-triggered instant check on replay; the
  `card_status_checked_at` stamp keeps their combined rate inside the
  2-per-minute throttle.
- A paid read persists `COMPLETED_UNCONFIRMED` BEFORE confirming the order
  (the one-shot read means a crash after confirm-first would lose the money
  fact); the existing confirm-retry sweep then promotes to `SUCCEEDED`.
- An echo mismatch on a paid read parks `IN_DOUBT` (a transition added to
  the legal map for exactly this) — no auto-resolver, operator only.

## FE contract (additive to the historical stub shape)

Request: `POST /payments` with the usual order key plus
`"paymentRail": "ZIMSWITCH_CARD"`. Response (`status=PROCESSING`) carries
`checkoutId`, `checkoutScriptUrl`, `checkoutIntegrity`, `checkoutBrands`,
`shopperResultUrl`, `checkoutExpiresAt`; render:

```html
<script src="{checkoutScriptUrl}" integrity="{checkoutIntegrity}"
        crossorigin="anonymous"></script>
<form action="{shopperResultUrl}" class="paymentWidgets"
      data-brands="{checkoutBrands}"></form>
```

On landing back on `shopperResultUrl`, IGNORE the `resourcePath` query
parameter and re-POST `/payments` with the same order key — the backend
verifies server-side (the redirect is never proof of payment) and replies
SUCCESS / PROCESSING; bookings then confirm exactly like the code rail
(poll the booking). A re-POST while the checkout is open replays the SAME
checkout (reload/decline-retry); after `checkoutExpiresAt` it mints a fresh
one. One active payment per order across BOTH rails — switching rails needs
the open attempt to lapse first.

## Configuration

| Property | Env var | Notes |
|---|---|---|
| `zimswitch.base-url` | `ZIMSWITCH_BASE_URL` | default `https://eu-test.oppwa.com` (UAT) |
| `zimswitch.entity-id` | `ZIMSWITCH_ENTITY_ID` | channel id; blank = rail disabled |
| `zimswitch.access-token` | `ZIMSWITCH_ACCESS_TOKEN` | Bearer token; SECRET; blank = rail disabled |
| `zimswitch.brands` | `ZIMSWITCH_BRANDS` | `data-brands` value (see UNVERIFIED above) |
| `zimswitch.test-mode` | `ZIMSWITCH_TEST_MODE` | `EXTERNAL` in UAT; blank in prod (param omitted) |
| `zimswitch.shopper-result-url` | `ZIMSWITCH_SHOPPER_RESULT_URL` | FE result page; echoed to the FE as the widget form action |
| `zimswitch.request-integrity` | `ZIMSWITCH_REQUEST_INTEGRITY` | default `true` — SRI digest for the widget script |
| `zimswitch.checkout-ttl` | `ZIMSWITCH_CHECKOUT_TTL` | default `PT28M`, just under the gateway's 30-min ceiling |
| `zimswitch.status-poll-min-interval` | `ZIMSWITCH_STATUS_POLL_MIN_INTERVAL` | default `PT30S` — the poller's share of the 2/min throttle |
| `zimswitch.instant-check-min-gap` | `ZIMSWITCH_INSTANT_CHECK_MIN_GAP` | default `PT15S` — the customer instant check's share |
| `payment-service.card-poll.interval` | `CARD_POLL_INTERVAL` | default `PT30S` — sweep cadence |

Amount/currency always come from the ORDER (gateway snapshot / cell config)
— deliberately no ZimSwitch-side currency setting, so the two rails cannot
disagree about what an order costs.

Blank credentials = the card rail is OFF (card attempts answer 503; the
poller logs and skips). There is no committed placeholder for the token, so
`ProductionSecretsGuard` has nothing to catch — the fail-safe is the
isConfigured() gate, mirroring the `BANK_API_*` credential pattern.

## Not yet modelled

- **Transaction Reports** endpoint (the post-one-shot lookup path) — needed for
  disputes and for any row whose status read already succeeded.
- **Webhooks.** OPPWA supports server-side notifications, which would remove
  the reliance on the shopper actually returning to `shopperResultUrl`.
  **UNVERIFIED** whether they are enabled on TICKETIZE's entity — worth asking
  ZimSwitch, because a customer who closes the tab after paying currently
  resolves only via the reconciler poll.
- **Refunds / reversals** (`POST /v1/payments/{id}` with `paymentType=RF`).
