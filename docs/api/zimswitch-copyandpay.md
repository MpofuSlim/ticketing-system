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

| Meaning | Pattern | Ledger outcome |
|---|---|---|
| Success | `^(000\.000\.\|000\.100\.1\|000\.[36])` | `SUCCEEDED` |
| Success, needs manual fraud review | `^(000\.400\.0[^3]\|000\.400\.100)` | `SUCCEEDED` + flagged |
| Pending / still open | `^(000\.200)` | stays `TOKEN_ISSUED`, poll again |
| Everything else | — | `FAILED` (declined / rejected / invalid) |

Pending codes mean an open session in the background: it resolves within
~30 minutes or times out. A pending row is **never** auto-failed early —
blocked slot beats double charge, same rule as the InnBucks rail.

`000.200.100` ("successfully created checkout") is a *prepare-checkout*
success, not a payment success — do not feed it through the payment
classifier.

## Configuration

| Property | Env var | Notes |
|---|---|---|
| `zimswitch.base-url` | `ZIMSWITCH_BASE_URL` | UAT `https://eu-test.oppwa.com` |
| `zimswitch.entity-id` | `ZIMSWITCH_ENTITY_ID` | channel id |
| `zimswitch.access-token` | `ZIMSWITCH_ACCESS_TOKEN` | Bearer token; secret |
| `zimswitch.brands` | `ZIMSWITCH_BRANDS` | `data-brands` value (see UNVERIFIED above) |
| `zimswitch.test-mode` | `ZIMSWITCH_TEST_MODE` | `EXTERNAL` in UAT, blank in prod |
| `zimswitch.shopper-result-url` | `ZIMSWITCH_SHOPPER_RESULT_URL` | FE landing page |

The access token is a secret: env-only, never committed, and guarded by
`ProductionSecretsGuard` under deployment profiles like every other credential
in this service.

## Not yet modelled

- **Transaction Reports** endpoint (the post-one-shot lookup path) — needed for
  disputes and for any row whose status read already succeeded.
- **Webhooks.** OPPWA supports server-side notifications, which would remove
  the reliance on the shopper actually returning to `shopperResultUrl`.
  **UNVERIFIED** whether they are enabled on TICKETIZE's entity — worth asking
  ZimSwitch, because a customer who closes the tab after paying currently
  resolves only via the reconciler poll.
- **Refunds / reversals** (`POST /v1/payments/{id}` with `paymentType=RF`).
